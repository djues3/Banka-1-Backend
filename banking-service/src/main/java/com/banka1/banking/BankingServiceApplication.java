package com.banka1.banking;

import com.banka1.common.annotation.Bank1Application;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Bank1Application
@EnableScheduling
public class BankingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingServiceApplication.class, args);
    }

}
