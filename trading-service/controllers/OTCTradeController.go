package controllers

import (
	"banka1.com/broker"
	"banka1.com/db"
	"banka1.com/dto"
	"banka1.com/middlewares"
	"banka1.com/saga"
	"banka1.com/types"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/go-playground/validator/v10"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/log"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type UpdateOTCTradeRequest struct {
	Quantity       int     `json:"quantity" validate:"required,gt=0"`
	PricePerUnit   float64 `json:"price_per_unit" validate:"required,gt=0"`
	Premium        float64 `json:"premium" validate:"required,gte=0"`
	SettlementDate string  `json:"settlement_date" validate:"required"`
}

type OTCTradeController struct {
	validator *validator.Validate
}

func NewOTCTradeController() *OTCTradeController {
	return &OTCTradeController{
		validator: validator.New(),
	}
}

type CreateOTCTradeRequest struct {
	OwnerID        string  `json:"ownerId" validate:"required"`
	PortfolioID    *uint   `json:"portfolioId,omitempty"`
	Ticker         *string `json:"ticker,omitempty"`
	Quantity       int     `json:"quantity"     validate:"required,gt=0"`
	PricePerUnit   float64 `json:"pricePerUnit" validate:"required,gt=0"`
	Premium        float64 `json:"premium"      validate:"required,gte=0"`
	SettlementDate string  `json:"settlementDate" validate:"required"`
}

func (c *OTCTradeController) CreateOTCTrade(ctx *fiber.Ctx) error {
	var req CreateOTCTradeRequest
	if err := ctx.BodyParser(&req); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{false, "", "Nevalidan JSON format"})
	}
	if err := c.validator.Struct(req); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{false, "", err.Error()})
	}
	settlementDate, err := time.Parse("2006-01-02", req.SettlementDate)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{false, "", "Nevalidan format datuma, očekivano YYYY-MM-DD"})
	}
	localUserID := uint(ctx.Locals("user_id").(float64))
	localUserIDStr := strconv.FormatUint(uint64(localUserID), 10)

	if req.PortfolioID != nil {
		var portfolio types.Portfolio
		if err := db.DB.Preload("Security").First(&portfolio, *req.PortfolioID).Error; err != nil {
			return ctx.Status(404).JSON(types.Response{false, "", "Portfolio nije pronađen"})
		}
		if portfolio.UserID == localUserID {
			return ctx.Status(403).JSON(types.Response{false, "", "Ne možete praviti ponudu za svoje akcije"})
		}
		if portfolio.PublicCount < req.Quantity {
			return ctx.Status(400).JSON(types.Response{false, "", "Nedovoljno javno dostupnih akcija"})
		}
		trade := types.OTCTrade{
			PortfolioID:   req.PortfolioID,
			SecurityID:    &portfolio.SecurityID,
			LocalSellerID: &portfolio.UserID,
			LocalBuyerID:  &localUserID,
			Ticker:        portfolio.Security.Ticker,
			Quantity:      req.Quantity,
			PricePerUnit:  req.PricePerUnit,
			Premium:       req.Premium,
			SettlementAt:  settlementDate,
			ModifiedBy:    localUserIDStr,
			Status:        "pending",
		}
		if err := db.DB.Create(&trade).Error; err != nil {
			return ctx.Status(500).JSON(types.Response{false, "", "Greška pri čuvanju ponude"})
		}
		return ctx.Status(201).JSON(types.Response{true, fmt.Sprintf("Interna ponuda kreirana: %d", trade.ID), ""})
	}

	if req.Ticker == nil {
		return ctx.Status(400).JSON(types.Response{false, "", "Ticker je obavezan za međubankarsku ponudu"})
	}
	prefix := req.OwnerID[:3]
	foreignID := req.OwnerID[3:]
	routingNum, err := strconv.Atoi(prefix)
	if err != nil {
		return ctx.Status(400).JSON(types.Response{false, "", "Neispravan ownerId format"})
	}

	ibReq := CreateInterbankOTCOfferRequest{
		Ticker:         *req.Ticker,
		Quantity:       req.Quantity,
		PricePerUnit:   req.PricePerUnit,
		Premium:        req.Premium,
		SettlementDate: req.SettlementDate,
		SellerRouting:  routingNum,
		SellerID:       foreignID,
	}

	if err := c.validator.Struct(ibReq); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{false, "", err.Error()})
	}

	const myRouting = 111
	offerDTO := dto.InterbankOtcOfferDTO{
		Stock:          dto.StockDescription{Ticker: ibReq.Ticker},
		SettlementDate: settlementDate.Format(time.RFC3339),
		PricePerUnit:   dto.MonetaryValue{Currency: "USD", Amount: ibReq.PricePerUnit},
		Premium:        dto.MonetaryValue{Currency: "USD", Amount: ibReq.Premium},
		Amount:         ibReq.Quantity,
		BuyerID:        dto.ForeignBankId{RoutingNumber: myRouting, ID: localUserIDStr},
		SellerID:       dto.ForeignBankId{RoutingNumber: ibReq.SellerRouting, ID: ibReq.SellerID},
		LastModifiedBy: dto.ForeignBankId{RoutingNumber: myRouting, ID: localUserIDStr},
	}

	url := fmt.Sprintf("%s/negotiations", os.Getenv("BANK4_BASE_URL"))
	bodyBytes, err := json.Marshal(offerDTO)
	if err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri serializaciji zahteva"})
	}

	bodyReader := bytes.NewReader(bodyBytes)

	httpReq, err := http.NewRequest("POST", url, bodyReader)
	if err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri kreiranju HTTP zahteva"})
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Api-Key", os.Getenv("BANK4_API_KEY"))

	resp, err := http.DefaultClient.Do(httpReq)
	if err != nil {
		return ctx.Status(502).JSON(types.Response{false, "", "Greška pri komunikaciji sa Bankom 4"})
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		return ctx.Status(resp.StatusCode).JSON(types.Response{false, fmt.Sprintf("Bank4: %s", string(body)), ""})
	}

	var fbid dto.ForeignBankId
	if err := json.NewDecoder(resp.Body).Decode(&fbid); err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Neuspešno parsiranje odgovora Banke 4"})
	}

	//modifiedBy := fmt.Sprintf("%d%s", myRouting, localUserIDStr)
	seller := fmt.Sprintf("%d%s", 444, ibReq.SellerID)
	buyer := fmt.Sprintf("%d%s", 111, localUserIDStr)
	var sec types.Security
	if err := db.DB.Where("ticker = ?", ibReq.Ticker).First(&sec).Error; err != nil {
		return ctx.Status(404).JSON(types.Response{false, "", "Ticker nije pronađen"})
	}
	trade := types.OTCTrade{
		RemoteRoutingNumber: &fbid.RoutingNumber,
		RemoteNegotiationID: &fbid.ID,
		RemoteSellerID:      &seller,
		RemoteBuyerID:       &buyer,
		Ticker:              ibReq.Ticker,
		SecurityID:          &sec.ID,
		Quantity:            ibReq.Quantity,
		PricePerUnit:        ibReq.PricePerUnit,
		Premium:             ibReq.Premium,
		SettlementAt:        settlementDate,
		ModifiedBy:          localUserIDStr,
		Status:              "pending",
	}
	if err := db.DB.Create(&trade).Error; err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri čuvanju međubankarske ponude"})
	}

	return ctx.Status(201).JSON(types.Response{
		Success: true,
		Data:    fmt.Sprintf("Interbank ponuda kreirana, negoID=%s", fbid.ID),
	})
}

