package broker

import (
	"banka1.com/db"
	"banka1.com/dto"
	"banka1.com/saga"
	"banka1.com/types"
	"context"
	"encoding/json"
	"fmt"
	"github.com/Azure/go-amqp"
	"gorm.io/gorm"
	"log"
	"time"
)

func handle(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message, makeObject func() any, handler func(any) any) {
	err := reciever.AcceptMessage(ctx, message)
	if err != nil {
		log.Printf("Neuspesno prihvatanje poruke: %v", err)
		return
	}

	if message.Properties.ReplyTo == nil {
		log.Printf("Poruka nema postavljenu ReplyTo adresu: %v", message)
		return
	}

	object := makeObject()

	err = json.Unmarshal(messageValueAsBytes(message), object)
	if err != nil {
		log.Printf("Neuspesno parsiranje poruke: %v", err)
		return
	}

	response := handler(object)

	err = send(*message.Properties.ReplyTo, response)
	if err != nil {
		log.Printf("Neuspesno slanje reply-a: %v", err)
		return
	}
}

func handleNoReply(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message, makeObject func() any, handler func(any)) {
	err := reciever.AcceptMessage(ctx, message)
	if err != nil {
		log.Printf("Neuspesno prihvatanje poruke: %v", err)
		return
	}

	object := makeObject()

	err = json.Unmarshal(messageValueAsBytes(message), object)
	if err != nil {
		log.Printf("Neuspesno parsiranje poruke: %v", err)
		return
	}

	handler(object)
}

func handleReliable(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message, makeObject func() any, handler func(any) error) {
	object := makeObject()

	err := json.Unmarshal(messageValueAsBytes(message), object)
	if err != nil {
		log.Printf("Neuspesno parsiranje poruke: %v", err)
		return
	}

	for true {
		err = handler(object)
		if err == nil {
			break
		}
		log.Printf("Greska u procesiranju poruke %v, sledeci pokusaj za 5 sekundi. Greska: %v", message, err)
		time.Sleep(5 * time.Second)
	}

	err = reciever.AcceptMessage(ctx, message)
	if err != nil {
		log.Printf("Neuspesno prihvatanje poruke: %v", err)
		return
	}
}

func defaultErrHandler(_ context.Context, _ *amqp.Receiver, err error) {
	log.Fatalf("Greska u primanju poruke: %v", err)
}

func wrap(makeObject func() any, handler func(any) any) func(context.Context, *amqp.Receiver, *amqp.Message) {
	return func(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message) {
		handle(ctx, reciever, message, makeObject, handler)
	}
}

func wrapNoReply(makeObject func() any, handler func(any)) func(context.Context, *amqp.Receiver, *amqp.Message) {
	return func(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message) {
		handleNoReply(ctx, reciever, message, makeObject, handler)
	}
}

func wrapReliable(makeObject func() any, handler func(any) error) func(context.Context, *amqp.Receiver, *amqp.Message) {
	return func(ctx context.Context, reciever *amqp.Receiver, message *amqp.Message) {
		handleReliable(ctx, reciever, message, makeObject, handler)
	}
}

func getActuary(id any) any {
	var actuary types.Actuary
	if db.DB.First(&actuary, "id = ?", *(id.(*int))).Error != nil {
		return nil
	}
	return &actuary
}

