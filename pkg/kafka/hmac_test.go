package kafka_test

import (
	"testing"

	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/stretchr/testify/require"
)

func TestSignAndVerify(t *testing.T) {
	s := kafka.NewSigner([]byte("secret-key-1234567890abcdefgh"))
	msg := []byte(`{"eventId":"abc"}`)
	sig := s.Sign(msg)
	require.NoError(t, s.Verify(msg, sig))
}

func TestVerify_WrongKey(t *testing.T) {
	s1 := kafka.NewSigner([]byte("key-one-1234567890abcdefghij"))
	s2 := kafka.NewSigner([]byte("key-two-1234567890abcdefghij"))
	msg := []byte(`{"eventId":"abc"}`)
	sig := s1.Sign(msg)
	require.Error(t, s2.Verify(msg, sig))
}

func TestVerify_TamperedMessage(t *testing.T) {
	s := kafka.NewSigner([]byte("secret-key-1234567890abcdefgh"))
	msg := []byte(`{"eventId":"abc"}`)
	sig := s.Sign(msg)
	require.Error(t, s.Verify([]byte(`{"eventId":"xyz"}`), sig))
}

func TestSign_Deterministic(t *testing.T) {
	s := kafka.NewSigner([]byte("secret-key-1234567890abcdefgh"))
	msg := []byte("hello")
	sig1 := s.Sign(msg)
	sig2 := s.Sign(msg)
	require.Equal(t, sig1, sig2)
}
