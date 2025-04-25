package middlewares

import (
	"banka1.com/redis"
	"crypto/sha256"
	"encoding/hex"
	"github.com/gofiber/fiber/v2"
	"time"
)

func CacheMiddleware(expiration time.Duration) fiber.Handler {
	return func(c *fiber.Ctx) error {
		if c.Method() != "GET" {
			return c.Next()
		}

		key := generateCacheKey(c)

		// Try to get cached response
		exists, err := redis.Exists(key)
		if err == nil && exists {
			cachedResponse, err := redis.Get(key)
			if err == nil {
				c.Set("X-Cache", "HIT")
				c.Set("Content-Type", "application/json")
				return c.Send(cachedResponse)
			}
		}

		if err := c.Next(); err != nil {
			return err
		}

		if c.Response().StatusCode() == fiber.StatusOK {
			redis.Set(key, c.Response().Body(), expiration)
		}

		c.Set("X-Cache", "MISS")
		return nil
	}
}

// generateCacheKey creates a unique hash for the request
func generateCacheKey(c *fiber.Ctx) string {
	path := c.Path()
	query := c.Request().URI().QueryString()
	data := path + string(query)

	hash := sha256.Sum256([]byte(data))
	return "cache:" + hex.EncodeToString(hash[:])
}