func handleOTCACK(data any) error {
	dto := data.(*types.OTCTransactionACKDTO)

	if dto.Failure {
		log.Println("Saga failed:", dto.Message)
		return nil
	}

	log.Println("Saga progressed for", dto.Uid)

	phase, exists := saga.StateManager.GetPhase(dto.Uid)
	if !exists {
		log.Printf("[SAGA] Nepoznat UID: %s", dto.Uid)
		return nil
	}

	switch phase {
	case saga.PhaseOwnershipRemoved:
		log.Println("[SAGA] OwnershipRemove faza za", dto.Uid)
		if err := removeOwnership(dto.Uid); err != nil {
			log.Printf("Ownership REMOVE error: %v", err)
			_ = SendOTCTransactionFailure(dto.Uid, "Ownership remove failed")
			return err
		}
		saga.StateManager.UpdatePhase(dto.Uid, saga.PhaseOwnershipTransferred)
		return SendOTCTransactionSuccess(dto.Uid)

	case saga.PhaseOwnershipTransferred:
		log.Println("[SAGA] OwnershipAssign faza za", dto.Uid)
		if err := assignOwnership(dto.Uid); err != nil {
			log.Printf("Ownership ASSIGN error: %v", err)
			_ = SendOTCTransactionFailure(dto.Uid, "Ownership assign failed")
			return err
		}
		saga.StateManager.UpdatePhase(dto.Uid, saga.PhaseVerified)
		return SendOTCTransactionSuccess(dto.Uid)

	case saga.PhaseVerified:
		log.Println("[SAGA] FinalCheck faza za", dto.Uid)
		if err := verifyFinalState(dto.Uid); err != nil {
			log.Printf("Finalna provera neuspešna: %v", err)
			_ = SendOTCTransactionFailure(dto.Uid, "Finalna provera neuspešna: "+err.Error())
			if err := rollbackOwnership(dto.Uid); err != nil {
				log.Printf("Rollback ownership nije uspeo: %v", err)
			}
			return err
		}
		saga.StateManager.Remove(dto.Uid)
		log.Println("[SAGA] Uspešno završena saga za", dto.Uid)
		if err := markContractAsExercised(dto.Uid); err != nil {
			log.Printf("Greška prilikom označavanja ugovora kao izvršenog: %v", err)
		}

		return SendOTCTransactionSuccess(dto.Uid)

	default:
		log.Printf("Nepoznata faza SAGA-e za uid: %s", dto.Uid)
		return nil
	}
}

func removeOwnership(uid string) error {
	return db.DB.Transaction(func(tx *gorm.DB) error {
		var contract types.OptionContract
		if err := tx.Where("concat('OTC-', otc_trade_id, '-', exercised_at) = ?", uid).First(&contract).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje ugovora: %w", err)
		}

		var sellerPortfolio types.Portfolio
		if err := tx.First(&sellerPortfolio, contract.PortfolioID).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje portfolija prodavca: %w", err)
		}

		if sellerPortfolio.Quantity < contract.Quantity {
			return fmt.Errorf("Prodavac nema dovoljno akcija")
		}
		if sellerPortfolio.PublicCount < contract.Quantity {
			return fmt.Errorf("Prodavac nema dovoljno javno raspoloživih akcija")
		}

		sellerPortfolio.Quantity -= contract.Quantity
		sellerPortfolio.PublicCount -= contract.Quantity
		if sellerPortfolio.PublicCount < 0 {
			sellerPortfolio.PublicCount = 0
		}

		if err := tx.Save(&sellerPortfolio).Error; err != nil {
			return fmt.Errorf("Greška prilikom ažuriranja portfolija prodavca: %w", err)
		}

		return nil
	})
}

func assignOwnership(uid string) error {
	return db.DB.Transaction(func(tx *gorm.DB) error {
		var contract types.OptionContract
		if err := tx.Where("concat('OTC-', otc_trade_id, '-', exercised_at) = ?", uid).First(&contract).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje ugovora: %w", err)
		}

		buyerPortfolio := types.Portfolio{
			UserID:        contract.BuyerID,
			SecurityID:    contract.SecurityID,
			Quantity:      contract.Quantity,
			PurchasePrice: contract.StrikePrice,
			PublicCount:   0,
		}

		if err := tx.Create(&buyerPortfolio).Error; err != nil {
			return fmt.Errorf("Greška prilikom kreiranja portfolija kupca: %w", err)
		}

		return nil
	})
}

