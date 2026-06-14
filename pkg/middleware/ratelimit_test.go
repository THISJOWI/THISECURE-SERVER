package middleware_test

import (
	"testing"
	"time"

	"github.com/thisuite/thisecure/pkg/middleware"
	"github.com/stretchr/testify/require"
)

func TestRateLimiter_AllowsWithinBurst(t *testing.T) {
	rl := middleware.NewRateLimiter(1, 5, time.Second)
	for i := 0; i < 5; i++ {
		require.True(t, rl.Allow("127.0.0.1"))
	}
}

func TestRateLimiter_BlocksExcess(t *testing.T) {
	rl := middleware.NewRateLimiter(1, 3, time.Second)
	for i := 0; i < 3; i++ {
		rl.Allow("127.0.0.1")
	}
	require.False(t, rl.Allow("127.0.0.1"))
}

func TestRateLimiter_DifferentIPs(t *testing.T) {
	rl := middleware.NewRateLimiter(1, 1, time.Second)
	require.True(t, rl.Allow("1.1.1.1"))
	require.True(t, rl.Allow("2.2.2.2"))
}

func TestRateLimiter_RecoversAfterInterval(t *testing.T) {
	rl := middleware.NewRateLimiter(1, 1, 50*time.Millisecond)
	require.True(t, rl.Allow("127.0.0.1"))
	require.False(t, rl.Allow("127.0.0.1"))
	time.Sleep(60 * time.Millisecond)
	require.True(t, rl.Allow("127.0.0.1"))
}
