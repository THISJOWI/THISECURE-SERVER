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
	"github.com/thisuite/thisecure/note/internal/config"
	"github.com/thisuite/thisecure/note/internal/handler"
	"github.com/thisuite/thisecure/note/internal/repository"
	"github.com/thisuite/thisecure/note/internal/service"
	"github.com/thisuite/thisecure/pkg/database"
	"github.com/thisuite/thisecure/pkg/kafka"
	mid "github.com/thisuite/thisecure/pkg/middleware"
)

func main() {
	cfg := config.Load()
	ctx := context.Background()

	pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	encKey := []byte(cfg.EncryptionKey)
	jwtSecret := []byte(cfg.JWTSecret)

	signer := kafka.NewSigner(jwtSecret)
	syncProducer := kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)
	defer syncProducer.Close()

	noteRepo := repository.NewNoteRepo(pool)
	noteSvc := service.NewNoteService(noteRepo, encKey, syncProducer)
	noteH := handler.NewNoteHandler(noteSvc)

	r := gin.Default()
	r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
	r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })

	v1 := r.Group("/v1/notes", mid.JWTAuth(jwtSecret))
	noteH.Register(v1)

	srv := &http.Server{Addr: ":" + cfg.Port, Handler: r}
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
