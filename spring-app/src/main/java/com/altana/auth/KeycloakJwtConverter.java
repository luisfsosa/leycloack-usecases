package com.altana.auth;

/*
 * CONCEPT: Custom JWT Converter for Keycloak
 *
 * Problem: Spring Security reads roles from the "scope" claim.
 * Keycloak puts roles in "realm_access.roles".
 *
 * Without this converter → Spring sees no roles → all RBAC endpoints return 403.
 *
 * INTERVIEW: How do you integrate Keycloak roles with Spring Security?
 * → By implementing Converter<Jwt, AbstractAuthenticationToken> that reads
 *   realm_access.roles and creates a GrantedAuthority for each role.
 */

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRoles(jwt);
        String username = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, username);
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return List.of();

        List<String> roles = (List<String>) realmAccess.getOrDefault("roles", List.of());

        // Keycloak already uses "ROLE_ANALYST", Spring Security maps it directly.
        // hasRole("ANALYST") → looks for "ROLE_ANALYST" ✓
        return roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
