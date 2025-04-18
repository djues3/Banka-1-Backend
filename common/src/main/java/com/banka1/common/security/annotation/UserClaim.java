package com.banka1.common.security.annotation;

import org.springframework.security.config.annotation.web.oauth2.resourceserver.OAuth2ResourceServerSecurityMarker;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE })
@Retention(value = RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "attributes[\"resource_access\"]['{claim}']")
public @interface UserClaim {
  String claim() default "id";
}

