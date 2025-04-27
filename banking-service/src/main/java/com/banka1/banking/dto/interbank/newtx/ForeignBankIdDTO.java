package com.banka1.banking.dto.interbank.newtx;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ForeignBankIdDTO {
    private String routingNumber;
    private String id;
}