func rollbackOwnership(uid string) error {
	return db.DB.Transaction(func(tx *gorm.DB) error {
		var contract types.OptionContract
		if err := tx.Where("concat('OTC-', otc_trade_id, '-', exercised_at) = ?", uid).First(&contract).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje ugovora: %w", err)
		}

		var sellerPortfolio types.Portfolio
		if err := tx.First(&sellerPortfolio, contract.PortfolioID).Error; err == nil {
			sellerPortfolio.Quantity += contract.Quantity
			sellerPortfolio.PublicCount += contract.Quantity
			if err := tx.Save(&sellerPortfolio).Error; err != nil {
				return fmt.Errorf("Greška prilikom vraćanja portfolija prodavcu: %w", err)
			}
		}

		// VODITI RACUNA O TOME DA LI JE TO DOBAR PORTFOLIO ZA BRISANJE
		var buyerPortfolio types.Portfolio
		err := tx.Where("user_id = ? AND security_id = ?", contract.BuyerID, contract.SecurityID).First(&buyerPortfolio).Error
		if err == nil {
			if buyerPortfolio.Quantity < contract.Quantity {
				return fmt.Errorf("Kupčev portfolio ima manje hartija nego što je predviđeno za rollback")
			}
			buyerPortfolio.Quantity -= contract.Quantity
			if buyerPortfolio.Quantity == 0 {
				if err := tx.Delete(&buyerPortfolio).Error; err != nil {
					return fmt.Errorf("Greška prilikom brisanja portfolija kupca: %w", err)
				}
			} else {
				if err := tx.Save(&buyerPortfolio).Error; err != nil {
					return fmt.Errorf("Greška prilikom ažuriranja portfolija kupca: %w", err)
				}
			}
		}

		return nil
	})
}

func verifyFinalState(uid string) error {
	return db.DB.Transaction(func(tx *gorm.DB) error {
		var contract types.OptionContract
		if err := tx.Where("concat('OTC-', otc_trade_id, '-', exercised_at) = ?", uid).First(&contract).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje ugovora za proveru integriteta: %w", err)
		}

		var sellerPortfolio types.Portfolio
		if err := tx.First(&sellerPortfolio, contract.PortfolioID).Error; err != nil {
			return fmt.Errorf("Neuspešno pronalaženje portfolija prodavca: %w", err)
		}
		expectedSellerQuantity := sellerPortfolio.Quantity
		if expectedSellerQuantity < 0 {
			return fmt.Errorf("Integritet neuspešan: negativna količina kod prodavca")
		}

		var buyerPortfolios []types.Portfolio
		if err := tx.Where("user_id = ? AND security_id = ?", contract.BuyerID, contract.SecurityID).Find(&buyerPortfolios).Error; err != nil {
			return fmt.Errorf("Greška pri traženju portfolija kupca: %w", err)
		}

		totalBuyerQuantity := 0
		for _, p := range buyerPortfolios {
			totalBuyerQuantity += p.Quantity
		}

		if totalBuyerQuantity < contract.Quantity {
			return fmt.Errorf("Integritet neuspešan: kupac nije dobio sve hartije – očekivano %d, ima %d", contract.Quantity, totalBuyerQuantity)
		}

		return nil
	})
}

func markContractAsExercised(uid string) error {
	return db.DB.Model(&types.OptionContract{}).
		Where("concat('OTC-', otc_trade_id, '-', exercised_at) = ?", uid).
		Updates(map[string]interface{}{
			"is_exercised": true,
			"exercised_at": time.Now(),
		}).Error
}

func GetAccountsForUser(userId int64) ([]dto.Account, error) {
	req := dto.UserRequest{UserId: userId}
	var response dto.UserAccountsResponse

	err := sendAndRecieve("get-accounts-by-user", req, &response)
	if err != nil {
		log.Printf("Greška prilikom dohvatanja računa korisnika %d: %v", userId, err)
		return nil, err
	}

	return response.Accounts, nil
}

func StartListeners(ctx context.Context) {
	go listen(ctx, "get-actuary", wrap(func() any { var id int; return &id }, getActuary), defaultErrHandler)
	go listen(ctx, "otc-ack-trade", wrapReliable(func() any { return &types.OTCTransactionACKDTO{} }, handleOTCACK), defaultErrHandler)
}