func (c *OTCTradeController) CounterOfferOTCTrade(ctx *fiber.Ctx) error {
	id := ctx.Params("id")
	var req UpdateOTCTradeRequest
	if err := ctx.BodyParser(&req); err != nil {
		return ctx.Status(fiber.StatusBadRequest).
			JSON(types.Response{Success: false, Data: "", Error: "Nevalidan JSON format"})
	}
	if err := c.validator.Struct(req); err != nil {
		return ctx.Status(fiber.StatusBadRequest).
			JSON(types.Response{Success: false, Data: "", Error: err.Error()})
	}

	settlementDate, err := time.Parse("2006-01-02", req.SettlementDate)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).
			JSON(types.Response{Success: false, Data: "", Error: "Nevalidan format datuma. Očekivano YYYY-MM-DD"})
	}

	localUserID := uint(ctx.Locals("user_id").(float64))
	localUserIDStr := strconv.FormatUint(uint64(localUserID), 10)

	var trade types.OTCTrade
	if err := db.DB.Preload("Portfolio").First(&trade, id).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).
			JSON(types.Response{Success: false, Data: "", Error: "Ponuda nije pronađena"})
	}

	if trade.ModifiedBy == localUserIDStr {
		return ctx.Status(fiber.StatusForbidden).
			JSON(types.Response{Success: false, Data: "", Error: "Ne možete uzastopno menjati ponudu"})
	}

	if trade.RemoteNegotiationID == nil {
		if trade.PortfolioID == nil {
			return ctx.Status(fiber.StatusBadRequest).
				JSON(types.Response{Success: false, Data: "", Error: "Interna greška: nema PortfolioID"})
		}
		var portfolio types.Portfolio
		if err := db.DB.First(&portfolio, *trade.PortfolioID).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).
				JSON(types.Response{Success: false, Data: "", Error: "Greška pri proveri portfolija"})
		}
		if portfolio.PublicCount < req.Quantity {
			return ctx.Status(fiber.StatusBadRequest).
				JSON(types.Response{Success: false, Data: "", Error: "Nedovoljno javno dostupnih akcija"})
		}
		trade.Quantity = req.Quantity
		trade.PricePerUnit = req.PricePerUnit
		trade.Premium = req.Premium
		trade.SettlementAt = settlementDate
		trade.LastModified = time.Now().Unix()
		trade.ModifiedBy = localUserIDStr
		trade.Status = "pending"

		if err := db.DB.Save(&trade).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).
				JSON(types.Response{Success: false, Data: "", Error: "Greška prilikom čuvanja kontraponude"})
		}
		return ctx.Status(fiber.StatusOK).
			JSON(types.Response{Success: true, Data: fmt.Sprintf("Kontraponuda uspešno poslata (interna): %d", trade.ID), Error: ""})
	}

	const myRouting = 111
	const theirRouting = 444

	var buyerFB, sellerFB dto.ForeignBankId

	extractSuffix := func(full string, routing int) string {
		prefix := fmt.Sprint(routing)
		if strings.HasPrefix(full, prefix) {
			return full[len(prefix):]
		}
		return full
	}
	if trade.RemoteBuyerID != nil && strings.HasPrefix(*trade.RemoteBuyerID, fmt.Sprint(myRouting)) {
		localSuffix := (*trade.RemoteBuyerID)[len(fmt.Sprint(myRouting)):]
		buyerFB = dto.ForeignBankId{
			RoutingNumber: myRouting,
			ID:            localSuffix,
		}

		rawSeller := *trade.RemoteSellerID
		sellerSuffix := extractSuffix(rawSeller, theirRouting)
		sellerFB = dto.ForeignBankId{
			RoutingNumber: theirRouting,
			ID:            sellerSuffix,
		}

	} else if trade.RemoteSellerID != nil && strings.HasPrefix(*trade.RemoteSellerID, fmt.Sprint(myRouting)) {
		localSuffix := (*trade.RemoteSellerID)[len(fmt.Sprint(myRouting)):]
		sellerFB = dto.ForeignBankId{
			RoutingNumber: myRouting,
			ID:            localSuffix,
		}

		rawBuyer := *trade.RemoteBuyerID
		buyerSuffix := extractSuffix(rawBuyer, theirRouting)
		buyerFB = dto.ForeignBankId{
			RoutingNumber: theirRouting,
			ID:            buyerSuffix,
		}

	} else {
		return ctx.Status(fiber.StatusForbidden).
			JSON(types.Response{Success: false, Data: "", Error: "Niste učesnik ove međubankarske ponude"})
	}

	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(500).
			JSON(types.Response{Success: false, Data: "", Error: "Greška pri čuvanju međubankarske kontraponude"})
	}

	offer := dto.InterbankOtcOfferDTO{
		Stock:          dto.StockDescription{Ticker: trade.Ticker},
		SettlementDate: settlementDate.Format(time.RFC3339),
		PricePerUnit:   dto.MonetaryValue{Currency: "USD", Amount: req.PricePerUnit},
		Premium:        dto.MonetaryValue{Currency: "USD", Amount: req.Premium},
		Amount:         req.Quantity,
		BuyerID:        buyerFB,
		SellerID:       sellerFB,
		LastModifiedBy: dto.ForeignBankId{RoutingNumber: myRouting, ID: localUserIDStr},
	}

	body, err := json.Marshal(offer)
	if err != nil {
		return ctx.Status(500).
			JSON(types.Response{Success: false, Data: "", Error: "Greška pri serializaciji zahteva"})
	}
	url := fmt.Sprintf("%s/negotiations/%d/%s",
		os.Getenv("BANK4_BASE_URL"),
		*trade.RemoteRoutingNumber,
		*trade.RemoteNegotiationID,
	)
	httpReq, _ := http.NewRequest("PUT", url, bytes.NewReader(body))
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Api-Key", os.Getenv("BANK4_API_KEY"))

	resp, err := http.DefaultClient.Do(httpReq)
	if err != nil {
		return ctx.Status(502).
			JSON(types.Response{Success: false, Data: "", Error: "Greška pri komunikaciji sa Bankom 4"})
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusConflict {
		return ctx.Status(409).
			JSON(types.Response{Success: false, Data: "", Error: "Nije vaš red za kontra‑ponudu."})
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		respBody, _ := io.ReadAll(resp.Body)
		return ctx.Status(resp.StatusCode).
			JSON(types.Response{Success: false, Data: "", Error: fmt.Sprintf("Bank4: %s", string(respBody))})
	}

	trade.Quantity = req.Quantity
	trade.PricePerUnit = req.PricePerUnit
	trade.Premium = req.Premium
	trade.SettlementAt = settlementDate
	trade.LastModified = time.Now().Unix()
	trade.ModifiedBy = localUserIDStr
	trade.Status = "pending"

	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(500).
			JSON(types.Response{Success: false, Data: "", Error: "Greška pri čuvanju međubankarske kontraponude"})
	}

	return ctx.Status(fiber.StatusOK).
		JSON(types.Response{Success: true, Data: fmt.Sprintf("Interbank kontraponuda poslata za međubankarski negotioationID: %d", trade.RemoteNegotiationID), Error: ""})
}

func parseUintPtr(s *string) (uint, error) {
	if s == nil {
		return 0, errors.New("empty")
	}
	v, err := strconv.ParseUint(*s, 10, 64)
	return uint(v), err
}

func parseSellerID(t *types.OTCTrade) (uint, error) {
	if t.LocalSellerID != nil {
		return *t.LocalSellerID, nil
	}
	return parseUintPtr(t.RemoteSellerID)
}

func parseBuyerID(t *types.OTCTrade) (uint, error) {
	if t.LocalBuyerID != nil {
		return *t.LocalBuyerID, nil
	}
	return parseUintPtr(t.RemoteBuyerID)
}

