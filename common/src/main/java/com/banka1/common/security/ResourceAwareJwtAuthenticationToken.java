package com.banka1.common.security;

import lombok.Getter;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Map;

public final class ResourceAwareJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final JwtAuthenticationToken delegate;
    private final Jwt jwt;
    @Getter
    private final Map<String, Object> resourceAccess;
    @Getter
	private final Long userId;
    @Getter
    private final Boolean isAdmin;
    @Getter
    private final Boolean isEmployed;
    @Getter
    private final String department;
    @Getter
    private final String position;

    public ResourceAwareJwtAuthenticationToken(JwtAuthenticationToken delegate) {
        super(delegate.getAuthorities());
        this.delegate = delegate;
        this.jwt = delegate.getToken();
        this.resourceAccess = this.jwt.getClaim("resource_access");
        
        this.userId = resourceAccess != null ? getTypedValue("id", Long.class) : null;
        this.isAdmin = resourceAccess != null ? getTypedValue("isAdmin", Boolean.class) : false;
        this.isEmployed = resourceAccess != null ? getTypedValue("isEmployed", Boolean.class) : false;
        this.department = resourceAccess != null ? getTypedValue("department", String.class) : null;
        this.position = resourceAccess != null ? getTypedValue("position", String.class) : null;
        
        setAuthenticated(delegate.isAuthenticated());
    }

    public ResourceAwareJwtAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, String name) {
        super(authorities);
        this.delegate = new JwtAuthenticationToken(jwt, authorities, name);
        this.jwt = jwt;
        this.resourceAccess = this.jwt.getClaim("resource_access");
        
        this.userId = resourceAccess != null ? getTypedValue("id", Long.class) : null;
        this.isAdmin = resourceAccess != null ? getTypedValue("isAdmin", Boolean.class) : false;
        this.isEmployed = resourceAccess != null ? getTypedValue("isEmployed", Boolean.class) : false;
        this.department = resourceAccess != null ? getTypedValue("department", String.class) : null;
        this.position = resourceAccess != null ? getTypedValue("position", String.class) : null;
        
        setAuthenticated(true);
    }

    @SuppressWarnings("unchecked")
    public Collection<String> getPermissions() {
        if (resourceAccess == null) return null;
        return getTypedValue("permissions", Collection.class);
    }

    // Helper method for type-safe value extraction
    private <T> T getTypedValue(String key, Class<T> type) {
        if (resourceAccess == null) return null;
        
        Object value = resourceAccess.get(key);
        if (value == null) return null;
        
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        
        return null;
    }

    @Override
    public Object getCredentials() {
        return delegate.getCredentials();
    }

    @Override
    public Object getPrincipal() {
        return delegate.getPrincipal();
    }

    public Jwt getToken() {
        return jwt;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        delegate.eraseCredentials();
    }

    @Override
    public String toString() {
        return "ResourceAwareJwtAuthenticationToken{" +
                "delegate=" + delegate +
                ", userId=" + userId +
                ", isAdmin=" + isAdmin +
                ", isEmployed=" + isEmployed +
                ", department='" + department + '\'' +
                ", permissions=" + getPermissions() +
                ", position='" + position + '\'' +
                '}';
    }
}