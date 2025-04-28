package com.banka1.banking.dto.interbank.internal;

import lombok.Data;
import lombok.Getter;

@Data
@Getter
public class PremiumPaymentDTO {
    private String FromAccount;
    private Double Amount;
    private String To;
}
