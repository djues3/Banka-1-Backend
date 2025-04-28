package com.banka1.banking.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class InterbankConfig {
    @Value("${interbank.routing.number}")
    private String routingNumber;

    @Value("${foreign.bank.routing.number}")
    private String foreignBankRoutingNumber;

    @Value("${interbank.target.url}")
    private String interbankTargetUrl;

    @Value("${api.key}")
    private String apiKey;

    @Value("${foreign.bank.api.key}")
    private String foreignBankApiKey;

    @Value("${trading.service.url}")
    private String tradingServiceUrl;
}
