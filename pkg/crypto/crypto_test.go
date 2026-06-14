package crypto_test

import (
	"testing"

	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/stretchr/testify/require"
)

func TestEncryptDecrypt_RoundTrip(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef")
	original := []byte("hello world")
	encoded, err := crypto.Encrypt(original, key)
	require.NoError(t, err)

	decoded, err := crypto.Decrypt(encoded, key)
	require.NoError(t, err)
	require.Equal(t, original, decoded)
}

func TestDecrypt_WrongKey(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef")
	wrongKey := []byte("abcdef0123456789abcdef0123456789")
	encoded, err := crypto.Encrypt([]byte("secret"), key)
	require.NoError(t, err)

	_, err = crypto.Decrypt(encoded, wrongKey)
	require.Error(t, err)
}

func TestDecrypt_InvalidBase64(t *testing.T) {
	key := []byte("0123456789abcdef0123456789abcdef")
	_, err := crypto.Decrypt("not-base64!!!", key)
	require.Error(t, err)
}

func TestValidateKey(t *testing.T) {
	require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef")))
	require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef01234567")))
	require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef0123456789abcdef")))
	require.Error(t, crypto.ValidateKey([]byte("short")))
}
