package com.banka1.banking.dto.interbank.committx;

import com.banka1.banking.dto.interbank.newtx.ForeignBankIdDTO;

import lombok.Data;

@Data
public class CommitTransactionDTO {
    private ForeignBankIdDTO transactionId;
}
