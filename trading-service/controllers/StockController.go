package controllers

import (
	"banka1.com/db"
	"banka1.com/listings/finhub"
	"banka1.com/middlewares"
	"banka1.com/types"
	"github.com/gofiber/fiber/v2"
	"time"
)

type StockController struct {
}

func NewStockController() *StockController {
	return &StockController{}
}

// GetAllStocks godoc
//
//	@Summary		Preuzimanje svih akcija
//	@Description	Vraća listu svih listinga koji predstavljaju akcije.
//	@Tags			Stocks
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.Listing}	"Lista svih akcija"
//	@Failure		500	{object}	types.Response							"Interna greška servera pri preuzimanju akcija"
//	@Router			/stocks [get]
func (sc *StockController) GetAllStocks(c *fiber.Ctx) error {
	var listings []types.Listing

	if result := db.DB.Preload("Exchange").Where("type = ?", "Stock").Find(&listings); result.Error != nil {
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

// GetStockByTicker godoc
//
//	@Summary		Preuzimanje akcije po tikeru
//	@Description	Vraća osnovne podatke (Listing) i detalje (Stock) za akciju specificiranu tikerom.
//	@Tags			Stocks
//	@Produce		json
//	@Param			ticker	path		string																	true	"Tiker (simbol) akcije"	example(AAPL)
//	@Success		200		{object}	types.Response{data=object{listing=types.Listing,details=types.Stock}}	"Detalji akcije"
//	@Failure		404		{object}	types.Response															"Akcija sa datim tikerom nije pronađena"
//	@Failure		500		{object}	types.Response															"Interna greška servera pri preuzimanju detalja akcije"
//	@Router			/stocks/{ticker} [get]
func (sc *StockController) GetStockByTicker(c *fiber.Ctx) error {
	ticker := c.Params("ticker")

	var listing types.Listing
	if result := db.DB.Preload("Exchange").Where("ticker = ? AND type = ?", ticker, "Stock").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Stock not found with ticker: " + ticker,
		})
	}

	var stock types.Stock
	if result := db.DB.Preload("Listing.Exchange").Where("listing_id = ?", listing.ID).First(&stock); result.Error != nil {
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
			"details": stock,
		},
		Error: "",
	})
}

// GetStockFirstHistory godoc
//
//	@Summary		Prvi istorijski podatak za akciju
//	@Description	Preuzima najstariji dostupan dnevni istorijski podatak za akciju.
//	@Tags			Stocks
//	@Produce		json
//	@Param			ticker	path		string			true	"Tiker (simbol) akcije"	example(AAPL)
//	@Success		200		{object}	types.Response	"Najstariji istorijski podatak"
//	@Failure		404		{object}	types.Response	"Akcija sa datim tikerom nije pronađena"
//	@Failure		500		{object}	types.Response	"Greška pri preuzimanju istorijskih podataka"
//	@Router			/stocks/{ticker}/history/first [get]
func (sc *StockController) GetStockFirstHistory(c *fiber.Ctx) error {
	ticker := c.Params("ticker")

	// Find the listing first
	var listing types.Listing
	if result := db.DB.Where("ticker = ? AND type = ?", ticker, "Stock").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Stock not found with ticker: " + ticker,
		})
	}

	history, err := finhub.GetHistoricalPriceFirst(ticker, listing.Subtype)
	if err != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch historical price data: " + err.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    history,
		Error:   "",
	})
}

// GetStockHistoryByDate godoc
//
//	@Summary		Istorijski podatak za akciju za određeni datum
//	@Description	Preuzima dnevni istorijski podatak za akciju za određeni datum.
//	@Tags			Stocks
//	@Produce		json
//	@Param			ticker	path		string			true	"Tiker (simbol) akcije"			example(AAPL)
//	@Param			date	path		string			true	"Datum u formatu YYYY-MM-DD"	example(2023-10-26)	Format(date)
//	@Success		200		{object}	types.Response	"Istorijski podatak za dati datum"
//	@Failure		400		{object}	types.Response	"Neispravan format datuma"
//	@Failure		404		{object}	types.Response	"Akcija sa datim tikerom nije pronađena"
//	@Failure		500		{object}	types.Response	"Greška pri preuzimanju istorijskih podataka"
//	@Router			/stocks/{ticker}/history/{date} [get]
func (sc *StockController) GetStockHistoryByDate(c *fiber.Ctx) error {
	ticker := c.Params("ticker")
	date := c.Params("date")

	// Find the listing first
	var listing types.Listing
	if result := db.DB.Where("ticker = ? AND type = ?", ticker, "Stock").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Stock not found with ticker: " + ticker,
		})
	}

	// Parse date parameter
	dateTime, err := time.Parse("2006-01-02", date)
	if err != nil {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Invalid date format. Use YYYY-MM-DD",
		})
	}

	history, err := finhub.GetHistoricalPriceDate(ticker, listing.Subtype, dateTime)
	if err != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch historical price data: " + err.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    history,
		Error:   "",
	})
}

// GetStockHistoryRange godoc
//
//	@Summary		Istorijski podaci za akciju u vremenskom opsegu
//	@Description	Preuzima dnevne istorijske podatke za akciju u vremenskom opsegu.
//	@Tags			Stocks
//	@Produce		json
//	@Param			ticker		path		string												true	"Tiker (simbol) akcije"										example(AAPL)
//	@Param			startDate	query		string												false	"Početni datum (YYYY-MM-DD). Podrazumevano pre 30 dana."	example(2023-09-27)	Format(date)
//	@Param			endDate		query		string												false	"Krajnji datum (YYYY-MM-DD). Podrazumevano današnji dan."	example(2023-10-27)	Format(date)
//	@Success		200			{object}	types.Response{data=[]types.ListingDailyPriceInfo}	"Lista istorijskih podataka"
//	@Failure		400			{object}	types.Response										"Neispravan format datuma"
//	@Failure		404			{object}	types.Response										"Akcija sa datim tikerom nije pronađena"
//	@Failure		500			{object}	types.Response										"Greška pri preuzimanju istorijskih podataka iz baze"
//	@Router			/stocks/{ticker}/history [get]
func (sc *StockController) GetStockHistoryRange(c *fiber.Ctx) error {
	ticker := c.Params("ticker")
	// Parse date range parameters
	startDateStr := c.Query("startDate", "")
	endDateStr := c.Query("endDate", "")

	var listing types.Listing
	if result := db.DB.Where("ticker = ? AND type = ?", ticker, "Stock").First(&listing); result.Error != nil {
		return c.Status(404).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Stock not found with ticker: " + ticker,
		})
	}

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
		// Default to 30 days ago
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
		// Default to today
		endDate = time.Now()
	}

	// Fetch historical price data
	var history []types.ListingDailyPriceInfo
	if result := db.DB.Where("listing_id = ? AND date BETWEEN ? AND ?",
		listing.ID, startDate, endDate).Order("date").Find(&history); result.Error != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Data:    nil,
			Error:   "Failed to fetch historical price data: " + result.Error.Error(),
		})
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    history,
		Error:   "",
	})
}

func InitStockRoutes(app *fiber.App) {
	StockController := NewStockController()

	stockGroup := app.Group("/stocks", middlewares.CacheMiddleware(5*time.Minute))

	stockGroup.Get("/", StockController.GetAllStocks)
	stockGroup.Get("/:ticker", StockController.GetStockByTicker)
	stockGroup.Get("/:ticker/history/first", StockController.GetStockFirstHistory)
	stockGroup.Get("/:ticker/history/:date", StockController.GetStockHistoryByDate)
	stockGroup.Get("/:ticker/history", StockController.GetStockHistoryRange)
}
