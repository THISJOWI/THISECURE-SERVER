package config

import (
	"fmt"
	"os"
)

const ServiceVersion = "1.0.0"

type Config struct {
	Port            string
	DatabaseURL     string
	JWTSecret       string
	EncryptionKey   string
	KafkaSigningKey string
	KafkaBrokers    []string
	DBSSLMode       string
}

func Load() Config {
	cfg := Config{
		Port:            getEnv("PORT", "8083"),
		JWTSecret:       getEnv("JWT_SECRET", ""),
		EncryptionKey:   getEnv("ENCRYPTION_KEY", ""),
		KafkaSigningKey: getEnv("KAFKA_SIGNING_KEY", ""),
		DBSSLMode:       getEnv("DB_SSLMODE", "disable"),
	}

	dbHost := getEnv("DB_HOST", "localhost")
	dbPort := getEnv("DB_PORT", "5432")
	dbUser := getEnv("DB_USERNAME", "postgres")
	dbPass := getEnv("DB_PASSWORD", "postgres")
	cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/notes?sslmode=%s", dbUser, dbPass, dbHost, dbPort, cfg.DBSSLMode)

	kafkaHost := getEnv("KAFKA_HOST", "localhost")
	kafkaPort := getEnv("KAFKA_PORT", "9092")
	cfg.KafkaBrokers = []string{fmt.Sprintf("%s:%s", kafkaHost, kafkaPort)}

	return cfg
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
