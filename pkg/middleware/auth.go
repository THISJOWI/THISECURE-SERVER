package middleware

import (
	"log"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/pkg/jwt"
)

const ContextKeyUserID = "userId"

func JWTAuth(jwtSecret []byte) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing Authorization header"})
			return
		}
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid Authorization header format"})
			return
		}
		userID, err := jwt.ValidateToken(parts[1], jwtSecret)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}
		c.Set(ContextKeyUserID, userID)
		c.Next()
	}
}

func GetUserID(c *gin.Context) string {
	v, exists := c.Get(ContextKeyUserID)
	if !exists {
		log.Printf("CRITICAL: userID not found in context — middleware missing on %s %s", c.Request.Method, c.Request.URL.Path)
		return ""
	}
	s, ok := v.(string)
	if !ok || s == "" {
		log.Printf("CRITICAL: userID is empty or wrong type on %s %s", c.Request.Method, c.Request.URL.Path)
		return ""
	}
	return s
}
