package com.banka1.banking.dto.interbank.newtx;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TxAccountDTO {
    private String type; // "PERSON" ili "ACCOUNT"
    private ForeignBankIdDTO id;       // ako je PERSON
    private String num;                // ako je ACCOUNT
}
