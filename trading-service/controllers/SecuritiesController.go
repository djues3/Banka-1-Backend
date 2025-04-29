package controllers

import (
	"banka1.com/db"
	"banka1.com/dto"
	"banka1.com/types"
	"github.com/gofiber/fiber/v2"
	"log"
	"time"
)

type SecuritiesController struct {
}

func NewSecuritiesController() *SecuritiesController {
	return &SecuritiesController{}
}

// GetAllSecurities godoc
//
//	@Summary		Preuzimanje svih hartija od vrednosti (Alias)
//	@Description	Vraća listu svih dostupnih hartija od vrednosti.
//	@Tags			Securities
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.Security}	"Lista svih hartija od vrednosti"
//	@Failure		500	{object}	types.Response							"Interna greška servera pri preuzimanju ili konverziji hartija od vrednosti"
//	@Router			/securities [get]
func (sc *SecuritiesController) getSecurities() func(c *fiber.Ctx) error {
	return func(c *fiber.Ctx) error {
		var listings []types.Listing
		if result := db.DB.Preload("Exchange").Find(&listings); result.Error != nil {
			return c.Status(500).JSON(types.Response{
				Success: false,
				Data:    nil,
				Error:   "Failed to fetch securities: " + result.Error.Error(),
			})
		}
		var securities []types.Security
		for _, listing := range listings {
			security, err := listingToSecurity(&listing)
			if err != nil || security == nil {
				return c.Status(500).JSON(types.Response{
					Success: false,
					Data:    nil,
					Error:   "Failed to convert listing to security: " + err.Error(),
				})
			}
			securities = append(securities, *security)
		}

		return c.JSON(types.Response{
			Success: true,
			Data:    securities,
			Error:   "",
		})
	}
}

// GetUserSecurities godoc
//
//	@Summary		Dohvatanje svih hartija od vrednosti za korisnika
//	@Description	Vraća listu svih hartija od vrednosti koje poseduje korisnik sa specificiranim ID-jem, zajedno sa količinom, kupovnom cenom i trenutnim profitom/gubitkom za svaku.
//	@Tags			Securities
//	@Produce		json
//	@Param			id	path		string											true	"ID korisnika čije hartije od vrednosti treba dohvatiti"	example(123)
//	@Success		200	{object}	types.Response{data=[]dto.PortfolioSecurityDTO}	"Lista hartija od vrednosti u vlasništvu korisnika"
//	@Failure		400	{object}	types.Response									"ID korisnika nije prosleđen u putanji"
//	@Failure		500	{object}	types.Response									"Greška pri dohvatanju podataka iz baze"
//	@Router			/securities/{id} [get]
func (sc *SecuritiesController) GetUserSecurities(c *fiber.Ctx) error {
	userId := c.Params("id")
	if userId == "" {
		return c.Status(400).JSON(types.Response{
			Success: false,
			Error:   "Nedostaje user ID",
		})
	}

	var portfolios []types.Portfolio
	if err := db.DB.Preload("Security").Where("user_id = ?", userId).Find(&portfolios).Error; err != nil {
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Greška pri dohvatanju portfolija: " + err.Error(),
		})
	}

	var response []dto.PortfolioSecurityDTO
	for _, p := range portfolios {
		profit := (p.Security.LastPrice - p.PurchasePrice) * float64(p.Quantity)

		var lastMod int64
		err := db.DB.
			Model(&types.Order{}).
			Select("MAX(last_modified)").
			Where("user_id = ? AND security_id = ?", p.UserID, p.SecurityID).
			Scan(&lastMod).Error
		if err != nil || lastMod == 0 {
			lastMod = p.CreatedAt // fallback
		}

		item := dto.PortfolioSecurityDTO{
			PortfolioID:  p.ID,
			SecurityID:   p.SecurityID,
			Ticker:       p.Security.Ticker,
			Type:         p.Security.Type,
			Symbol:       p.Security.Ticker,
			Amount:       p.Quantity,
			Price:        p.PurchasePrice,
			Profit:       profit,
			LastModified: lastMod,
			Public:       p.PublicCount,
		}
		response = append(response, item)
	}

	return c.JSON(types.Response{
		Success: true,
		Data:    response,
		Error:   "",
	})
}

// /security/securityId/volume