func (c *OTCTradeController) AcceptOTCTrade(ctx *fiber.Ctx) error {
	const ourRouting = 111
	const theirRouting = 444

	id := ctx.Params("id")
	userID := uint(ctx.Locals("user_id").(float64))

	var trade types.OTCTrade
	if err := db.DB.Preload("Portfolio").First(&trade, id).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(types.Response{false, "", "Ponuda nije pronađena"})
	}

	if trade.ModifiedBy == fmt.Sprintf("%d", userID) {
		return ctx.Status(fiber.StatusForbidden).JSON(types.Response{
			Success: false,
			Error:   "Ne možete uzastopno menjati ponudu",
		})
	}

	if trade.Status == "accepted" || trade.Status == "executed" {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{false, "", "Ova ponuda je već prihvaćena ili realizovana"})
	}

	if trade.RemoteNegotiationID == nil {
		var portfolio types.Portfolio
		if err := db.DB.First(&portfolio, trade.PortfolioID).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri dohvatanju portfolija",
			})
		}

		var existingContracts []types.OptionContract
		if err := db.DB.
			Where("seller_id = ? AND portfolio_id = ? AND is_exercised = false AND status = ?",
				trade.LocalSellerID, portfolio.ID, "active").
			Find(&existingContracts).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri proveri postojećih ugovora",
			})
		}

		usedQuantity := 0
		for _, contract := range existingContracts {
			usedQuantity += contract.Quantity
		}

		if usedQuantity+trade.Quantity > portfolio.PublicCount {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Nemate dovoljno raspoloživih akcija za prihvatanje ove ponude",
			})
		}

		trade.Status = "accepted"
		trade.LastModified = time.Now().Unix()
		trade.ModifiedBy = fmt.Sprintf("%d", userID)
		if err := db.DB.Save(&trade).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri ažuriranju ponude",
			})
		}

		contract := types.OptionContract{
			OTCTradeID:   trade.ID,
			BuyerID:      trade.LocalBuyerID,
			SellerID:     trade.LocalSellerID,
			PortfolioID:  trade.PortfolioID,
			SecurityID:   trade.SecurityID,
			Quantity:     trade.Quantity,
			StrikePrice:  trade.PricePerUnit,
			Premium:      trade.Premium,
			SettlementAt: trade.SettlementAt,
			Status:       "active",
			CreatedAt:    time.Now().Unix(),
		}

		buyerID := int64(*contract.BuyerID)

		buyerAccounts, err := broker.GetAccountsForUser(buyerID)
		if err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Neuspešno dohvatanje računa kupca",
			})
		}

		sellerID := int64(*contract.SellerID)
		sellerAccounts, err := broker.GetAccountsForUser(sellerID)
		if err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Neuspešno dohvatanje računa prodavca",
			})
		}

		var buyerAccountID, sellerAccountID int64 = -1, -1

		for _, acc := range buyerAccounts {
			if acc.CurrencyType == "USD" {
				buyerAccountID = acc.ID
				break
			}
		}

		for _, acc := range sellerAccounts {
			if acc.CurrencyType == "USD" {
				sellerAccountID = acc.ID
				break
			}
		}

		if buyerAccountID == -1 || sellerAccountID == -1 {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Kupac ili prodavac nema USD račun",
			})
		}

		var buyerAccount *dto.Account
		for _, acc := range buyerAccounts {
			if acc.ID == buyerAccountID {
				buyerAccount = &acc
				break
			}
		}
		if buyerAccount == nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri pronalaženju kupčevog računa",
			})
		}

		if buyerAccount.Balance < contract.Premium {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Kupčev račun nema dovoljno sredstava za plaćanje premije",
			})
		}

		if err := db.DB.Create(&contract).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri kreiranju ugovora " + err.Error(),
			})
		}

		premiumDTO := &dto.OTCPremiumFeeDTO{
			BuyerAccountId:  uint(buyerAccountID),
			SellerAccountId: uint(sellerAccountID),
			Amount:          contract.Premium,
		}

		if err := broker.SendOTCPremium(premiumDTO); err != nil {
			_ = db.DB.Delete(&contract)
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri plaćanju premije",
			})
		}
		return ctx.Status(fiber.StatusOK).JSON(types.Response{true, fmt.Sprintf("Ponuda uspešno prihvaćena. Kreiran ugovor: %d", contract.ID), ""})
	}

	contract := types.OptionContract{
		OTCTradeID:          trade.ID,
		RemoteContractID:    trade.RemoteNegotiationID,
		RemoteBuyerID:       trade.RemoteBuyerID,
		RemoteSellerID:      trade.RemoteSellerID,
		Quantity:            trade.Quantity,
		StrikePrice:         trade.PricePerUnit,
		RemoteNegotiationID: trade.RemoteNegotiationID,
		Premium:             trade.Premium,
		SettlementAt:        trade.SettlementAt,
		Ticker:              trade.Ticker,
		SecurityID:          trade.SecurityID,
		Status:              "active",
		CreatedAt:           time.Now().Unix(),
	}
	if err := db.DB.Create(&contract).Error; err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri kreiranju ugovora"})
	}

	url := fmt.Sprintf("%s/negotiations/%d/%s/accept",
		os.Getenv("BANK4_BASE_URL"),
		*trade.RemoteRoutingNumber,
		*trade.RemoteNegotiationID,
	)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri kreiranju zahteva ka banci 4"})
	}
	req.Header.Set("X-Api-Key", os.Getenv("BANK4_API_KEY"))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return ctx.Status(502).JSON(types.Response{false, "", "Greška pri komunikaciji sa bankom 4"})
	}
	defer resp.Body.Close()

	if resp.StatusCode == 409 {
		return ctx.Status(409).JSON(types.Response{false, "", "Nije vaš red ili su pregovori zatvoreni"})
	}
	if resp.StatusCode != 200 && resp.StatusCode != 202 {
		body, _ := io.ReadAll(resp.Body)
		return ctx.Status(resp.StatusCode).JSON(types.Response{false, "", fmt.Sprintf("Bank4: %s", string(body))})
	}

	trade.Status = "accepted"
	trade.ModifiedBy = fmt.Sprintf("%d", userID)
	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(500).JSON(types.Response{false, "", "Greška pri ažuriranju ponude"})
	}

	return ctx.Status(200).JSON(types.Response{true, "Interbank ponuda prihvaćena", ""})

}

func parsePrefixedID(prefixed string) (uint, error) {
	if len(prefixed) <= 3 {
		return 0, fmt.Errorf("invalid prefixed id: %q", prefixed)
	}
	idStr := prefixed[3:]
	id64, err := strconv.ParseUint(idStr, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("cannot parse user part %q: %w", idStr, err)
	}
	return uint(id64), nil
}

func toRaw(v interface{}) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func (c *OTCTradeController) ExecuteOptionContract(ctx *fiber.Ctx) error {
	id := ctx.Params("id")
	userID := uint(ctx.Locals("user_id").(float64))

	var contract types.OptionContract
	if err := db.DB.First(&contract, id).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(types.Response{
			Success: false,
			Error:   "Ugovor nije pronađen",
		})
	}

	if contract.RemoteContractID != nil {
		const ourRouting = 111
		const theirRouting = 444
		optID := *contract.RemoteContractID
		qty := contract.Quantity
		strike := contract.StrikePrice

		buyerID := int64(userID)
		//userIDStr := strconv.FormatUint(uint64(userID), 10)
		buyerAccounts, err := broker.GetAccountsForUser(buyerID)
		if err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Neuspešno dohvatanje računa kupca",
			})
		}
		var buyerAccountID int64 = -1
		var ourAccountNumber string
		for _, acc := range buyerAccounts {
			if acc.CurrencyType == "USD" {
				buyerAccountID = acc.ID
				ourAccountNumber = acc.AccountNumber
				break
			}
		}
		if buyerAccountID == -1 {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Kupac ili prodavac nema USD račun",
			})
		}

		uid := uuid.NewString()
		log.Infof("Creating a new idempotence key: %s", uid)
		idem := dto.IdempotenceKeyDTO{
			RoutingNumber:       ourRouting,
			LocallyGeneratedKey: uid,
		}

		debitOptionMoney := dto.PostingDTO{
			Account: dto.TxAccountDTO{
				Type: "ACCOUNT",
				Num:  &ourAccountNumber,
			},
			Amount: -strike * float64(qty),
			Asset: dto.AssetDTO{
				Type:  "MONAS",
				Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
			},
		}

		creditSellerMoney := dto.PostingDTO{
			Account: dto.TxAccountDTO{
				Type: "OPTION",
				Id: &dto.ForeignBankIdDTO{
					RoutingNumber: theirRouting,
					UserId:        *contract.RemoteNegotiationID,
				},
			},
			Amount: strike * float64(qty),
			Asset: dto.AssetDTO{
				Type:  "MONAS",
				Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
			},
		}

		creditOptionStock := dto.PostingDTO{
			Account: dto.TxAccountDTO{
				Type: "OPTION",
				Id: &dto.ForeignBankIdDTO{
					RoutingNumber: theirRouting,
					UserId:        *contract.RemoteNegotiationID,
				},
			},
			Amount: float64(-qty),
			Asset: dto.AssetDTO{
				Type:  "STOCK",
				Asset: toRaw(dto.StockDescriptionDTO{Ticker: contract.Ticker}),
			},
		}

		debitBuyerStock := dto.PostingDTO{
			Account: dto.TxAccountDTO{
				Type: "PERSON",
				Id: &dto.ForeignBankIdDTO{
					RoutingNumber: ourRouting,
					UserId:        strconv.FormatInt(buyerID, 10),
				},
			},
			Amount: float64(qty),
			Asset: dto.AssetDTO{
				Type:  "STOCK",
				Asset: toRaw(dto.StockDescriptionDTO{Ticker: contract.Ticker}),
			},
		}

		interbankMsg := dto.InterbankMessageDTO[dto.InterbankTransactionDTO]{
			IdempotenceKey: idem,
			MessageType:    "NEW_TX",
			Message: dto.InterbankTransactionDTO{
				Postings: []dto.PostingDTO{
					debitOptionMoney,
					creditOptionStock,
					debitBuyerStock,
					creditSellerMoney,
				},
				Message: fmt.Sprintf(
					"Exercise %d %s under %s",
					qty, contract.Ticker, optID,
				),
				TransactionId: dto.ForeignBankIdDTO{
					RoutingNumber: ourRouting,
					UserId:        idem.LocallyGeneratedKey,
				},
			},
		}

		ticker := contract.Ticker
		var sec types.Security
		if err := db.DB.
			Where("ticker = ?", ticker).
			First(&sec).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   fmt.Sprintf("Security '%s' nije pronađen", ticker),
			})
		}

		rec := types.InterbankTxnRecord{
			RoutingNumber: 111,
			TransactionId: idem.LocallyGeneratedKey,
			UserID:        userID,
			SecurityID:    sec.ID,
			Quantity:      contract.Quantity,
			PurchasePrice: &contract.StrikePrice,
			NeedsCredit:   true,
			State:         "PREPARED",
			ContractId:    &contract.ID,
		}
		if err := db.DB.Create(&rec).Error; err != nil {
			log.Errorf("Ne mogu da snimim interbank tx record: %v", err)
		}
		log.Infof("Saving interbank tx record: %v", rec)

		body, err := json.Marshal(interbankMsg)
		if err != nil {
			return ctx.Status(500).JSON(types.Response{false, "", "Greška pri serijalizaciji interbank poruke"})
		}

		url := os.Getenv("BANKING_SERVICE_URL") + "/interbank/internal"
		req, _ := http.NewRequest("POST", url, bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Api-Key", os.Getenv("BANK1_SECURITY"))

		resp, err := http.DefaultClient.Do(req)
		if err != nil || resp.StatusCode < 200 || resp.StatusCode >= 300 {
			log.Errorf("Greška pri slanju poruke bankingu: %v", err)
			return ctx.Status(502).JSON(types.Response{false, "", "Greška pri slanju poruke bankingu"})
		}
		defer resp.Body.Close()

		return ctx.Status(fiber.StatusOK).JSON(types.Response{
			Success: true,
			Data:    fmt.Sprintf("Ugovor uspešno realizovan. Kreiran interbank ID: %s", idem.LocallyGeneratedKey),
		})
	}

	if contract.BuyerID == nil || *contract.BuyerID != userID {
		return ctx.Status(fiber.StatusForbidden).JSON(types.Response{
			Success: false,
			Error:   "Nemate pravo da izvršite ovaj ugovor",
		})
	}

	if contract.IsExercised {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Ovaj ugovor je već iskorišćen",
		})
	}

	if contract.SettlementAt.Before(time.Now()) {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Ugovor je istekao",
		})
	}

	buyerID := int64(*contract.BuyerID)
	sellerID := int64(*contract.SellerID)

	buyerAccounts, err := broker.GetAccountsForUser(buyerID)
	if err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Neuspešno dohvatanje računa kupca",
		})
	}

	sellerAccounts, err := broker.GetAccountsForUser(sellerID)
	if err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Neuspešno dohvatanje računa prodavca",
		})
	}

	var sellerPortfolio types.Portfolio
	if err := db.DB.First(&sellerPortfolio, contract.PortfolioID).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Neuspešno dohvatanje portfolija",
		})
	}

	if contract.Quantity > sellerPortfolio.PublicCount {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Nema dovoljno raspoloživih akcija za izvršavanje ovog ugovora",
		})
	}

	var buyerAccountID, sellerAccountID int64 = -1, -1

	var buyerAccount *dto.Account
	for _, acc := range buyerAccounts {
		if acc.CurrencyType == "USD" {
			buyerAccountID = acc.ID
			buyerAccount = &acc
			break
		}
	}

	for _, acc := range sellerAccounts {
		if acc.CurrencyType == "USD" {
			sellerAccountID = acc.ID
			break
		}
	}

	if buyerAccountID == -1 || sellerAccountID == -1 {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Kupac ili prodavac nema USD račun",
		})
	}

	if buyerAccount.Balance < (contract.StrikePrice * float64(contract.Quantity)) {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Kupčev račun nema dovoljno sredstava za izvršavanje ugovora",
		})
	}

	uid := fmt.Sprintf("OTC-%d-%d", contract.ID, time.Now().Unix())

	dto := &types.OTCTransactionInitiationDTO{
		Uid:             uid,
		SellerAccountId: uint(sellerAccountID),
		BuyerAccountId:  uint(buyerAccountID),
		Amount:          contract.StrikePrice * float64(contract.Quantity),
	}

	contract.UID = uid

	if err := db.DB.Save(&contract).Error; err != nil {
		go broker.FailOTC(uid, "Greška prilikom čuvanja statusa ugovora")
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom čuvanja statusa ugovora",
		})
	}

	if err := db.DB.Model(&types.OTCTrade{}).Where("id = ?", contract.OTCTradeID).Update("status", "completed").Error; err != nil {
		go broker.FailOTC(uid, "Greška pri ažuriranju OTC ponude")
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška pri ažuriranju OTC ponude",
		})
	}

	if err := saga.StateManager.UpdatePhase(db.DB, uid, types.PhaseInit); err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom kreiranja OTC transakcije",
		})
	}

	if err := broker.SendOTCTransactionInit(dto); err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom slanja OTC transakcije",
		})
	}

	return ctx.Status(fiber.StatusOK).JSON(types.Response{
		Success: true,
		Data:    "Ugovor uspešno realizovan",
	})
}

