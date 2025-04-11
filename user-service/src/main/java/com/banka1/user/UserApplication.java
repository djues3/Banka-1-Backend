package com.banka1.user;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@SpringBootApplication
public class UserApplication {

	public static void main(String[] args) {
		ignoreCertificates();
		SpringApplication.run(UserApplication.class, args);
	}

	private static void ignoreCertificates() {
		TrustManager[] trustAllCerts =
				new TrustManager[] {
						new X509TrustManager() {
							@Override
							public X509Certificate[] getAcceptedIssuers() {
								return null;
							}

							@Override
							public void checkClientTrusted(X509Certificate[] certs, String authType) {}

							@Override
							public void checkServerTrusted(X509Certificate[] certs, String authType) {}
						}
				};
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		} catch (Exception ignored) {
		}
	}
}

@RestController
class HelloWorldController {
	@GetMapping("/hello")
	public String hello() {
		return "<h1>Hello, World!</h1>";
	}
}
