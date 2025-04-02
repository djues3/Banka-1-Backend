package com.banka1.idp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@SpringBootApplication
public class IdpApplication {

	public static void main(String[] args) {
		SpringApplication.run(IdpApplication.class, args);
	}

	@Controller
	@RequestMapping("")
	static class IdpApplicationController {
		@GetMapping("")
		public ResponseEntity<String> get() {
			return ResponseEntity.ok("IDP is running");
		}
	}
}

