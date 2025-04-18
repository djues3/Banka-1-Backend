package com.banka1.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final ClientRegistrationRepository clientRegistrationRepository;

    public SecurityConfig(ClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);

        config.setAllowedHeaders(
                Arrays.asList("Origin", "Content-Type", "Accept", "Authorization"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // Dynamic Access-Control-Allow-Origin: Set it to the Origin from the request
        config.setAllowedOriginPatterns(Collections.singletonList("*"));
        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/banking/currency/**",
                                                "/api/banking/metadata/**",
                                                "/api/user/api/users/reset-password",
                                                "/api/user/api/set-password")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())

                // Used to simplify the proxy configuration
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2Login(
                        o ->
                                o.loginProcessingUrl("/api/login/oauth2/code/idp")
                                        .successHandler(
                                                (req, res, auth) -> {
                                                    if (!(auth
                                                            instanceof
                                                            OAuth2AuthenticationToken token)) {
                                                        throw new IllegalArgumentException(
                                                                "Unknown authentication type for oauth2Login");
                                                    }
                                                    Map<String, Object> resourceAccess =
                                                            token.getPrincipal()
                                                                    .getAttribute(
                                                                            "resource_access");
                                                    if (resourceAccess == null) {
                                                        throw new IllegalArgumentException(
                                                                "Invalid OAuth2AuthenticationToken");
                                                    }
                                                    String position =
                                                            (String) resourceAccess.get("position");
                                                    if (position.equalsIgnoreCase("N/A"))
                                                        res.sendRedirect("/customer-home");
                                                    else res.sendRedirect("/employee-home");
                                                }))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/logout")
                                        .logoutSuccessHandler(oidcLogoutSuccessHandler()))
                .build();
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(this.clientRegistrationRepository);

        // Sets the location that the End-User's User Agent will be redirected to
        // after the logout has been performed at the Provider
        oidcLogoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}");

        return oidcLogoutSuccessHandler;
    }
}
