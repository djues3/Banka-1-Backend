package com.banka1.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@SpringBootApplication
public class GatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);


    public static void main(String[] args) {
        ignoreCertificates();
        SpringApplication.run(GatewayApplication.class, args);
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
@RequestMapping("/api")
class UserInfoController {

    private static final Logger log = LoggerFactory.getLogger(UserInfoController.class);

    /**
     * Provides information about the currently authenticated user. This endpoint retrieves details
     * such as the user's name, authentication status, and additional attributes in case of OAuth2
     * authentication.
     *
     * @param authentication the authentication object representing the currently authenticated
     *     user. Can be null if the user is not authenticated.
     * @return a {@link ResponseEntity} containing a map with user authentication details. If the
     *     user is not authenticated, returns a 401 status with an error message.
     */
    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(Authentication authentication) {
        log.info("Authentication: {}", authentication);
        Map<String, Object> response = new HashMap<>();

        if (authentication == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // Basic authentication info
        response.put("authenticated", true);
        response.put("name", authentication.getName());

        // For OAuth2 authentication with user details
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2User oauth2User = ((OAuth2AuthenticationToken) authentication).getPrincipal();

            String email = oauth2User.getAttribute("sub");
            if (email != null) {
                response.put("email", email);
            }

            Map<String, Object> resourceAccess = oauth2User.getAttribute("resource_access");
            if (resourceAccess != null) {
                if (resourceAccess.containsKey("id")) {
                    response.put("id", resourceAccess.get("id"));
                }

                if (resourceAccess.containsKey("permissions")) {
                    response.put("permissions", resourceAccess.get("permissions"));
                }

                if (resourceAccess.containsKey("isAdmin")) {
                    response.put("isAdmin", resourceAccess.get("isAdmin"));
                }

                if (resourceAccess.containsKey("isEmployed")) {
                    response.put("isEmployed", resourceAccess.get("isEmployed"));
                }

                if (resourceAccess.containsKey("department")) {
                    response.put("department", resourceAccess.get("department"));
                }

                if (resourceAccess.containsKey("position")) {
                    response.put("position", resourceAccess.get("position"));
                }
            }

            if (oauth2User instanceof OidcUser oidcUser) {
                response.put("preferredUsername", oidcUser.getPreferredUsername());
                response.put("givenName", oidcUser.getGivenName());
                response.put("familyName", oidcUser.getFamilyName());
            }
        }

        return ResponseEntity.ok(response);
    }
}
