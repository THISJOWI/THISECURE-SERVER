package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"

	"github.com/segmentio/kafka-go"
)

type Handler func(ctx context.Context, key string, value []byte) error

type Consumer struct {
	reader  *kafka.Reader
	signer  *Signer
	handler Handler
}

func NewConsumer(brokers []string, topic, groupID string, signer *Signer, handler Handler) *Consumer {
	return &Consumer{
		reader: kafka.NewReader(kafka.ReaderConfig{
			Brokers:  brokers,
			Topic:    topic,
			GroupID:  groupID,
			MinBytes: 10,
			MaxBytes: 10e6,
		}),
		signer:  signer,
		handler: handler,
	}
}

func (c *Consumer) Run(ctx context.Context) error {
	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			return fmt.Errorf("fetch: %w", err)
		}
		if err := c.verifySignature(msg); err != nil {
			log.Printf("WARN: signature verification failed: %v", err)
			continue
		}
		if err := c.handler(ctx, string(msg.Key), msg.Value); err != nil {
			log.Printf("ERROR: handler failed: %v", err)
			continue
		}
		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			return fmt.Errorf("commit: %w", err)
		}
	}
}

func (c *Consumer) verifySignature(msg kafka.Message) error {
	var signature string
	for _, h := range msg.Headers {
		if h.Key == "X-Signature" {
			signature = string(h.Value)
			break
		}
	}
	if signature == "" {
		return fmt.Errorf("missing X-Signature header")
	}
	return c.signer.Verify(msg.Value, signature)
}

func (c *Consumer) Close() error {
	return c.reader.Close()
}

func Decode[T any](data []byte) (*T, error) {
	var v T
	if err := json.Unmarshal(data, &v); err != nil {
		return nil, err
	}
	return &v, nil
}
