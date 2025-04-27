package controllers

import (
	"log"
	"os"
	"time"

	"github.com/go-co-op/gocron"

	"banka1.com/broker"
	"banka1.com/db"
	"banka1.com/dto"
	"banka1.com/middlewares"
	"banka1.com/types"
	"github.com/gofiber/fiber/v2"
)

type TaxController struct {
}

func NewTaxController() *TaxController {
	return &TaxController{}
}

// GetTaxForAllUsers godoc
//
//	@Summary		Dohvatanje poslednjeg neplaćenog poreza za sve korisnike
//	@Description	Vraća listu najskorijih neplaćenih poreskih obaveza (za poslednji obračunati mesec/godinu) za sve korisnike. Za svakog korisnika proverava i da li je registrovan kao aktuar.
//	@Tags			Tax
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.TaxResponse}	"Lista poslednjih neplaćenih poreskih obaveza"
//	@Failure		400	{object}	types.Response								"Greška pri izvršavanju upita u bazi (kako je implementirano u kodu)"
//	@Failure		500	{object}	types.Response								"Greška pri čitanju rezultata iz baze"
//	@Router			/tax [get]
func (tc *TaxController) GetTaxForAllUsers(c *fiber.Ctx) error {
	/*
	rows, err := db.DB.Raw(`WITH max_created_at AS (SELECT user_id, MAX(created_at) AS c FROM tax GROUP BY user_id)
SELECT user_id, taxable_profit, tax_amount, is_paid, actuary.id IS NOT NULL
FROM tax LEFT JOIN actuary USING (user_id)
WHERE month_year = (SELECT MAX(month_year) FROM tax)
AND created_at = (SELECT c FROM max_created_at WHERE max_created_at.user_id = tax.user_id)
AND NOT is_paid;`).Rows()
	defer rows.Close()
	if err != nil {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Error:   "Neuspeo zahtev: " + err.Error(),
		})
	}*/

	responses := make([]types.TaxResponse, 0)
	var transactions []types.Transaction

	query := "SELECT * FROM transactions WHERE total_price > 0"

	err := db.DB.Raw(query).Scan(&transactions).Error

	if err != nil {
		log.Printf("Error fetching transactions: %v", err)
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Error fetching transactions: " + err.Error(),
		})
	}

	/*
	for rows.Next() {
		var response types.TaxResponse
		err := rows.Scan(&response.UserID, &response.TaxableProfit, &response.TaxAmount, &response.IsPaid, &response.IsActuary)
		if err != nil {
			return c.Status(500).JSON(types.Response{
				Success: false,
				Error:   "Greska prilikom citanja redova iz baze: " + err.Error(),
			})
		}
		responses = append(responses, response)
	}*/

	for _, transaction := range transactions {
		profit := transaction.TotalPrice
		tax := profit * 0.15

		var userId uint = 0
		if transaction.OrderID != 0 {
			// order
			var order types.Order
			if err := db.DB.First(&order, transaction.OrderID).Error; err != nil {
				continue
			}

			userId = order.UserID
		} else {
			// otc
			var contract types.OptionContract
			if err := db.DB.First(&contract, transaction.ContractID).Error; err != nil {
				continue
			}

			userId = *contract.SellerID
		}

		if userId == 0 {
			continue
		}

		var isActuary bool
		db.DB.Raw(`
			SELECT COUNT(*) > 0
			FROM actuary
			WHERE user_id = ?
		`, userId).Scan(&isActuary)

		response := types.TaxResponse{
			UserID:        userId,
			TaxableProfit: profit,
			TaxAmount:     tax,
			IsPaid:        transaction.TaxPaid,
			IsActuary:     isActuary,
		}

		responses = append(responses, response)
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    responses,
	})
}

