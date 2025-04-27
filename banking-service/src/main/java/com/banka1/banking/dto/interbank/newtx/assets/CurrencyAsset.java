package com.banka1.banking.dto.interbank.newtx.assets;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CurrencyAsset {
    private String currency; // npr. "EUR", "USD", ...
}
