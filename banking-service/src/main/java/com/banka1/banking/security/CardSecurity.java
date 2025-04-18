package com.banka1.banking.security;

import com.banka1.banking.services.CardService;
import org.springframework.stereotype.Component;

@Component
public class CardSecurity {
	private final CardService cardService;

	public CardSecurity(CardService cardService) {this.cardService = cardService;}

	/**
	 * Check if a user is the owner of a card
	 *
	 * @param cardId The card ID to check
	 * @param userId The user ID to verify against
	 * @return true if the user is the owner of the card
	 */
	public boolean isCardOwner(Long cardId, Long userId) {
		if (cardId == null || userId == null) return false;
		try {
			return cardService.findById(cardId).getAccount().getOwnerID().equals(userId);
		} catch (Exception e) {
			return false;
		}
	}
}
