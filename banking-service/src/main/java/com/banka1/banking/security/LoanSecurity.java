package com.banka1.banking.security;

import com.banka1.banking.services.LoanService;
import org.springframework.stereotype.Component;

@Component
public class LoanSecurity {
	private final LoanService loanService;

	public LoanSecurity(LoanService loanService) {this.loanService = loanService;}

	/**
	 * Determines if a user is the owner of a specific loan.
	 *
	 * @param loanId The ID of the loan to check.
	 * @param userId The ID of the user to verify against.
	 * @return true if the user is the owner of the loan, false otherwise.
	 */
	public boolean isLoanOwner(Long loanId, Long userId) {
		if (loanId == null || userId == null) return false;
		return loanService.getAllUserLoans(userId).stream().anyMatch(l -> l.getId().equals(loanId));
	}
}
