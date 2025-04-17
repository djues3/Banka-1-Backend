package com.banka1.common.security;

import lombok.Setter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.core.convert.converter.Converter;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;


@Setter
public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    protected String authorityPrefix = "SCOPE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {

        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);

        String principalClaimName = jwt.getSubject();

        JwtAuthenticationToken jwtAuthenticationToken =
                new JwtAuthenticationToken(jwt, authorities, principalClaimName);
        return new ResourceAwareJwtAuthenticationToken(jwtAuthenticationToken);
    }

    protected Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess == null) {
            return Collections.emptyList();
        }
        Object permissionsObj = resourceAccess.get("permissions");
        if (permissionsObj instanceof Collection<?> perms) {
            return perms.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(permission -> new SimpleGrantedAuthority(authorityPrefix + permission))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
