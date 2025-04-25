package redis

import (
	"context"
	"encoding/json"
	"time"

	"github.com/go-redis/redis/v8"
)

var (
	Client *redis.Client
	ctx    = context.Background()
)

// Config contains Redis configuration options
type Config struct {
	Addr     string
	Password string
	DB       int
	PoolSize int
}

// Init initializes the Redis client
func Init(config Config) error {
	Client = redis.NewClient(&redis.Options{
		Addr:     config.Addr,
		Password: config.Password,
		DB:       config.DB,
		PoolSize: config.PoolSize,
	})

	// Verify connection
	if err := Client.Ping(ctx).Err(); err != nil {
		return err
	}

	return nil
}

// Close closes the Redis client connection
func Close() error {
	return Client.Close()
}

// Set stores a value with the given key and expiration
func Set(key string, value interface{}, expiration time.Duration) error {
	if Client == nil {
		return nil
	}

	// Convert complex data types to JSON
	var dataToStore []byte
	var err error

	switch v := value.(type) {
	case []byte:
		dataToStore = v
	case string:
		dataToStore = []byte(v)
	default:
		// Marshal other types to JSON
		dataToStore, err = json.Marshal(value)
		if err != nil {
			return err
		}
	}

	return Client.Set(ctx, key, dataToStore, expiration).Err()
}

// Get retrieves a value by key
func Get(key string) ([]byte, error) {
	if Client == nil {
		return nil, nil
	}

	val, err := Client.Get(ctx, key).Bytes()
	if err != nil {
		return nil, err
	}
	return val, nil
}

// GetJSON retrieves a JSON value by key and unmarshals it
func GetJSON(key string, dest interface{}) error {
	if Client == nil {
		return nil
	}

	val, err := Get(key)
	if err != nil {
		return err
	}

	return json.Unmarshal(val, dest)
}

// Delete removes a key
func Delete(key string) error {
	if Client == nil {
		return nil
	}
	return Client.Del(ctx, key).Err()
}

// Exists checks if a key exists
func Exists(key string) (bool, error) {
	if Client == nil {
		return false, nil
	}

	val, err := Client.Exists(ctx, key).Result()
	if err != nil {
		return false, err
	}
	return val > 0, nil
}
