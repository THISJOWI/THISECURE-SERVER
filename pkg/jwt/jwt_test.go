package jwt_test

import (
	"testing"

	golangjwt "github.com/golang-jwt/jwt/v5"
	"github.com/thisuite/thisecure/pkg/jwt"
	"github.com/stretchr/testify/require"
)

func TestValidateToken_Valid(t *testing.T) {
	secret := []byte("my-very-secret-key-1234567890abcd")
	token := golangjwt.NewWithClaims(golangjwt.SigningMethodHS256, golangjwt.MapClaims{"sub": "user-123"})
	tokenStr, err := token.SignedString(secret)
	require.NoError(t, err)

	sub, err := jwt.ValidateToken(tokenStr, secret)
	require.NoError(t, err)
	require.Equal(t, "user-123", sub)
}

func TestValidateToken_InvalidSecret(t *testing.T) {
	secret := []byte("my-very-secret-key-1234567890abcd")
	token := golangjwt.NewWithClaims(golangjwt.SigningMethodHS256, golangjwt.MapClaims{"sub": "user-123"})
	tokenStr, _ := token.SignedString(secret)

	_, err := jwt.ValidateToken(tokenStr, []byte("wrong-secret-1234567890abcdefgh"))
	require.Error(t, err)
}

func TestValidateToken_Expired(t *testing.T) {
	secret := []byte("my-very-secret-key-1234567890abcd")
	token := golangjwt.NewWithClaims(golangjwt.SigningMethodHS256, golangjwt.MapClaims{
		"sub": "user-123",
		"exp": 1000000000,
	})
	tokenStr, err := token.SignedString(secret)
	require.NoError(t, err)

	_, err = jwt.ValidateToken(tokenStr, secret)
	require.Error(t, err)
}
