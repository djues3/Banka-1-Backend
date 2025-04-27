package com.banka1.banking.dto.interbank.newtx.assets;

import com.banka1.banking.dto.interbank.newtx.AssetDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MonetaryAssetDTO extends AssetDTO {
    private CurrencyAsset asset;
}
