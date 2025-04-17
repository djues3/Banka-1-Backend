package com.banka1.idp.user;

import com.banka1.common.model.Permission;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Implements CredentialsContainer which erases the password field. Care should be taken when
 * managing this entity to avoid unintended persistence of null passwords after security operations.
 */
@Slf4j
@Entity
@Table(name = "\"user\"")
@Getter
@Setter
@ToString(exclude = {"password"})
public class User implements UserDetails, CredentialsContainer {

    @Id private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String birthDate;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, unique = true)
    private String username;

    private String password;

    @Column(nullable = false)
    private Boolean active;

    @Transient private Collection<GrantedAuthority> sessionPermissions;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    @Enumerated(EnumType.STRING)
    private List<Permission> permissions;

    @Column private String position;

    @Column private String department;

    @Column private boolean isAdmin;

    @Column private String userType;

    /**
     * Spring Security requires this method return the "username" to be used to authenticate the
     * user. Since we use email for authentication, it returns the users email. A
     * <em>username()</em> method is also provided to return the username.
     *
     * @return The users email
     */
    @Override
    @NotNull
    public String getUsername() {
        return email;
    }

    public String username() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    /**
     * This method initializes the session-safe permissions collection from the Hibernate-managed
     * one
     */
    @PostLoad
    public void initializeSessionPermissions() {
        if (permissions != null) {
            sessionPermissions =
                    new ArrayList<>(
                            permissions.stream()
                                    .map(p -> new SimpleGrantedAuthority(p.name()))
                                    .toList());
        } else {
            sessionPermissions = new ArrayList<>();
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return sessionPermissions != null ? sessionPermissions : Collections.emptyList();
    }
}