func (c *OTCTradeController) GetActiveOffers(ctx *fiber.Ctx) error {
	userID := uint(ctx.Locals("user_id").(float64))
	userIDStr := strconv.FormatUint(uint64(userID), 10)

	const myRouting = 111
	composite := fmt.Sprintf("%d%s", myRouting, userIDStr)

	var trades []types.OTCTrade
	if err := db.DB.
		Preload("Portfolio.Security").
		Where("status = ?", "pending").
		Where(
			"(local_buyer_id  = ? OR local_seller_id  = ?) OR (remote_buyer_id = ? OR remote_seller_id = ?)",
			userID, userID,
			composite, composite,
		).
		Find(&trades).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom dohvatanja aktivnih ponuda",
		})
	}

	return ctx.JSON(types.Response{
		Success: true,
		Data:    trades,
	})
}

func (c *OTCTradeController) GetUserOptionContracts(ctx *fiber.Ctx) error {
	userID := uint(ctx.Locals("user_id").(float64))
	userIDStr := strconv.FormatUint(uint64(userID), 10)

	const myRouting = 111
	composite := fmt.Sprintf("%d%s", myRouting, userIDStr)

	var contracts []types.OptionContract
	if err := db.DB.
		Preload("Portfolio.Security").
		Preload("OTCTrade.Portfolio.Security").
		Where(`(buyer_id = ? OR seller_id = ? OR remote_buyer_id = ? OR remote_seller_id = ?)`,
			userID, userID, composite, composite).
		Find(&contracts).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom dohvatanja ugovora",
		})
	}
	var out []dto.OptionContractDTO
	for _, oc := range contracts {
		ticker := oc.OTCTrade.Ticker

		var secName *string
		if oc.Portfolio != nil && oc.Portfolio.Security.Name != "" {
			secName = &oc.Portfolio.Security.Name
		}

		out = append(out, dto.OptionContractDTO{
			ID:                  oc.ID,
			PortfolioID:         oc.PortfolioID,
			BuyerID:             oc.BuyerID,
			SellerID:            oc.SellerID,
			Ticker:              ticker,
			SecurityName:        secName,
			StrikePrice:         oc.StrikePrice,
			Premium:             oc.Premium,
			Quantity:            oc.Quantity,
			SettlementDate:      oc.SettlementAt.Format(time.RFC3339),
			IsExercised:         oc.IsExercised,
			RemoteRoutingNumber: oc.OTCTrade.RemoteRoutingNumber,
			RemoteBuyerID:       oc.RemoteBuyerID,
			RemoteSellerID:      oc.RemoteSellerID,
		})
	}

	return ctx.JSON(types.Response{
		Success: true,
		Data:    out,
	})
}

func (c *OTCTradeController) RejectOTCTrade(ctx *fiber.Ctx) error {
	id := ctx.Params("id")
	localUserID := uint(ctx.Locals("user_id").(float64))
	localUserIDStr := strconv.FormatUint(uint64(localUserID), 10)

	var trade types.OTCTrade
	if err := db.DB.First(&trade, id).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(types.Response{
			Success: false,
			Error:   "Ponuda nije pronađena",
		})
	}
	if trade.ModifiedBy == fmt.Sprintf("%d", localUserID) {
		return ctx.Status(fiber.StatusForbidden).JSON(types.Response{
			Success: false,
			Error:   "Ne možete uzastopno menjati ponudu",
		})
	}
	if trade.Status != "pending" {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Ponuda više nije aktivna",
		})
	}

	if trade.RemoteNegotiationID == nil {
		trade.Status = "rejected"
		trade.LastModified = time.Now().Unix()
		trade.ModifiedBy = fmt.Sprintf("%s", localUserIDStr)

		if err := db.DB.Save(&trade).Error; err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Greška pri odbijanju ponude",
			})
		}

		return ctx.JSON(types.Response{
			Success: true,
			Data:    "Domaća ponuda je uspešno odbijena",
		})
	}

	baseURL := os.Getenv("BANK4_BASE_URL")
	url := fmt.Sprintf("%s/negotiations/%d/%s",
		baseURL,
		*trade.RemoteRoutingNumber,
		*trade.RemoteNegotiationID,
	)

	httpReq, err := http.NewRequest("DELETE", url, nil)
	if err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška pri kreiranju HTTP zahteva ka banci 4",
		})
	}
	httpReq.Header.Set("X-Api-Key", os.Getenv("BANK4_API_KEY"))

	resp, err := http.DefaultClient.Do(httpReq)
	if err != nil {
		return ctx.Status(fiber.StatusBadGateway).JSON(types.Response{
			Success: false,
			Error:   "Greška pri komunikaciji sa bankom 4",
		})
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		body, _ := io.ReadAll(resp.Body)
		return ctx.Status(resp.StatusCode).JSON(types.Response{
			Success: false,
			Error:   fmt.Sprintf("Bank4: %s", string(body)),
		})
	}
	trade.Status = "rejected"
	trade.LastModified = time.Now().Unix()
	trade.ModifiedBy = fmt.Sprintf("%d", localUserID)

	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška pri ažuriranju ponude nakon odbijanja",
		})
	}

	return ctx.JSON(types.Response{
		Success: true,
		Data:    "Međubankarska ponuda je uspešno odbijena",
	})
}

