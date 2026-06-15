package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/otp/internal/config"
	"github.com/thisuite/thisecure/otp/internal/handler"
	"github.com/thisuite/thisecure/otp/internal/repository"
	"github.com/thisuite/thisecure/otp/internal/service"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/database"
	"github.com/thisuite/thisecure/pkg/kafka"
	mid "github.com/thisuite/thisecure/pkg/middleware"
	"github.com/thisuite/thisecure/pkg/models"
)

func main() {
	cfg := config.Load()
	ctx := context.Background()

	if cfg.JWTSecret == "" || len(cfg.JWTSecret) < 32 {
		log.Fatal("JWT_SECRET must be set and at least 32 characters")
	}
	if cfg.EncryptionKey == "" {
		log.Fatal("ENCRYPTION_KEY must be set")
	}
	encKey := []byte(cfg.EncryptionKey)
	if err := crypto.ValidateKey(encKey); err != nil {
		log.Fatalf("ENCRYPTION_KEY: %v", err)
	}

	pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	jwtSecret := []byte(cfg.JWTSecret)
	signingKey := []byte(cfg.KafkaSigningKey)
	if len(signingKey) == 0 {
		signingKey = jwtSecret
	}

	signer := kafka.NewSigner(signingKey)
	syncProd := kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)
	eventProd := kafka.NewProducer(cfg.KafkaBrokers, "otp-events", signer)

	otpRepo := repository.NewOtpRepo(pool)
	qrSvc := service.NewQrService()
	otpSvc := service.NewOtpService(otpRepo, encKey, eventProd, syncProd)
	otpH := handler.NewOtpHandler(otpSvc, qrSvc)

	consumer := kafka.NewConsumer(cfg.KafkaBrokers, "auth-events", "otp-service-group", signer, func(ctx context.Context, key string, value []byte) error {
		event, err := kafka.Decode[models.UserRegisteredEvent](value)
		if err != nil {
			return err
		}
		log.Printf("received USER_REGISTERED event: userId=%s, email=%s", event.UserID, event.Email)
		return nil
	})
	go func() {
		if err := consumer.Run(ctx); err != nil {
			log.Printf("kafka consumer stopped: %v", err)
		}
	}()

	r := gin.Default()
	r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
	r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })

	v1 := r.Group("/v1/otp", mid.JWTAuth(jwtSecret))
	otpH.Register(v1)

	srv := &http.Server{Addr: ":" + cfg.Port, Handler: r}
	go func() {
		log.Printf("otp service listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("shutting down...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	consumer.Close()
	syncProd.Close()
	eventProd.Close()
	srv.Shutdown(shutdownCtx)
}
