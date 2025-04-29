package middlewares

import (
	"fmt"
	"os"

	"github.com/gofiber/fiber/v2"
)

func Auth(c *fiber.Ctx) error {
	auth := c.Get("Authorization")
	if auth == "" {
		return c.Status(401).JSON(fiber.Map{
			"error":   "Unauthorized - No token provided",
			"success": false,
		})
	}

	// remove Bearer from token
	token := auth[7:]
	if token == "" {
		return c.Status(401).JSON(fiber.Map{
			"error":   "Unauthorized - No token provided",
			"success": false,
		})
	}

	_, claims, err := readToken(token)
	if err != nil {
		fmt.Println(err)
		return c.Status(401).JSON(fiber.Map{
			"error":   "Unauthorized - Invalid token",
			"success": false,
		})
	}

	c.Locals("token", token)

	c.Locals("claims", claims)
	c.Locals("user_id", claims["id"])
	c.Locals("position", claims["position"])
	c.Locals("department", claims["department"])
	c.Locals("permissions", claims["permissions"])
	c.Locals("is_admin", claims["isAdmin"])
	c.Locals("is_employed", claims["isEmployed"])
	return c.Next()
}

func DepartmentCheck(requiredDept string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		// Dohvatanje department vrednosti iz Locals
		department, ok := c.Locals("department").(string)
		if !ok || department != requiredDept {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"success": false,
				"error":   "Unauthorized: Invalid department",
			})
		}

		// Nastavi sa sledećim middleware-om ili handler-om
		return c.Next()
	}
}

func RequirePermission(requiredPermission string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		permissions, ok := c.Locals("permissions").([]interface{})
		if !ok {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"success": false,
				"error":   "Unauthorized: No permissions found",
			})
		}

		for _, perm := range permissions {
			if strPerm, ok := perm.(string); ok && strPerm == requiredPermission {
				return c.Next()
			}
		}

		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
			"success": false,
			"error":   fmt.Sprintf("Unauthorized: Missing permission '%s'", requiredPermission),
		})
	}
}

func RequireInterbankApiKey(c *fiber.Ctx) error {
	key := c.Get("X-Api-Key")
	expected := os.Getenv("BANK1_SECURITY")
	if key == "" || key != expected {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"success": false,
			"error":   "Unauthorized: invalid interbank API key",
		})
	}
	return c.Next()
}