type PortfolioControllerr struct{}

func NewPortfolioControllerr() *PortfolioControllerr {
	return &PortfolioControllerr{}
}

func InitPortfolioRoutess(app *fiber.App) {
	portfolioController := NewPortfolioControllerr()

	app.Get("/portfolio/public", middlewares.Auth, portfolioController.GetOurAndInterPublicPortfolios)
}

func GetPublicStocks(ctx *fiber.Ctx) error {
	log.Info("Fetching public stocks")
	const myRoutingNumber = 111

	var portfolios []types.Portfolio
	if err := db.DB.Preload("Security").Where("public_count > 0").Find(&portfolios).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom dohvatanja portfolija",
		})
	}

	grouped := map[string][]dto.SellerStockEntry{}

	for _, p := range portfolios {
		if !strings.EqualFold(p.Security.Type, "Stock") {
			continue
		}

		ticker := p.Security.Ticker
		entry := dto.SellerStockEntry{
			Seller: dto.ForeignBankId{
				RoutingNumber: myRoutingNumber,
				ID:            fmt.Sprintf("%d", p.UserID),
			},
			Amount: p.PublicCount,
		}

		grouped[ticker] = append(grouped[ticker], entry)
	}

	var result dto.PublicStocksResponse
	for ticker, sellers := range grouped {
		result = append(result, dto.PublicStock{
			Stock:   dto.StockDescription{Ticker: ticker},
			Sellers: sellers,
		})
	}

	return ctx.JSON(result)
}

type UnifiedPublicPortfolio struct {
	Ticker       string          `json:"ticker"`
	Quantity     int             `json:"quantity"`
	Price        *float64        `json:"price,omitempty"`
	SecurityName *string         `json:"name,omitempty"`
	PortfolioID  *uint           `json:"portfolioId,omitempty"`
	OwnerID      string          `json:"ownerId"`
	Security     *types.Security `json:"security,omitempty"`
}

func fetchForeignPublicStocks() ([]UnifiedPublicPortfolio, error) {
	baseURL := os.Getenv("BANK4_BASE_URL")
	url := fmt.Sprintf("%s/public-stock", baseURL)

	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("Greška pri kreiranju zahteva ka banci 4: %w", err)
	}
	req.Header.Set("X-Api-Key", os.Getenv("BANK4_API_KEY"))

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Greška prilikom slanja zahteva ka banci 4: %w", err)
	}
	defer resp.Body.Close()

	var foreignStocks dto.PublicStocksResponse
	if err := json.NewDecoder(resp.Body).Decode(&foreignStocks); err != nil {
		return nil, fmt.Errorf("Neuspešno parsiranje podataka iz banke 4: %w", err)
	}

	var result []UnifiedPublicPortfolio
	for _, ps := range foreignStocks {
		var sec types.Security
		var namePtr *string
		var secPtr *types.Security
		if err := db.DB.Where("ticker = ?", ps.Stock.Ticker).First(&sec).Error; err == nil {
			tmp := sec
			namePtr = &sec.Name
			secPtr = &tmp
		}

		for _, seller := range ps.Sellers {
			ownerID := fmt.Sprintf("%d%s", seller.Seller.RoutingNumber, seller.Seller.ID)
			result = append(result, UnifiedPublicPortfolio{
				Ticker:       ps.Stock.Ticker,
				Quantity:     seller.Amount,
				Price:        nil,
				SecurityName: namePtr,
				PortfolioID:  nil,
				OwnerID:      ownerID,
				Security:     secPtr,
			})
		}
	}

	return result, nil
}

func (c *PortfolioControllerr) GetOurAndInterPublicPortfolios(ctx *fiber.Ctx) error {
	userID := uint(ctx.Locals("user_id").(float64))

	var localPortfolios []types.Portfolio
	if err := db.DB.
		Where("public_count > 0 AND user_id != ?", userID).
		Preload("Security").
		Find(&localPortfolios).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška prilikom dohvatanja domaćih portfolija",
		})
	}

	var result []UnifiedPublicPortfolio
	for _, p := range localPortfolios {
		if !strings.EqualFold(p.Security.Type, "Stock") {
			continue
		}
		price := p.PurchasePrice
		name := p.Security.Name
		ownerID := strconv.FormatUint(uint64(p.UserID), 10)

		result = append(result, UnifiedPublicPortfolio{
			Ticker:       p.Security.Ticker,
			Quantity:     p.PublicCount,
			Price:        &price,
			SecurityName: &name,
			PortfolioID:  &p.ID,
			OwnerID:      ownerID,
			Security:     &p.Security,
		})
	}

	foreign, err := fetchForeignPublicStocks()
	if err != nil {
		fmt.Println("!!!!!!!!!!!Greška u dohvatanju stranih portfolija:", err)
	} else {
		result = append(result, foreign...)
	}

	return ctx.JSON(types.Response{
		Success: true,
		Data:    result,
	})
}

type CreateInterbankOTCOfferRequest struct {
	Ticker         string  `json:"ticker" validate:"required"`
	Quantity       int     `json:"quantity" validate:"required,gt=0"`
	PricePerUnit   float64 `json:"pricePerUnit" validate:"required,gt=0"`
	Premium        float64 `json:"premium" validate:"required,gte=0"`
	SettlementDate string  `json:"settlementDate" validate:"required"`
	SellerRouting  int     `json:"sellerRouting" validate:"required"`
	SellerID       string  `json:"sellerId" validate:"required"`
}

func (c *OTCTradeController) GetInterbankNegotiation(ctx *fiber.Ctx) error {
	routingStr := ctx.Params("routingNumber")
	negID := ctx.Params("id")

	routingNum, err := strconv.Atoi(routingStr)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
			Success: false,
			Error:   "Neispravan routingNumber",
		})
	}
	fmt.Println(routingNum)
	var t types.OTCTrade
	if err := db.DB.
		Where("remote_negotiation_id = ?", negID).
		First(&t).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(types.Response{
			Success: false,
			Error:   "Negotiation not found",
		})
	}

	const myRouting = 111
	const theirRouting = 444

	if t.RemoteBuyerID == nil || t.RemoteSellerID == nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nepotpuni podaci za međubankarsku ponudu",
		})
	}
	// buyerFB:
	buyerRaw := *t.RemoteBuyerID
	if len(buyerRaw) < 3 {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nevalidan RemoteBuyerID",
		})
	}
	buyerRouting, err := strconv.Atoi(buyerRaw[:3])
	if err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nevalidan routing u RemoteBuyerID",
		})
	}
	buyerIDStr := buyerRaw[3:]
	buyerFB := dto.ForeignBankId{
		RoutingNumber: buyerRouting,
		ID:            buyerIDStr,
	}

	sellerRaw := *t.RemoteSellerID
	if len(sellerRaw) < 3 {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nevalidan RemoteSellerID",
		})
	}
	sellerRouting, err := strconv.Atoi(sellerRaw[:3])
	if err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nevalidan routing u RemoteSellerID",
		})
	}
	sellerIDStr := sellerRaw[3:]
	sellerFB := dto.ForeignBankId{
		RoutingNumber: sellerRouting,
		ID:            sellerIDStr,
	}

	lmb := t.ModifiedBy
	if len(lmb) < 3 {
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Nevalidan ModifiedBy",
		})
	}
	lmRouting, err := strconv.Atoi(lmb[:3])
	if err != nil {
		lmRouting = myRouting
	}
	lmID := lmb[3:]
	lastMod := dto.ForeignBankId{
		RoutingNumber: lmRouting,
		ID:            lmID,
	}

	nt := dto.OtcNegotiation{
		Stock:          dto.StockDescription{Ticker: t.Ticker},
		SettlementDate: t.SettlementAt.Format(time.RFC3339),
		PricePerUnit:   dto.MonetaryValue{Currency: "USD", Amount: t.PricePerUnit},
		Premium:        dto.MonetaryValue{Currency: "USD", Amount: t.Premium},
		BuyerID:        buyerFB,
		SellerID:       sellerFB,
		Amount:         t.Quantity,
		LastModifiedBy: lastMod,
		IsOngoing:      t.Status == "pending",
	}

	return ctx.JSON(nt)
}