// RunTax godoc
//
//	@Summary		Pokretanje obračuna poreza
//	@Description	Endpoint namenjen za pokretanje procesa obračuna poreza za korisnike. Trenutno je implementiran i nemam pojma sta vraca.
//	@Tags			Tax
//	@Produce		json
//	@Success		202	{object}	types.Response	"Zahtev za obračun poreza je primljen"
//	@Failure		500	{object}	types.Response	"Greska"
//	@Router			/tax/run [post]
func (tc *TaxController) RunTax(c *fiber.Ctx) error {
	now := time.Now()
	yearMonth := now.Format("2006-01")

	var transactions []types.Transaction

	query := "SELECT * FROM transactions WHERE total_price > 0"

	// koristimo orm jel tako kolege
	if os.Getenv("DB_TYPE") == "POSTGRES_DSN" {
		query += " AND TO_CHAR(created_at, 'YYYY-MM') = ?"
	} else {
		query += " AND substr(created_at, 1, 7) = ?"
	}

	query += " AND tax_paid = FALSE"

	err := db.DB.Raw(query, yearMonth).Scan(&transactions).Error

	if err != nil {
		log.Printf("Error fetching transactions: %v", err)
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Error fetching transactions: " + err.Error(),
		})
	}

	var hadError bool = false
	for _, transaction := range transactions {
		log.Printf("Porez na transakciju %d", transaction.ID)

		profit := transaction.TotalPrice
		tax := profit * 0.15

		var accountId int64 = -1
		if transaction.OrderID != 0 {
			// order
			var order types.Order
			if err := db.DB.First(&order, transaction.OrderID).Error; err != nil {
				hadError = true
				log.Printf("Greska pri fetch-u ordera %d: %v", transaction.OrderID, err)
				continue
			}

			accountId = int64(order.AccountID)
		} else {
			// otc
			var contract types.OptionContract
			if err := db.DB.First(&contract, transaction.ContractID).Error; err != nil {
				hadError = true
				log.Printf("Greska pri fetch-u option-a %d: %v", transaction.ContractID, err)
				continue
			}
			sellerAccounts, err := broker.GetAccountsForUser(int64(*contract.SellerID))
			if err != nil {
				hadError = true
				log.Printf("Greska pri fetch-u računa %v", err)
				continue
			}

			var sellerAccountID int64 = -1

			for _, acc := range sellerAccounts {
				if acc.CurrencyType == "USD" {
					sellerAccountID = acc.ID
					break
				}
			}

			if sellerAccountID == -1 {
				hadError = true
				log.Printf("Nije pronadjen USD račun")
				continue
			}

			accountId = sellerAccountID
		}

		if accountId == -1 {
			hadError = true
			continue
		}

		taxDto := dto.TaxCollectionDTO{
			AccountId: accountId,
			Amount:    tax,
		}

		err := broker.SendTaxCollection(&taxDto)
		if err != nil {
			hadError = true
			log.Printf("Greska pri slanju TaxCollectionDTO: %v", err)
			continue
		}

		if err != nil {
			hadError = true
			log.Printf("Error deducting tax for transaction %d: %v", transaction.ID, err)
			continue
		}

		err = db.DB.Exec(`
			UPDATE transactions
			SET tax_paid = TRUE
			WHERE id = ?
		`, transaction.ID).Error

		if err != nil {
			hadError = true
			log.Printf("Error updating transaction %d: %v", transaction.ID, err)
			continue
		}
	}

	additionalMessage := ""
	if hadError {
		additionalMessage = " Some accounts could not have taxes deducted from them properly. Please check the logs."
	}

	return c.Status(202).JSON(types.Response{
		Success: true,
		Data:    "Tax calculation and deduction completed successfully." + additionalMessage,
	})

}

