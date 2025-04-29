package com.banka1.banking.dto.interbank.newtx.assets;

import com.banka1.banking.dto.interbank.newtx.ForeignBankIdDTO;
import lombok.Data;

@Data
public class OptionDescription {
    private ForeignBankIdDTO negotiationId;
    private StockDescription stock;
    private PricePerUnit pricePerUnit;
    private String settlementDate;
    private int amount;
}

record PricePerUnit(double amount, String currency) {}