// GetAvailableSecurities godoc
//
//	@Summary		Dohvatanje svih dostupnih hartija
//	@Description	Vraća listu svih hartija od vrednosti koje su trenutno dostupne na tržištu.
//	@Tags			Securities
//	@Produce		json
//	@Success		200	{object}	types.Response{data=[]types.Security}	"Lista dostupnih hartija od vrednosti"
//	@Failure		500	{object}	types.Response							"Greška pri dohvatanju podataka iz baze"
//	@Router			/securities/available [get]
func (sc *SecuritiesController) GetAvailableSecurities(c *fiber.Ctx) error {
	var securities []types.Security

	if err := db.DB.Find(&securities).Error; err != nil {
		log.Printf("[ERROR] Failed to fetch available securities: %v\n", err)
		return c.Status(500).JSON(types.Response{
			Success: false,
			Error:   "Failed to fetch available securities.",
		})
	}

	log.Printf("[INFO] Fetched %d available securities.\n", len(securities))
	return c.JSON(types.Response{
		Success: true,
		Data:    securities,
	})
}

func listingToSecurity(l *types.Listing) (*types.Security, error) {
	var security types.Security
	previousClose := getPreviousCloseForListing(l.ID)

	// Dohvati volume iz baze za dati listing ID
	var dbSec types.Security
	if err := db.DB.Select("volume").Where("id = ?", l.ID).First(&dbSec).Error; err != nil {
		// Ako nije pronađen, podrazumevano stavi na 0
		dbSec.Volume = 0
	}

	switch l.Type {
	case "Stock":
		{
			security = types.Security{
				ID:            l.ID,
				Ticker:        l.Ticker,
				Name:          l.Name,
				Type:          l.Type,
				Exchange:      l.Exchange.Name,
				LastPrice:     float64(l.Price),
				AskPrice:      float64(l.Ask),
				BidPrice:      float64(l.Bid),
				Volume:        dbSec.Volume,
				ContractSize:  int64(l.ContractSize),
				PreviousClose: previousClose,
			}
		}
	case "Forex":
		{
			security = types.Security{
				ID:            l.ID,
				Ticker:        l.Ticker,
				Name:          l.Name,
				Type:          l.Type,
				Exchange:      l.Exchange.Name,
				LastPrice:     float64(l.Price),
				AskPrice:      float64(l.Ask),
				BidPrice:      float64(l.Bid),
				Volume:        dbSec.Volume,
				ContractSize:  int64(l.ContractSize),
				PreviousClose: previousClose,
			}
		}
	case "Future":
		{
			var future types.FuturesContract
			if result := db.DB.Where("listing_id = ?", l.ID).First(&future); result.Error != nil {
				return nil, result.Error
			}
			settlementDate := future.SettlementDate.Format("2006-01-02")
			security = types.Security{
				ID:             l.ID,
				Ticker:         l.Ticker,
				Name:           l.Name,
				Type:           l.Type,
				Exchange:       l.Exchange.Name,
				LastPrice:      float64(l.Price),
				AskPrice:       float64(l.Ask),
				BidPrice:       float64(l.Bid),
				Volume:         dbSec.Volume,
				SettlementDate: &settlementDate,
				ContractSize:   int64(l.ContractSize),
				PreviousClose:  previousClose,
			}
		}
	case "Option":
		{
			var option types.Option
			if result := db.DB.Where("listing_id = ?", l.ID).First(&option); result.Error != nil {
				return nil, result.Error
			}
			settlementDate := option.SettlementDate.Format("2006-01-02")
			security = types.Security{
				ID:             l.ID,
				Ticker:         l.Ticker,
				Name:           l.Name,
				Type:           l.Type,
				Exchange:       l.Exchange.Name,
				LastPrice:      float64(l.Price),
				AskPrice:       float64(l.Ask),
				BidPrice:       float64(l.Bid),
				Volume:         dbSec.Volume,
				StrikePrice:    &option.StrikePrice,
				OptionType:     &option.OptionType,
				SettlementDate: &settlementDate,
				ContractSize:   int64(l.ContractSize),
				PreviousClose:  previousClose,
			}

		}

	}
	return &security, nil
}

func getPreviousCloseForListing(listingID uint) float64 {
	var dailyInfo types.ListingDailyPriceInfo

	yesterday := time.Now().AddDate(0, 0, -1)

	err := db.DB.
		Where("listing_id = ? AND DATE(date) = ?", listingID, yesterday.Format("2006-01-02")).
		Order("date DESC").
		First(&dailyInfo).Error

	if err == nil {
		return dailyInfo.Price
	}

	err = db.DB.
		Where("listing_id = ?", listingID).
		Order("date DESC").
		First(&dailyInfo).Error

	if err == nil {
		return dailyInfo.Price
	}

	return 0
}

func InitSecuritiesRoutes(app *fiber.App) {
	securitiesController := NewSecuritiesController()

	app.Get("/securities", securitiesController.getSecurities())
	app.Get("/securities/available", securitiesController.GetAvailableSecurities)
	app.Get("/securities/:id", securitiesController.GetUserSecurities)
}
