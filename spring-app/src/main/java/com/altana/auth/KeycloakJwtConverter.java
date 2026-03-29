package com.altana.auth;

/*
 * CONCEPTO: Custom JWT Converter para Keycloak
 *
 * Problema: Spring Security lee roles del claim "scope".
 * Keycloak pone los roles en "realm_access.roles".
 *
 * Sin este converter → Spring no ve ningún rol → todos los endpoints RBAC dan 403.
 *
 * ENTREVISTA: ¿Cómo integras los roles de Keycloak con Spring Security?
 * → Implementando Converter<Jwt, AbstractAuthenticationToken> que lee
 *   realm_access.roles y crea un GrantedAuthority por cada rol.
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

        // Keycloak ya usa "ROLE_ANALYST", Spring Security lo mapea directo.
        // hasRole("ANALYST") → busca "ROLE_ANALYST" ✓
        return roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
