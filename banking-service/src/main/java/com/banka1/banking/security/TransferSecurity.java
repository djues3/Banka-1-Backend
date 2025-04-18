package com.banka1.banking.security;

import com.banka1.banking.services.TransferService;

import org.springframework.stereotype.Component;

@Component
public class TransferSecurity {

    private final TransferService transferService;

    public TransferSecurity(TransferService transferService) {
        this.transferService = transferService;
    }

    public boolean initiatedTransfer(Long transferId, Long userId) {
        if (transferId == null || userId == null) return false;
        try {
            return transferService
                    .findById(transferId)
                    .getFromAccountId()
                    .getOwnerID()
                    .equals(userId);
        } catch (Exception e) {
            return false;
        }
    }
}