func (c *OTCTradeController) CreateInterbankNegotiation(ctx *fiber.Ctx) error {
	ourRouting := 111
	//DODATI PROVERU DA LI POSTOJI PORTFOLIO SA TIM VLASNIKOMOM I TIM TICKEROM
	var off dto.InterbankOtcOfferDTO
	if err := ctx.BodyParser(&off); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Nevalidan JSON format",
		})
	}
	if err := c.validator.Struct(off); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   err.Error(),
		})
	}
	settlementAt, err := time.Parse(time.RFC3339, off.SettlementDate)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Nevalidan settlementDate, očekuje se RFC3339 format",
		})
	}
	negID := uuid.New().String()

	remoteBuyer := fmt.Sprintf("%d%s", off.BuyerID.RoutingNumber, off.BuyerID.ID)
	remoteSeller := fmt.Sprintf("%d%s", off.SellerID.RoutingNumber, off.SellerID.ID)

	modifiedBy := fmt.Sprintf("%d%s", off.LastModifiedBy.RoutingNumber, off.LastModifiedBy.ID)
	var sec types.Security
	if err := db.DB.Where("ticker = ?", off.Stock.Ticker).First(&sec).Error; err != nil {
		return ctx.Status(404).JSON(types.Response{false, "", "Ticker nije pronađen"})
	}
	trade := types.OTCTrade{
		RemoteRoutingNumber: &ourRouting,
		RemoteNegotiationID: &negID,
		RemoteBuyerID:       &remoteBuyer,
		RemoteSellerID:      &remoteSeller,
		Ticker:              off.Stock.Ticker,
		SecurityID:          &sec.ID,
		Quantity:            off.Amount,
		PricePerUnit:        off.PricePerUnit.Amount,
		Premium:             off.Premium.Amount,
		SettlementAt:        settlementAt,
		Status:              "pending",
		ModifiedBy:          modifiedBy,
	}
	if err := db.DB.Create(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"success": false,
			"error":   "Greška pri čuvanju međubankarske ponude",
		})
	}

	return ctx.Status(fiber.StatusCreated).JSON(
		dto.ForeignBankId{
			RoutingNumber: ourRouting,
			ID:            negID,
		})
}

func (c *OTCTradeController) CounterInterbankNegotiation(ctx *fiber.Ctx) error {
	routingStr := ctx.Params("routingNumber")
	negID := ctx.Params("id")
	routingNum, err := strconv.Atoi(routingStr)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Neispravan routingNumber",
		})
	}
	fmt.Println(routingNum)
	var off dto.InterbankOtcOfferDTO
	if err := ctx.BodyParser(&off); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Nevalidan JSON format",
		})
	}
	if err := c.validator.Struct(off); err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   err.Error(),
		})
	}

	var trade types.OTCTrade
	if err := db.DB.
		Where("remote_negotiation_id = ?", negID).
		First(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"success": false,
			"error":   "Ponuda nije pronađena",
		})
	}

	settlementAt, err := time.Parse(time.RFC3339, off.SettlementDate)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Neispravan settlementDate, očekuje se RFC3339 format",
		})
	}

	currActor := fmt.Sprintf("%d%s", off.LastModifiedBy.RoutingNumber, off.LastModifiedBy.ID)
	if trade.ModifiedBy == currActor {
		return ctx.Status(fiber.StatusConflict).JSON(fiber.Map{
			"success": false,
			"error":   "Nije vaš red za kontra-ponudu",
		})
	}

	trade.Quantity = off.Amount
	trade.PricePerUnit = off.PricePerUnit.Amount
	trade.Premium = off.Premium.Amount
	trade.SettlementAt = settlementAt
	trade.ModifiedBy = fmt.Sprintf("%d%s", off.LastModifiedBy.RoutingNumber, off.LastModifiedBy.ID)

	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"success": false,
			"error":   "Greška pri čuvanju kontra-ponude",
		})
	}

	return ctx.Status(fiber.StatusOK).JSON(fiber.Map{
		"success": true,
		"data":    fmt.Sprintf("Interbank konter-ponuda primljena: %s/%s", routingStr, negID),
	})
}

func (c *OTCTradeController) CloseInterbankNegotiation(ctx *fiber.Ctx) error {
	routingStr := ctx.Params("routingNumber")
	negID := ctx.Params("id")

	routingNum, err := strconv.Atoi(routingStr)
	if err != nil {
		return ctx.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"success": false,
			"error":   "Neispravan routingNumber",
		})
	}

	var trade types.OTCTrade
	if err := db.DB.
		Where("remote_negotiation_id = ?", negID).
		First(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"success": false,
			"error":   "Negotation not found",
		})
	}

	if trade.Status != "pending" {
		return ctx.Status(fiber.StatusConflict).JSON(fiber.Map{
			"success": false,
			"error":   "Negotiation is already closed or resolved",
		})
	}
	trade.Status = "rejected"
	trade.LastModified = time.Now().Unix()
	trade.ModifiedBy = fmt.Sprintf("%d%s", routingNum, negID)

	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"success": false,
			"error":   "Greška pri zatvaranju ponude",
		})
	}

	return ctx.Status(fiber.StatusOK).JSON(fiber.Map{
		"success": true,
		"data":    fmt.Sprintf("Negotiation %d/%s closed", routingNum, negID),
	})
}

