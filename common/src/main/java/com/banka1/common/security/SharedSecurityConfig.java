package com.banka1.common.security;

import com.banka1.common.security.annotation.UserClaim;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.*;

@Configuration
@EnableMethodSecurity
public class SharedSecurityConfig {

    /** Bean which allows templating in SPeL expressions. Heavily (ab)used in {@link UserClaim} */
    @Bean
    static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    /**
     * Configures the security filter chain for the application.
     *
     * @param http the {@link HttpSecurity} to configure the security filters and settings for HTTP requests
     * @param converter the {@link CustomJwtAuthenticationConverter} for converting JWT claims into authentication tokens
     * @param authorizeHttpRequests a {@link Customizer} used to configure authorization rules for incoming HTTP requests
     * @return the constructed {@link SecurityFilterChain} for handling HTTP security
     * @throws Exception if an error occurs while building the security filter chain
     */
    @Bean
    @Fallback
    protected SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CustomJwtAuthenticationConverter converter,
            Customizer<
                            AuthorizeHttpRequestsConfigurer<HttpSecurity>
                                    .AuthorizationManagerRequestMatcherRegistry>
                    authorizeHttpRequests)
            throws Exception {
        // Sets the prefix so that hasRole works in SpEL
        converter.setAuthorityPrefix("ROLE_");
        http.authorizeHttpRequests(authorizeHttpRequests)

                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(cors()));

        return http.build();
    }

    @Bean
    CorsConfigurationSource cors() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH"));
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Defines a bean that customizes HTTP authorization rules for requests.
     * This method configures the authorization manager to require authentication
     * for all incoming requests. Used if no other bean of the same type is provided
     *
     * @return a customizer to configure HTTP request authorization, ensuring all requests
     *         are authenticated.
     */
    @Bean
    @Fallback
    Customizer<
                    AuthorizeHttpRequestsConfigurer<HttpSecurity>
                            .AuthorizationManagerRequestMatcherRegistry> fallbackAuthorizeHttpRequests() {
        return authorize -> authorize.anyRequest().authenticated();
    }

    @Bean
    @Fallback
    CustomJwtAuthenticationConverter customJwtAuthenticationConverter() {
        return new CustomJwtAuthenticationConverter();
    }
}
