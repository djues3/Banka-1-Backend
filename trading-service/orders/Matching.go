package orders

import (
	"banka1.com/db"
	"banka1.com/types"
	"fmt"
	"math/rand"
	"time"
)

// MatchOrder se poziva kada je order odobren i spreman za izvršavanje
func MatchOrder(order types.Order) {
	go func() {
		if order.AON {
			if !canExecuteAll(order) {
				fmt.Println("AON: Nema dovoljno za celokupan order")
				return
			}
		}

		for order.RemainingParts != nil && *order.RemainingParts > 0 {
			quantityToExecute := 1
			if *order.RemainingParts < quantityToExecute {
				quantityToExecute = *order.RemainingParts
			}

			price := getOrderPrice(order)
			executePartial(order, quantityToExecute, price)

			*order.RemainingParts -= quantityToExecute

			db.DB.First(&order, order.ID)
			if order.RemainingParts == nil || *order.RemainingParts == 0 {
				order.IsDone = true
				order.Status = "done"
			}

			// Obavezno eksplicitno ažuriraj u bazi
			db.DB.Model(&types.Order{}).Where("id = ?", order.ID).Updates(map[string]interface{}{
				"remaining_parts": *order.RemainingParts,
				"is_done":         order.IsDone,
				"status":          order.Status,
			})

			delay := calculateDelay(order)
			time.Sleep(delay)
		}
	}()
}

func getOrderPrice(order types.Order) float64 {
	// Market order koristi tržišnu cenu
	if order.OrderType == "MARKET" {
		var security types.Security
		db.DB.First(&security, order.SecurityID)
		return security.LastPrice
	}
	// Limit ili Stop-Limit
	if order.LimitPricePerUnit != nil {
		return *order.LimitPricePerUnit
	}
	// Fallback
	return 0.0
}

func executePartial(order types.Order, quantity int, price float64) {
	// Pronađi matching order (npr. suprotnog smera, za istu hartiju)
	var match types.Order
	direction := "buy"
	if order.Direction == "buy" {
		direction = "sell"
	} else {
		direction = "buy"
	}
	db.DB.Where("security_id = ? AND direction = ? AND status = ? AND is_done = ?", order.SecurityID, direction, "approved", false).
		Order("last_modified").
		First(&match)

	if match.ID == 0 {
		fmt.Println("Nema dostupnog ordera za matchovanje")
		return
	}

	matchQuantity := quantity
	if match.RemainingParts != nil && *match.RemainingParts < quantity {
		matchQuantity = *match.RemainingParts
	}

	// Kreiraj transakciju
	txn := types.Transaction{
		OrderID:      order.ID,
		BuyerID:      getBuyerID(order, match),
		SellerID:     getSellerID(order, match),
		SecurityID:   order.SecurityID,
		Quantity:     matchQuantity,
		PricePerUnit: price,
		TotalPrice:   price * float64(matchQuantity),
	}
	db.DB.Create(&txn)

	// Ažuriraj RemainingParts za oba ordera
	*order.RemainingParts -= matchQuantity
	*match.RemainingParts -= matchQuantity
	if *match.RemainingParts == 0 {
		match.IsDone = true
		match.Status = "done"
	}
	db.DB.Save(&order)
	db.DB.Save(&match)

	// Ažuriraj portfolio kupca i prodavca
	updatePortfolio(getBuyerID(order, match), order.SecurityID, matchQuantity)
	updatePortfolio(getSellerID(order, match), order.SecurityID, -matchQuantity)

	// Ako je margin order, ažuriraj iskorišćenje limita
	if order.Margin {
		var actuary types.Actuary
		if err := db.DB.Where("user_id = ?", order.UserID).First(&actuary).Error; err == nil {
			initialMargin := price * float64(matchQuantity) * 0.3 * 1.1
			actuary.UsedLimit += initialMargin
			db.DB.Save(&actuary)
		}
	}

	fmt.Printf("Match success: Order %d ↔ Order %d za %d @ %.2f\n", order.ID, match.ID, matchQuantity, price)
}

func updatePortfolio(userID uint, securityID uint, delta int) {
	var portfolio types.Portfolio
	err := db.DB.Where("user_id = ? AND security_id = ?", userID, securityID).First(&portfolio).Error
	if err != nil {
		// Ako ne postoji unos, kreiraj ga ako je delta > 0
		if delta > 0 {
			portfolio = types.Portfolio{
				UserID:        userID,
				SecurityID:    securityID,
				Quantity:      delta,
				PurchasePrice: 0, // Može se dodatno obraditi
			}
			db.DB.Create(&portfolio)
		}
		return
	}

	portfolio.Quantity += delta
	if portfolio.Quantity <= 0 {
		db.DB.Delete(&portfolio)
	} else {
		db.DB.Save(&portfolio)
	}
}

func calculateDelay(order types.Order) time.Duration {
	// Random vreme izvršavanja u sekundama
	delaySeconds := rand.Intn(10) + 1
	if order.AfterHours {
		return time.Duration(delaySeconds+1800) * time.Second // +30min delay
	}
	return time.Duration(delaySeconds) * time.Second
}

func canExecuteAll(order types.Order) bool {
	// Prava logika: broj dostupnih suprotnih ordera koji mogu da zadovolje celu količinu
	var matchingOrders []types.Order
	direction := "buy"
	if order.Direction == "buy" {
		direction = "sell"
	} else {
		direction = "buy"
	}

	// Pronađi sve odobrene, neizvršene suprotne ordere za istu hartiju
	db.DB.Where("security_id = ? AND direction = ? AND status = ? AND is_done = ?", order.SecurityID, direction, "approved", false).Find(&matchingOrders)

	totalAvailable := 0
	for _, o := range matchingOrders {
		if o.RemainingParts != nil {
			totalAvailable += *o.RemainingParts
		}
	}

	return totalAvailable >= *order.RemainingParts
}

func getBuyerID(a, b types.Order) uint {
	if a.Direction == "buy" {
		return a.UserID
	}
	return b.UserID
}

func getSellerID(a, b types.Order) uint {
	if a.Direction == "sell" {
		return a.UserID
	}
	return b.UserID
}
