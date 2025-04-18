package com.banka1.banking.security;

import com.banka1.banking.models.Receiver;
import com.banka1.banking.services.ReceiverService;

import org.springframework.stereotype.Component;

@Component
public class ReceiverSecurity {
    private final ReceiverService receiverService;
    private final AccountSecurity accountSecurity;

    public ReceiverSecurity(ReceiverService receiverService, AccountSecurity accountSecurity) {
        this.receiverService = receiverService;
        this.accountSecurity = accountSecurity;
    }

    /**
     * Determines if the given receiver is "owned" by the given user
     *
     * @param receiverId The ID of the receiver whose ownership is being verified.
     * @param userId The ID of the user to check ownership against.
     * @return true if the user is the owner of the account associated with the receiver, false otherwise.
     */
    public boolean isReceiverOf(Long receiverId, Long userId) {
        if (receiverId == null || userId == null) return false;
        try {
            return receiverService.findById(receiverId).getCustomerId().equals(userId);
        } catch (Exception e) {
            return false;
        }
    }
}
