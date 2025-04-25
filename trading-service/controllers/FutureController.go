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

type FutureController struct {
}

func NewFutureController() *FutureController {
	return &FutureController{}
}

// GetAllFutures godoc
//
//	@Summary		Preuzimanje svih future-a
//	@Description	Vraća listu svih listinga koji predstavljaju future ugovore.
//	@Tags			Futures
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.Listing}	"Lista svih future-a"
//	@Failure		500	{object}	types.Response							"Interna greška servera pri preuzimanju future-a"
//	@Router			/future [get]
func (fc *FutureController) GetAllFutures(c *fiber.Ctx) error {
	var listings []types.Listing

	if result := db.DB.Preload("Exchange").Where("type = ?", "Future").Find(&listings); result.Error != nil {
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

// GetFutureByTicker godoc
//
//	@Summary		Preuzimanje future-a po tikeru
//	@Description	Vraća osnovne podatke (Listing) i detalje (FuturesContract) za future specificiran tikerom.
//	@Tags			Futures
//	@Produce		json
//	@Param			ticker	path		string																				true	"Tiker (simbol) future-a"	example(ESZ3)
//	@Success		200		{object}	types.Response{data=object{listing=types.Listing,details=types.FuturesContract}}	"Detalji future-a"
//	@Failure		404		{object}	types.Response																		"Future sa datim tikerom nije pronađen"
//	@Failure		500		{object}	types.Response																		"Interna greška servera pri preuzimanju detalja future-a"
//	@Router			/future/{ticker} [get]
func (fc *FutureController) GetFutureByTicker(c *fiber.Ctx) error {
	ticker := c.Params("ticker")

	var listing types.Listing
	if result := db.DB.Preload("Exchange").Where("ticker = ? AND type = ?", ticker, "Future").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Stock not found with ticker: " + ticker,
		})
	}

	var future types.FuturesContract
	if result := db.DB.Preload("Listing.Exchange").Where("listing_id = ?", listing.ID).First(&future); result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch future details: " + result.Error.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data: map[string]interface{}{
			"listing": listing,
			"details": future,
		},
		Error: "",
	})
}

// GetFutureHistoryByDate godoc
//
//	@Summary		Povrat snapshot-a za future po datumu
//	@Description	Preuzima snapshot (cenu, ask, bid...) za future ticker na tačno određeni dan.
//	@Tags			Futures
//	@Produce		json
//	@Param			ticker	path	string	true	"Tiker future-a"	example(ESZ3)
//	@Param			date	path	string	true	"Datum snapshot-a (YYYY-MM-DD)"	example(2025-04-09)	Format(date)
//	@Success		200		{object}	types.Response{data=types.ListingHistory}	"Snapshot podaci za traženi datum"
//	@Failure		400		{object}	types.Response	"Neispravan format datuma"
//	@Failure		404		{object}	types.Response	"Podaci nisu pronađeni za dati future ticker i datum"
//	@Failure		500		{object}	types.Response	"Greška baze"
//	@Router			/future/{ticker}/history/{date} [get]
func (fc *FutureController) GetFutureHistoryByDate(c *fiber.Ctx) error {
	ticker := c.Params("ticker")
	dateStr := c.Params("date")

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
				Error:   "Nema istorijskih podataka za " + ticker + " na datum " + dateStr,
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

// GetFutureHistoryRange godoc
//
//	@Summary		Istorijski podaci za future
//	@Description	Preuzima sve ili opsežne istorijske snapshot podatke za future ticker.
//	@Tags			Futures
//	@Produce		json
//	@Param			ticker		path	string	true	"Tiker future-a"							example(ESZ3)
//	@Param			startDate	query	string	false	"Početni datum (YYYY-MM-DD)"					example(2025-03-01)
//	@Param			endDate		query	string	false	"Krajnji datum (YYYY-MM-DD), podrazumevano danas"	example(2025-04-09)
//	@Success		200	{object}	types.Response{data=[]types.ListingHistory}	"Istorijski podaci za future"
//	@Failure		400	{object}	types.Response	"Neispravan format datuma"
//	@Failure		404	{object}	types.Response	"Nema istorijskih podataka"
//	@Failure		500	{object}	types.Response	"Greška baze"
//	@Router			/future/{ticker}/history [get]
func (fc *FutureController) GetFutureHistoryRange(c *fiber.Ctx) error {
	ticker := c.Params("ticker")
	startDateStr := c.Query("startDate")
	endDateStr := c.Query("endDate")

	var startDate time.Time
	var endDate time.Time
	var err error

	if startDateStr != "" {
		startDate, err = time.Parse("2006-01-02", startDateStr)
		if err != nil {
			return c.Status(400).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Neispravan format za startDate. Koristi YYYY-MM-DD",
			})
		}
	} else {
		startDate = time.Now().AddDate(0, 0, -30) // podrazumevano poslednjih 30 dana
	}

	if endDateStr != "" {
		endDate, err = time.Parse("2006-01-02", endDateStr)
		if err != nil {
			return c.Status(400).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Neispravan format za endDate. Koristi YYYY-MM-DD",
			})
		}
	} else {
		endDate = time.Now()
	}

	var history []types.ListingHistory
	result := db.DB.Where("ticker = ? AND snapshot_date BETWEEN ? AND ?", ticker, startDate, endDate).
		Order("snapshot_date").Find(&history)

	if result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Greška baze: " + result.Error.Error(),
		})
	}

	if len(history) == 0 {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Nema istorijskih podataka za " + ticker,
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    history,
		Error:   "",
	})
}

func InitFutureRoutes(app *fiber.App) {
	futureController := NewFutureController()

	futureGroup := app.Group("/future", middlewares.CacheMiddleware(5*time.Minute))

	futureGroup.Get("", futureController.GetAllFutures)
	futureGroup.Get("/:ticker", futureController.GetFutureByTicker)
	futureGroup.Get("/:ticker/history", futureController.GetFutureHistoryRange)
	futureGroup.Get("/:ticker/history/:date", futureController.GetFutureHistoryByDate)
}
