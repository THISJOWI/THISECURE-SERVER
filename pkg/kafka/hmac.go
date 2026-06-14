package kafka

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
)

type Signer struct {
	key []byte
}

func NewSigner(key []byte) *Signer {
	return &Signer{key: key}
}

func (s *Signer) Sign(message []byte) string {
	mac := hmac.New(sha256.New, s.key)
	mac.Write(message)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func (s *Signer) Verify(message []byte, signature string) error {
	expected := s.Sign(message)
	if !hmac.Equal([]byte(expected), []byte(signature)) {
		return fmt.Errorf("HMAC mismatch")
	}
	return nil
}
