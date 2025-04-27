package com.banka1.banking.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Data
@Getter
@Setter
public class OtpTokenDTO {

    private UUID transferId;
    private String otpCode;

}

