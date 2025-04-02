package com.banka1.idp;

import org.springframework.boot.SpringApplication;

public class TestIdpApplication {

  public static void main(String[] args) {
    SpringApplication.from(IdpApplication::main).with(TestcontainersConfiguration.class).run(args);
  }

}

