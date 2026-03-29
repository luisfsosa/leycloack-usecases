package com.altana.controller;

/*
 * CONCEPTO: Resource Server Controller
 *
 * Este controller NO sabe nada de Keycloak.
 * Recibe el Jwt ya validado via @AuthenticationPrincipal.
 *
 * Spring Security garantiza que si el request llega aquí:
 *   1. Firma RS256 válida (verificada con public key de Keycloak)
 *   2. Token no expirado
 *   3. Issuer = altana-dev
 *   4. Rol requerido presente (para endpoints RBAC)
 *
 * Comparación con FastAPI:
 *   FastAPI: user: TokenData = Depends(get_current_user)
 *   Spring:  @AuthenticationPrincipal Jwt jwt
 *   → Mismo concepto: el framework inyecta el usuario autenticado.
 */

import com.altana.auth.TokenData;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/supply-chain")
public class SupplyChainController {

    // ─── PÚBLICO ─────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "supply-chain-api-java");
    }

    // ─── AUTENTICADO (cualquier usuario) ─────────────────────────────────────

    @GetMapping("/suppliers")
    public Map<String, Object> listSuppliers(@AuthenticationPrincipal Jwt jwt) {
        String caller = jwt.getClaimAsString("preferred_username") != null
                ? jwt.getClaimAsString("preferred_username")
                : jwt.getClaimAsString("client_id"); // service account fallback
        return Map.of(
            "requested_by", caller,
            "suppliers", List.of(
                Map.of("id", 1, "name", "Acme Corp",    "country", "US"),
                Map.of("id", 2, "name", "Global Parts", "country", "DE")
            )
        );
    }

    @GetMapping("/me")
    public TokenData me(@AuthenticationPrincipal Jwt jwt) {
        return extractTokenData(jwt);
    }

    // ─── RBAC ─────────────────────────────────────────────────────────────────

    @GetMapping("/shipments")
    public Map<String, Object> listShipments(@AuthenticationPrincipal Jwt jwt) {
        // SecurityConfig ya verificó ROLE_ANALYST o ROLE_ADMIN
        return Map.of(
            "requested_by", jwt.getClaimAsString("preferred_username"),
            "shipments", List.of(
                Map.of("id", "SH-001", "origin", "Shanghai", "destination", "LA", "status", "in_transit"),
                Map.of("id", "SH-002", "origin", "Hamburg",  "destination", "NY", "status", "delivered")
            )
        );
    }

    @DeleteMapping("/suppliers/{supplierId}")
    public Map<String, Object> deleteSupplier(
            @PathVariable int supplierId,
            @AuthenticationPrincipal Jwt jwt) {
        // SecurityConfig ya verificó ROLE_ADMIN
        return Map.of(
            "deleted_by",  jwt.getClaimAsString("preferred_username"),
            "supplier_id", supplierId,
            "message",     "Proveedor " + supplierId + " eliminado"
        );
    }

    // ─── B2B2C — filtrado por tenant ──────────────────────────────────────────

    @GetMapping("/my-shipments")
    public Map<String, Object> myShipments(@AuthenticationPrincipal Jwt jwt) {
        List<Map<String, String>> allShipments = List.of(
            Map.of("id", "SH-001", "origin", "Shanghai", "destination", "LA", "status", "in_transit", "tenant_id", "toyota"),
            Map.of("id", "SH-002", "origin", "Hamburg",  "destination", "NY", "status", "delivered",  "tenant_id", "toyota"),
            Map.of("id", "SH-003", "origin", "Tokyo",    "destination", "LA", "status", "pending",    "tenant_id", "ford"),
            Map.of("id", "SH-004", "origin", "Osaka",    "destination", "NY", "status", "in_transit", "tenant_id", "toyota")
        );

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) jwt.getClaimAsMap("realm_access")
                                               .getOrDefault("roles", List.of());

        boolean viewerOnly = roles.contains("ROLE_VIEWER")
                          && !roles.contains("ROLE_ANALYST")
                          && !roles.contains("ROLE_ADMIN");

        if (viewerOnly) {
            String tenant = jwt.getClaimAsString("tenant_id");
            if (tenant == null) tenant = "";
            final String t = tenant;
            return Map.of(
                "requested_by", jwt.getClaimAsString("preferred_username"),
                "tenant_id",    t,
                "filter",       "tenant=" + t,
                "shipments",    allShipments.stream().filter(s -> t.equals(s.get("tenant_id"))).toList()
            );
        }

        return Map.of(
            "requested_by", jwt.getClaimAsString("preferred_username"),
            "filter",       "none (full access)",
            "shipments",    allShipments
        );
    }

    // ─── HELPER ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TokenData extractTokenData(Jwt jwt) {
        var realmAccess = jwt.getClaimAsMap("realm_access");
        List<String> roles = realmAccess != null
                ? (List<String>) realmAccess.getOrDefault("roles", List.of())
                : List.of();

        return new TokenData(
            jwt.getClaimAsString("sub"),
            jwt.getClaimAsString("preferred_username"),
            jwt.getClaimAsString("email"),
            roles,
            jwt.getClaimAsString("tenant_id"),
            jwt.getClaimAsString("user_type")
        );
    }
}
