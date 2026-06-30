package main

import (
	"context"
	"encoding/hex"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/note/internal/config"
	"github.com/thisuite/thisecure/note/internal/handler"
	"github.com/thisuite/thisecure/note/internal/repository"
	"github.com/thisuite/thisecure/note/internal/service"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/database"
	"github.com/thisuite/thisecure/pkg/discovery"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/metrics"
	mid "github.com/thisuite/thisecure/pkg/middleware"
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
	encKey, err := hex.DecodeString(cfg.EncryptionKey)
	if err != nil {
		log.Fatalf("ENCRYPTION_KEY: invalid hex: %v", err)
	}
	if err := crypto.ValidateKey(encKey); err != nil {
		log.Fatalf("ENCRYPTION_KEY: %v", err)
	}

	pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	jwtSecret := []byte(cfg.JWTSecret)
	if cfg.KafkaSigningKey == "" {
		log.Fatal("KAFKA_SIGNING_KEY must be set (separate from JWT_SECRET)")
	}
	signer := kafka.NewSigner([]byte(cfg.KafkaSigningKey))
	syncProducer := kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)
	defer syncProducer.Close()

	noteRepo := repository.NewNoteRepo(pool)
	noteSvc := service.NewNoteService(noteRepo, encKey, syncProducer)
	noteH := handler.NewNoteHandler(noteSvc)

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()
	r.Use(mid.SecurityHeaders())
	r.Use(mid.CORS(nil))
	r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
	r.Use(metrics.Middleware())
	r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok", "service": "note"}) })
	r.GET("/ready", func(c *gin.Context) {
		if err := pool.Ping(c.Request.Context()); err != nil {
			c.JSON(503, gin.H{"status": "not ready", "error": "database unavailable"})
			return
		}
		c.JSON(200, gin.H{"status": "ready", "service": "note"})
	})
	r.GET("/metrics", metrics.PrometheusHandler())
	r.GET("/metrics/json", metrics.JSONHandler())
	r.GET("/discovery", discovery.Handler(r, "note"))

	v1 := r.Group("/v1/notes", mid.JWTAuth(jwtSecret))
	noteH.Register(v1)

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      r,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}
	go func() {
		log.Printf("note service listening on :%s", cfg.Port)
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