// GetAggregatedTaxForUser godoc
//
//	@Summary		Dohvatanje agregiranih poreskih podataka za korisnika
//	@Description	Vraća sumu plaćenog poreza za tekuću godinu i sumu neplaćenog poreza za tekući mesec za specificiranog korisnika.
//	@Tags			Tax
//	@Produce		json
//	@Param			userID	path		int									true	"ID korisnika čiji se podaci traže"	example(123)
//	@Success		200		{object}	types.Response{data=types.AggregatedTaxResponse}	"Agregirani poreski podaci za korisnika"
//	@Failure		400		{object}	types.Response									"Neispravan ID korisnika (nije validan broj ili <= 0)"
//	@Failure		500		{object}	types.Response									"Interna greška servera pri dohvatanju podataka iz baze"
//	@Router			/tax/dashboard/{userID} [get]
func (tc *TaxController) GetAggregatedTaxForUser(c *fiber.Ctx) error {
	userID, err := c.ParamsInt("userID")
	if err != nil || userID <= 0 {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Error:   "Neispravan userID parametar",
		})
	}

	year := time.Now().Format("2006")
	yearMonth := time.Now().Format("2006-01")

	var yearTransactions []types.Transaction
	var monthTransactions []types.Transaction

	queryYearMonth := "SELECT * FROM transactions WHERE total_price > 0 AND tax_paid = FALSE"

	if os.Getenv("DB_TYPE") == "POSTGRES_DSN" {
		queryYearMonth += " AND TO_CHAR(created_at, 'YYYY-MM') = ?"
	} else {
		queryYearMonth += " AND substr(created_at, 1, 7) = ?"
	}

	var paid float64 = 0.0
	/*
	err = db.DB.Raw(`
		SELECT COALESCE(SUM(tax_amount), 0)
		FROM tax
		WHERE is_paid = TRUE AND user_id = ? AND substr(month_year, 1, 4) = ?
	`, userID, year).Scan(&paid).Error
	*/

	err = db.DB.Raw(queryYearMonth, yearMonth).Scan(&monthTransactions).Error

	if err != nil {
		log.Printf("OVDE PUCA!!!_-------------------------------------")
		log.Printf("Greška pri dohvatanju plaćenog poreza za user-a %d: %v", userID, err)
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Greška pri čitanju podataka iz baze",
		})
	}

	queryYear := "SELECT * FROM transactions WHERE total_price > 0 AND tax_paid = TRUE"

	if os.Getenv("DB_TYPE") == "POSTGRES_DSN" {
		queryYear += " AND TO_CHAR(created_at, 'YYYY') = ?"
	} else {
		queryYear += " AND substr(created_at, 1, 4) = ?"
	}

	var unpaid float64 = 0.0

	/*
	err = db.DB.Raw(`
		SELECT COALESCE(SUM(tax_amount), 0)
		FROM tax
		WHERE is_paid = FALSE AND user_id = ? AND month_year = ?
	`, userID, yearMonth).Scan(&unpaid).Error
	*/

	err = db.DB.Raw(queryYear, year).Scan(&yearTransactions).Error

	if err != nil {
		log.Printf("Greška pri dohvatanju neplaćenog poreza za user-a %d: %v", userID, err)
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Greška pri čitanju podataka iz baze",
		})
	}

	for _, transaction := range yearTransactions {
		profit := transaction.TotalPrice
		tax := profit * 0.15

		var userId uint = 0
		if transaction.OrderID != 0 {
			// order
			var order types.Order
			if err := db.DB.First(&order, transaction.OrderID).Error; err != nil {
				continue
			}

			userId = order.UserID
		} else {
			// otc
			var contract types.OptionContract
			if err := db.DB.First(&contract, transaction.ContractID).Error; err != nil {
				continue
			}

			userId = *contract.SellerID
		}

		if userId == 0 || userId != uint(userID) {
			continue
		}

		paid += tax
	}

	for _, transaction := range monthTransactions {
		profit := transaction.TotalPrice
		tax := profit * 0.15

		var userId uint = 0
		if transaction.OrderID != 0 {
			// order
			var order types.Order
			if err := db.DB.First(&order, transaction.OrderID).Error; err != nil {
				continue
			}

			userId = order.UserID
		} else {
			// otc
			var contract types.OptionContract
			if err := db.DB.First(&contract, transaction.ContractID).Error; err != nil {
				continue
			}

			userId = *contract.SellerID
		}

		if userId == 0 || userId != uint(userID) {
			continue
		}

		unpaid += tax
	}

	var isActuary bool
	db.DB.Raw(`
		SELECT COUNT(*) > 0
		FROM actuary
		WHERE user_id = ?
	`, userID).Scan(&isActuary)

	response := types.AggregatedTaxResponse{
		UserID:          uint(userID),
		PaidThisYear:    paid,
		UnpaidThisMonth: unpaid,
		IsActuary:       isActuary,
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    response,
	})
}

func RunTaxCronJob(taxController *TaxController) {
	scheduler := gocron.NewScheduler(time.UTC)
	_, err := scheduler.Every(1).Month(1).At("23:59").Do(func() {
		err := taxController.RunTax(nil) // Pass nil if no context is required
		if err != nil {
			log.Printf("Error running tax calculation: %v", err)
		} else {
			log.Println("Tax calculation completed successfully.")
		}
	})
	if err != nil {
		log.Fatalf("Failed to schedule RunTax: %v", err)
	}

	// Start the scheduler in a separate goroutine
	go scheduler.StartBlocking()
}

func InitTaxRoutes(app *fiber.App) {
	taxController := NewTaxController()

	app.Get("/tax", middlewares.Auth, middlewares.DepartmentCheck("SUPERVISOR"), taxController.GetTaxForAllUsers)
	app.Post("/tax/run", middlewares.Auth, middlewares.DepartmentCheck("SUPERVISOR"), taxController.RunTax)
	app.Get("/tax/dashboard/:userID", middlewares.Auth, taxController.GetAggregatedTaxForUser)

	// Start the cron job
	RunTaxCronJob(taxController)
}
