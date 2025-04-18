package com.banka1.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.security.Principal;
import java.util.Map;

public record JwtPrincipal(Jwt delegate, Map<String, Object> attributes) implements Principal {
	@Override
	public String getName() {
		return delegate.getId();
	}
}
