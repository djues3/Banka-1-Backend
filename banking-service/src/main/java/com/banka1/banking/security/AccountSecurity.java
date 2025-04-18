package com.banka1.banking.security;

import com.banka1.banking.services.AccountService;

import org.springframework.stereotype.Component;

@Component
public class AccountSecurity {
    private final AccountService accountService;

    public AccountSecurity(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Checks if a user is the owner of a specific account.
     *
     * @param accountId The ID of the account to check.
     * @param userId The ID of the user to verify against.
     * @return true if the user is the owner of the account, false otherwise.
     */
    public boolean isAccountOwner(Long accountId, Long userId) {
        if (accountId == null || userId == null) return false;
        try {
            return accountService.findById(accountId).getOwnerID().equals(userId);
        } catch (Exception e) {
            return false;
        }
    }
}
