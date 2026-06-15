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
	"github.com/thisuite/thisecure/password/internal/config"
	"github.com/thisuite/thisecure/password/internal/handler"
	"github.com/thisuite/thisecure/password/internal/repository"
	"github.com/thisuite/thisecure/password/internal/service"
	"github.com/thisuite/thisecure/pkg/database"
	"github.com/thisuite/thisecure/pkg/kafka"
	mid "github.com/thisuite/thisecure/pkg/middleware"
)

func main() {
	cfg := config.Load()
	ctx := context.Background()

	if cfg.JWTSecret == "" || len(cfg.JWTSecret) < 32 {
		log.Fatal("JWT_SECRET must be set and at least 32 characters")
	}
	if cfg.EncryptionKey == "" || len(cfg.EncryptionKey) != 64 {
		log.Fatal("ENCRYPTION_KEY must be set and exactly 32 bytes (64 hex characters)")
	}

	pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	encKey := []byte(cfg.EncryptionKey)
	jwtSecret := []byte(cfg.JWTSecret)
	signingKey := []byte(cfg.KafkaSigningKey)
	if len(signingKey) == 0 {
		signingKey = jwtSecret
	}

	signer := kafka.NewSigner(signingKey)
	syncProducer := kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)
	defer syncProducer.Close()

	pwRepo := repository.NewPasswordRepo(pool)
	pwSvc := service.NewPasswordService(pwRepo, encKey, syncProducer)
	dedupSvc := service.NewDedupService(pwRepo)
	pwH := handler.NewPasswordHandler(pwSvc, dedupSvc)

	r := gin.Default()
	r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
	r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })

	v1 := r.Group("/v1/passwords", mid.JWTAuth(jwtSecret))
	pwH.Register(v1)

	srv := &http.Server{Addr: ":" + cfg.Port, Handler: r}
	go func() {
		log.Printf("password service listening on :%s", cfg.Port)
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
	srv.Shutdown(shutdownCtx)
}
