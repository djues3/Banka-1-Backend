package controllers

import (
	"banka1.com/middlewares"
	"errors"
	"time"

	"banka1.com/db"
	"banka1.com/types"
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

type ForexController struct {
}

func NewForexController() *ForexController {
	return &ForexController{}
}

// GetAllForex godoc
//
//	@Summary		Preuzimanje svih forex parova
//	@Description	Vraća listu svih listinga koji predstavljaju forex valutne parove.
//	@Tags			Forex
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.Listing}	"Lista svih forex parova"
//	@Failure		500	{object}	types.Response							"Interna greška servera pri preuzimanju forex parova"
//	@Router			/forex [get]
func (fc *ForexController) GetAllForex(c *fiber.Ctx) error {
	var listings []types.Listing

	if result := db.DB.Preload("Exchange").Where("type = ?", "Forex").Find(&listings); result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch stocks: " + result.Error.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    listings,
		Error:   "",
	})
}

// GetForexByPair godoc
//
//	@Summary		Preuzimanje forex para po valutama
//	@Description	Vraća osnovne podatke (Listing) i detalje (ForexPair) za forex par specificiran osnovnom i kvotnom valutom.
//	@Tags			Forex
//	@Produce		json
//	@Param			base	path		string																		true	"Osnovna valuta (ISO kod)"	example(EUR)
//	@Param			quote	path		string																		true	"Kvotna valuta (ISO kod)"	example(USD)
//	@Success		200		{object}	types.Response{data=object{listing=types.Listing,details=types.ForexPair}}	"Detalji forex para"
//	@Failure		404		{object}	types.Response																"Forex par nije pronađen"
//	@Failure		500		{object}	types.Response																"Interna greška servera pri preuzimanju detalja forex para"
//	@Router			/forex/{base}/{quote} [get]
func (fc *ForexController) GetForexByPair(c *fiber.Ctx) error {

	base := c.Params("base")
	quote := c.Params("quote")
	ticker := base + "/" + quote

	var listing types.Listing
	if result := db.DB.Preload("Exchange").Where("ticker = ? AND type = ?", ticker, "Forex").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Forex pair not found with ticker: " + ticker,
		})
	}

	var forexPair types.ForexPair
	if result := db.DB.Preload("Listing.Exchange").Where("listing_id = ?", listing.ID).First(&forexPair); result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch stock details: " + result.Error.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data: map[string]interface{}{
			"listing": listing,
			"details": forexPair,
		},
		Error: "",
	})
}

// GetForexHistoryRange godoc
//
//	@Summary		Istorijski podaci za forex par u vremenskom opsegu
//	@Description	Preuzima dnevne istorijske podatke za forex par u vremenskom opsegu.
//	@Tags			Forex
//	@Produce		json
//	@Param			base		path		string												true	"Osnovna valuta"											example(EUR)
//	@Param			quote		path		string												true	"Kvotna valuta"												example(USD)
//	@Param			startDate	query		string												false	"Početni datum (YYYY-MM-DD). Podrazumevano pre 30 dana."	example(2023-09-27)	Format(date)
//	@Param			endDate		query		string												false	"Krajnji datum (YYYY-MM-DD). Podrazumevano današnji dan."	example(2023-10-27)	Format(date)
//	@Success		200			{object}	types.Response{data=[]types.ListingDailyPriceInfo}	"Lista istorijskih podataka"
//	@Failure		400			{object}	types.Response										"Neispravan format datuma"
//	@Failure		404			{object}	types.Response										"Forex par nije pronađen"
//	@Failure		500			{object}	types.Response										"Greška pri preuzimanju istorijskih podataka iz baze"
//	@Router			/forex/{base}/{quote}/history [get]
func (fc *ForexController) GetForexHistoryRange(c *fiber.Ctx) error {
	base := c.Params("base")
	quote := c.Params("quote")
	ticker := base + "/" + quote

	startDateStr := c.Query("startDate", "")
	endDateStr := c.Query("endDate", "")

	var startDate, endDate time.Time
	var err error

	if startDateStr != "" {
		startDate, err = time.Parse("2006-01-02", startDateStr)
		if err != nil {
			return c.Status(400).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Invalid startDate format. Use YYYY-MM-DD",
			})
		}
	} else {
		startDate = time.Now().AddDate(0, 0, -30)
	}

	if endDateStr != "" {
		endDate, err = time.Parse("2006-01-02", endDateStr)
		if err != nil {
			return c.Status(400).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Invalid endDate format. Use YYYY-MM-DD",
			})
		}
	} else {
		endDate = time.Now()
	}

	// Fetch from ListingHistory by ticker
	var history []types.ListingHistory
	if result := db.DB.Where("ticker = ? AND snapshot_date BETWEEN ? AND ?", ticker, startDate, endDate).
		Order("snapshot_date").Find(&history); result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch historical price data: " + result.Error.Error(),
		})
	}

	if len(history) == 0 {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "No historical data found for ticker: " + ticker,
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    history,
		Error:   "",
	})
}

// GetForexHistoryByDate godoc
//
//	@Summary		Povrat dnevnog snapshot-a za forex par
//	@Description	Preuzima snapshot forex para (cena, ask, bid) za tačno određeni dan.
//	@Tags			Forex
//	@Produce		json
//	@Param			base	path	string	true	"Osnovna valuta"	example(EUR)
//	@Param			quote	path	string	true	"Kvotna valuta"	example(USD)
//	@Param			date	path	string	true	"Datum za koji se traži snapshot (YYYY-MM-DD)"	example(2025-04-09)	Format(date)
//	@Success		200		{object}	types.Response{data=types.ListingHistory}	"Snapshot za taj dan"
//	@Failure		400		{object}	types.Response	"Neispravan format datuma"
//	@Failure		404		{object}	types.Response	"Forex par za traženi datum nije pronađen"
//	@Failure		500		{object}	types.Response	"Greška pri preuzimanju podataka iz baze"
//	@Router			/forex/{base}/{quote}/history/{date} [get]
func (fc *ForexController) GetForexHistoryByDate(c *fiber.Ctx) error {
	base := c.Params("base")
	quote := c.Params("quote")
	dateStr := c.Params("date")
	ticker := base + "/" + quote

	if dateStr == "" {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Nedostaje parametar datuma (format: YYYY-MM-DD)",
		})
	}

	targetDate, err := time.Parse("2006-01-02", dateStr)
	if err != nil {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Neispravan format datuma. Koristi YYYY-MM-DD",
		})
	}
	targetDate = targetDate.Truncate(24 * time.Hour)

	var entry types.ListingHistory
	result := db.DB.Where("ticker = ? AND snapshot_date = ?", ticker, targetDate).First(&entry)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return c.Status(404).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Podaci nisu pronađeni za: " + ticker + " na datum " + dateStr,
			})
		}
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Greška baze: " + result.Error.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    entry,
		Error:   "",
	})
}

func InitForexRoutes(app *fiber.App) {
	forexController := NewForexController()

	forexGroup := app.Group("/forex", middlewares.CacheMiddleware(5*time.Minute))

	forexGroup.Get("", forexController.GetAllForex)
	forexGroup.Get("/:base/:quote", forexController.GetForexByPair)
	forexGroup.Get("/:base/:quote/history", forexController.GetForexHistoryRange)
	forexGroup.Get("/:base/:quote/history/:date", forexController.GetForexHistoryByDate)
}
