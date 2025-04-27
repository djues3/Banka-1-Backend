package com.banka1.banking.dto.interbank.newtx;

import com.banka1.banking.models.helper.IdempotenceKey;
import lombok.Data;

import java.util.List;

@Data
public class InterbankTransactionDTO {
    private List<PostingDTO> postings;
    private String message;
    private ForeignBankIdDTO transactionId;
    private String timestamp;
}
