package config

import "os"

type Config struct {
	Port          string
	DatabaseURL   string
	JWTSecret     string
	EncryptionKey string
	KafkaBrokers  []string
}

func Load() Config {
	return Config{
		Port:          getEnv("PORT", "8084"),
		DatabaseURL:   getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5435/password?sslmode=disable"),
		JWTSecret:     getEnv("JWT_SECRET", ""),
		EncryptionKey: getEnv("ENCRYPTION_KEY", ""),
		KafkaBrokers:  []string{getEnv("KAFKA_BROKERS", "localhost:9092")},
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