func (c *OTCTradeController) AcceptInterbankNegotiation(ctx *fiber.Ctx) error {
	routingStr := ctx.Params("routingNumber")
	negID := ctx.Params("id")

	fmt.Println(routingStr)
	var trade types.OTCTrade
	if err := db.DB.
		Where("remote_negotiation_id = ?", negID).
		First(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"success": false,
			"error":   "Negotiation not found",
		})
	}

	if trade.Status != "pending" {
		return ctx.Status(fiber.StatusConflict).JSON(fiber.Map{
			"success": false,
			"error":   "Pregovori su zatvoreni ili ponuda već prihvaćena",
		})
	}

	const myRouting = 111
	const theirRouting = 444

	localIsBuyer := strings.HasPrefix(*trade.RemoteBuyerID, fmt.Sprint(myRouting))
	var routingNumber int
	if localIsBuyer {
		routingNumber = myRouting
	} else {
		routingNumber = theirRouting
	}
	idemp := dto.IdempotenceKeyDTO{
		RoutingNumber:       routingNumber,
		LocallyGeneratedKey: uuid.NewString(),
	}

	optDesc := dto.OptionDescriptionDTO{
		NegotiationID:  dto.ForeignBankIdDTO{RoutingNumber: 444, UserId: *trade.RemoteNegotiationID},
		Stock:          dto.StockDescriptionDTO{Ticker: trade.Ticker},
		PricePerUnit:   dto.MonetaryValueDTO{Currency: "USD", Amount: trade.PricePerUnit},
		SettlementDate: trade.SettlementAt.Format(time.RFC3339),
		Amount:         trade.Quantity,
	}

	var postings []dto.PostingDTO
	if localIsBuyer {
		localSuffix := (*trade.RemoteBuyerID)[len(fmt.Sprint(myRouting)):]
		remoteSuffix := (*trade.RemoteSellerID)[len(fmt.Sprint(theirRouting)):]
		localID, _ := strconv.ParseInt(localSuffix, 10, 64)
		accounts, err := broker.GetAccountsForUser(localID)
		if err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Neuspešno dohvatanje računa prodavca",
			})
		}
		var ourAcctNum string
		for _, a := range accounts {
			if a.CurrencyType == "USD" {
				ourAcctNum = a.AccountNumber
				break
			}
		}
		if ourAcctNum == "" {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Kupac ili prodavac nema USD račun",
			})
		}

		postings = []dto.PostingDTO{
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: myRouting,
						UserId:        fmt.Sprintf("%d", localID),
					},
				},
				Amount: 1,
				Asset: dto.AssetDTO{
					Type:  "OPTION",
					Asset: toRaw(optDesc),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "ACCOUNT",
					Num:  &ourAcctNum,
				},
				Amount: -trade.Premium,
				Asset: dto.AssetDTO{
					Type:  "MONAS",
					Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: theirRouting,
						UserId:        remoteSuffix,
					},
				},
				Amount: -1,
				Asset: dto.AssetDTO{
					Type:  "OPTION",
					Asset: toRaw(optDesc),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: theirRouting,
						UserId:        remoteSuffix,
					},
				},
				Amount: trade.Premium,
				Asset: dto.AssetDTO{
					Type:  "MONAS",
					Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
				},
			},
		}
	} else {
		localSuffix := (*trade.RemoteSellerID)[len(fmt.Sprint(myRouting)):]
		remoteSuffix := (*trade.RemoteBuyerID)[len(fmt.Sprint(theirRouting)):]
		localID, _ := strconv.ParseInt(localSuffix, 10, 64)
		accounts, err := broker.GetAccountsForUser(localID)
		if err != nil {
			return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
				Success: false,
				Error:   "Neuspešno dohvatanje računa prodavca",
			})
		}
		var ourAcctNum string
		for _, a := range accounts {
			if a.CurrencyType == "USD" {
				ourAcctNum = a.AccountNumber
				break
			}
		}
		if ourAcctNum == "" {
			return ctx.Status(fiber.StatusBadRequest).JSON(types.Response{
				Success: false,
				Error:   "Kupac ili prodavac nema USD račun",
			})
		}
		postings = []dto.PostingDTO{
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: theirRouting,
						UserId:        remoteSuffix,
					},
				},
				Amount: 1,
				Asset: dto.AssetDTO{
					Type:  "OPTION",
					Asset: toRaw(optDesc),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: theirRouting,
						UserId:        remoteSuffix,
					},
				},
				Amount: -trade.Premium,
				Asset: dto.AssetDTO{
					Type:  "MONAS",
					Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "PERSON",
					Id: &dto.ForeignBankIdDTO{
						RoutingNumber: myRouting,
						UserId:        localSuffix,
					},
				},
				Amount: -1,
				Asset: dto.AssetDTO{
					Type:  "OPTION",
					Asset: toRaw(optDesc),
				},
			},
			{
				Account: dto.TxAccountDTO{
					Type: "ACCOUNT",
					Num:  &ourAcctNum,
				},
				Amount: trade.Premium,
				Asset: dto.AssetDTO{
					Type:  "MONAS",
					Asset: toRaw(dto.MonetaryAssetDTO{Currency: "USD"}),
				},
			},
		}
	}

	interbankMsg := dto.InterbankMessageDTO[dto.InterbankTransactionDTO]{
		IdempotenceKey: idemp,
		MessageType:    "NEW_TX",
		Message: dto.InterbankTransactionDTO{
			Postings:      postings,
			Message:       fmt.Sprintf("Exercise %d %s under %s", trade.Quantity, trade.Ticker, *trade.RemoteNegotiationID),
			TransactionId: dto.ForeignBankIdDTO{RoutingNumber: myRouting, UserId: idemp.LocallyGeneratedKey},
		},
	}

	body, _ := json.Marshal(interbankMsg)
	req, _ := http.NewRequest("POST", os.Getenv("BANKING_SERVICE_URL")+"/interbank/internal", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Api-Key", os.Getenv("BANK1_SECURITY"))

	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode < 200 || resp.StatusCode >= 300 {
		log.Infof("Greška pri slanju interbank poruke: %v", err)
		slice, err := io.ReadAll(resp.Body)
		if err != nil {
			log.Errorf("Couldn't read response body: %v", err)
		} else {
			log.Infof("Response body: %s", string(slice))
		}
		return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
			Success: false,
			Error:   "Greška pri slanju interbank poruke",
		})
	}

	trade.Status = "accepted"
	if err := db.DB.Save(&trade).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"success": false,
			"error":   "Greška pri ažuriranju ponude",
		})
	}

	contract := types.OptionContract{
		OTCTradeID:          trade.ID,
		RemoteContractID:    &negID,
		UID:                 negID,
		RemoteBuyerID:       trade.RemoteBuyerID,
		RemoteSellerID:      trade.RemoteSellerID,
		Quantity:            trade.Quantity,
		StrikePrice:         trade.PricePerUnit,
		Premium:             trade.Premium,
		SettlementAt:        trade.SettlementAt,
		RemoteNegotiationID: trade.RemoteNegotiationID,
		TransactionID:       &idemp.LocallyGeneratedKey,
		Ticker:              trade.Ticker,
		SecurityID:          trade.SecurityID,
		Status:              "active",
		CreatedAt:           time.Now().Unix(),
	}
	if err := db.DB.Create(&contract).Error; err != nil {
		return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"success": false,
			"error":   "Greška pri kreiranju ugovora",
		})
	}

	data := struct {
		NegotiationID  dto.ForeignBankId    `json:"negotiationId"`
		Stock          dto.StockDescription `json:"stock"`
		PricePerUnit   dto.MonetaryValue    `json:"pricePerUnit"`
		SettlementDate string               `json:"settlementDate"`
		Amount         int                  `json:"amount"`
	}{
		NegotiationID: dto.ForeignBankId{
			RoutingNumber: myRouting,
			ID:            negID,
		},
		Stock:          dto.StockDescription{Ticker: trade.Ticker},
		PricePerUnit:   dto.MonetaryValue{Currency: "USD", Amount: trade.PricePerUnit},
		SettlementDate: trade.SettlementAt.Format(time.RFC3339),
		Amount:         trade.Quantity,
	}

	return ctx.JSON(data)
}

type interbankRaw struct {
	IdempotenceKey dto.IdempotenceKeyDTO `json:"idempotenceKey"`
	MessageType    string                `json:"messageType"`
	Message        json.RawMessage       `json:"message"`
}

func (c *OTCTradeController) HandleInterbankTX(ctx *fiber.Ctx) error {
	var raw interbankRaw
	if err := ctx.BodyParser(&raw); err != nil {
		log.Infof("Invalid JSON format: %v", err)
		log.Infof("Raw body: %s", string(ctx.Body()))
		return ctx.Status(fiber.StatusBadRequest).
			JSON(fiber.Map{"error": "Nevalidan JSON"})
	}
	log.Infof("Handling interbank message: %v", raw.MessageType)
	switch raw.MessageType {
	case "NEW_TX":
		var tx dto.InterbankTransactionDTO
		if err := json.Unmarshal(raw.Message, &tx); err != nil {
			log.Infof("Invalid NEW_TX payload: %v", err)
			return ctx.Status(fiber.StatusBadRequest).
				JSON(fiber.Map{"error": "Nevalidan NEW_TX payload"})
		}

		const myRouting = 111

		for _, p := range tx.Postings {
			if p.Asset.Type == "MONAS" {
				continue
			}

			if p.Account.Type != "PERSON" || p.Account.Id.RoutingNumber != myRouting {
				continue
			}

			switch p.Asset.Type {
			case "OPTION":
				var opt dto.OptionDescriptionDTO
				if err := json.Unmarshal(p.Asset.Asset, &opt); err != nil {
					log.Infof("Greška pri parsiranju OptionDescription: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Nevalidan OptionDescription"})
				}
				var oc types.OptionContract
				if err := db.DB.
					Where("remote_contract_id = ?", opt.NegotiationID.UserId).
					First(&oc).Error; err != nil {
					log.Errorf("Greška pri dohvatanju ugovora: %v", err)
					log.Infof("NegotationId: %v", opt)
					log.Infof("Asset type: %v", fmt.Sprintf("%s", p.Asset.Asset))
					log.Infof("Postings: %v", fmt.Sprintf("%v", p))
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Ugovor nije pronađen za negotiationId=" + opt.NegotiationID.UserId})
				}
				log.Infof("Got option contract: %v", oc)

				ticker := oc.Ticker
				var sec types.Security
				if err := db.DB.
					Where("ticker = ?", ticker).
					First(&sec).Error; err != nil {
					return ctx.Status(fiber.StatusInternalServerError).JSON(types.Response{
						Success: false,
						Error:   fmt.Sprintf("Security '%s' nije pronađen", ticker),
					})
				}
				var userID uint
				var err error
				var isSeller bool
				if strings.HasPrefix(*oc.RemoteBuyerID, fmt.Sprint(myRouting)) {
					userID, err = extractUserID(*oc.RemoteBuyerID, myRouting)
					isSeller = false
				} else {
					userID, err = extractUserID(*oc.RemoteSellerID, myRouting)
					isSeller = true
				}
				if err != nil {
					log.Errorf("Greška pri parsiranju userID: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Nevalidan userID u ugovoru"})
				}

				rec := types.InterbankTxnRecord{
					RoutingNumber: opt.NegotiationID.RoutingNumber,
					TransactionId: tx.TransactionId.UserId,
					UserID:        userID,
					SecurityID:    sec.ID,
					Quantity:      int(p.Amount),
					PurchasePrice: &opt.PricePerUnit.Amount,
					NeedsCredit:   isSeller,
					State:         "PREPARED",
					ContractId:    &oc.ID,
				}
				if err := db.DB.Create(&rec).Error; err != nil {
					log.Errorf("Failed to save interbank transaction record: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).JSON(fiber.Map{"error": "Greška pri čuvanju interbank transakcije"})
				}

			case "STOCK":
				var stock dto.StockDescriptionDTO
				if err := json.Unmarshal(p.Asset.Asset, &stock); err != nil {
					log.Infof("Greška pri parsiranju StockDescription: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Nevalidan StockDescription"})
				}
				negID := p.Account.Id.UserId

				var oc types.OptionContract
				if err := db.DB.
					Where("remote_negotiation_id = ?", negID).
					First(&oc).Error; err != nil {
					log.Errorf("Ugovor za izvršenje nije pronađen: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Ugovor za izvršenje nije pronađen: " + negID})
				}

				var localUserStr string
				if strings.HasPrefix(*oc.RemoteBuyerID, fmt.Sprint(myRouting)) {
					localUserStr = (*oc.RemoteBuyerID)[len(fmt.Sprint(myRouting)):]
				} else {
					localUserStr = (*oc.RemoteSellerID)[len(fmt.Sprint(myRouting)):]
				}
				localUserID, err := strconv.ParseUint(localUserStr, 10, 64)
				if err != nil {
					log.Errorf("Greška pri parsiranju userID: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Nevalidan userID u ugovoru: " + localUserStr})
				}
				qty := int(p.Amount)
				var sec types.Security
				if err := db.DB.Where("ticker = ?", stock.Ticker).First(&sec).Error; err != nil {
					log.Errorf("Greška pri dohvatanju Security: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Security nije pronađen: " + stock.Ticker})
				}
				var port types.Portfolio
				err = db.DB.
					Where("user_id = ? AND security_id = ?", uint(localUserID), sec.ID).
					First(&port).Error
				if errors.Is(err, gorm.ErrRecordNotFound) {
					port = types.Portfolio{
						UserID:        uint(localUserID),
						SecurityID:    sec.ID,
						PurchasePrice: oc.StrikePrice,
						Quantity:      0,
					}
				} else if err != nil {
					log.Errorf("Greška pri dohvatanju portfolija: %v", err)
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Greška pri dohvatanju portfolija"})
				}

				port.Quantity += qty
				if port.Quantity < 0 {
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Nedovoljan broj akcija za oduzimanje"})
				}

				if err := db.DB.Save(&port).Error; err != nil {
					return ctx.Status(fiber.StatusInternalServerError).
						JSON(fiber.Map{"error": "Greška pri snimanju portfolija"})
				}

			default:
				return ctx.Status(fiber.StatusInternalServerError).
					JSON(fiber.Map{"error": "Neočekivan asset type: " + p.Asset.Type})
			}
		}
		log.Info("Sending OK")
		vote := dto.VoteDTO{
			Vote: "YES",
		}
		return ctx.JSON(vote)

	case "COMMIT_TX":
		var msg dto.CommitTransactionDTO
		if err := json.Unmarshal(raw.Message, &msg); err != nil {
			return ctx.Status(fiber.StatusBadRequest).
				JSON(fiber.Map{"error": "Nevalidan COMMIT_TX payload"})
		}
		vote := c.handleCommitTX(raw.IdempotenceKey, msg)
		return ctx.JSON(vote)

	default:
		return ctx.Status(fiber.StatusBadRequest).
			JSON(fiber.Map{"error": "Nepoznat messageType"})
	}
}

func extractUserID(idWithRouting string, routingNumber int) (uint, error) {
	prefix := fmt.Sprint(routingNumber)
	if strings.HasPrefix(idWithRouting, prefix) {
		userIDStr := idWithRouting[len(prefix):]
		userID, err := strconv.ParseUint(userIDStr, 10, 64)
		if err != nil {
			return 0, errors.New("invalid user ID format")
		}
		return uint(userID), nil
	}
	return 0, errors.New("invalid routing prefix")
}

func (c *OTCTradeController) handleCommitTX(key dto.IdempotenceKeyDTO, commit dto.CommitTransactionDTO) dto.VoteDTO {
	txID := commit.TransactionId.UserId
	tx := db.DB.Begin()
	log.Infof("Handling COMMIT_TX for transaction ID: %s", txID)
	var rec types.InterbankTxnRecord
	if err := tx.
		Where("transaction_id = ?", txID).
		First(&rec).Error; err != nil {
		log.Infof("Couldn't find interbank transaction record with txId: %v", txID)
		tx.Rollback()
		return dto.VoteDTO{
			Vote:    "NO",
			Reasons: []dto.VoteReasonDTO{{Reason: "NO_SUCH_TX", Posting: nil}},
		}
	}
	var oc types.OptionContract
	if err := tx.
		Where("id = ?", *rec.ContractId).
		First(&oc).Error; err == nil {
		oc.IsPremiumPaid = ptrBool(true)
		oc.Status = "closed"
		oc.IsExercised = true
		if err := db.DB.Save(&oc).Error; err != nil {
			log.Errorf("Greška pri ažuriranju OptionContract: %v", err)
			tx.Rollback()
			return dto.VoteDTO{Vote: "NO"}
		}
	} else {
		log.Infof("Couldn't get contract with ID: %v", *rec.ContractId)
		tx.Rollback()
		return dto.VoteDTO{
			Vote:    "NO",
			Reasons: []dto.VoteReasonDTO{{Reason: "NO_SUCH_CONTRACT", Posting: nil}},
		}
	}

	log.Infof("Contract: %v", oc)

	if rec.State == "COMMITTED" {
		tx.Commit()
		return dto.VoteDTO{Vote: "YES"}
	}
	var port types.Portfolio
	err := tx.
		Where("user_id = ? AND security_id = ?", rec.UserID, rec.SecurityID).
		First(&port).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		port = types.Portfolio{
			UserID:     rec.UserID,
			SecurityID: rec.SecurityID,
			Quantity:   0,
		}
	} else if err != nil {
		tx.Rollback()
		log.Infof("Unable to find portfolio for userID: %d, securityID: %d", rec.UserID, rec.SecurityID)
		log.Infof("Error: %v", err)
		return dto.VoteDTO{Vote: "NO", Reasons: []dto.VoteReasonDTO{{Reason: "UNABLE_TO_COMMIT"}}}
	}

	if rec.NeedsCredit {
		port.Quantity += rec.Quantity
	} else {
		if port.Quantity < rec.Quantity {
			log.Infof("Insufficient asset quantity for userID: %d, securityID: %d", rec.UserID, rec.SecurityID)
			return dto.VoteDTO{Vote: "NO", Reasons: []dto.VoteReasonDTO{{Reason: "INSUFFICIENT_ASSET"}}}
		}
		port.Quantity -= rec.Quantity
	}
	if err := tx.Save(&port).Error; err != nil {
		tx.Rollback()
		log.Infof("Error saving portfolio for userID: %d, securityID: %d", rec.UserID, rec.SecurityID)
		return dto.VoteDTO{Vote: "NO", Reasons: []dto.VoteReasonDTO{{Reason: "UNABLE_TO_COMMIT"}}}
	}
	rec.State = "COMMITTED"
	tx.Save(&rec)
	tx.Commit()
	return dto.VoteDTO{Vote: "YES"}
}

func ptrBool(b bool) *bool { return &b }

func InitOTCTradeRoutes(app *fiber.App) {
	app.Get("/public-stock", middlewares.RequireInterbankApiKey, GetPublicStocks)
	otcController := NewOTCTradeController()
	otc := app.Group("/otctrade", middlewares.Auth)
	otc.Post("/offer", middlewares.RequirePermission("user.customer.otc_trade"), otcController.CreateOTCTrade)
	otc.Put("/offer/:id/counter", middlewares.RequirePermission("user.customer.otc_trade"), otcController.CounterOfferOTCTrade)
	otc.Put("/offer/:id/accept", middlewares.RequirePermission("user.customer.otc_trade"), otcController.AcceptOTCTrade)
	otc.Put("/offer/:id/reject", middlewares.RequirePermission("user.customer.otc_trade"), otcController.RejectOTCTrade)
	otc.Put("/option/:id/execute", middlewares.RequirePermission("user.customer.otc_trade"), otcController.ExecuteOptionContract)
	otc.Get("/offer/active", middlewares.RequirePermission("user.customer.otc_trade"), otcController.GetActiveOffers)
	otc.Get("/option/contracts", middlewares.RequirePermission("user.customer.otc_trade"), otcController.GetUserOptionContracts)

	app.Get("/negotiations/:routingNumber/:id", middlewares.RequireInterbankApiKey, otcController.GetInterbankNegotiation)
	app.Post("/negotiations", middlewares.RequireInterbankApiKey, otcController.CreateInterbankNegotiation)
	app.Put("/negotiations/:routingNumber/:id", middlewares.RequireInterbankApiKey, otcController.CounterInterbankNegotiation)
	app.Delete("/negotiations/:routingNumber/:id", middlewares.RequireInterbankApiKey, otcController.CloseInterbankNegotiation)
	app.Get("/negotiations/:routingNumber/:id/accept", middlewares.RequireInterbankApiKey, otcController.AcceptInterbankNegotiation)
	app.Post("/interbank/internal", otcController.HandleInterbankTX)
}
