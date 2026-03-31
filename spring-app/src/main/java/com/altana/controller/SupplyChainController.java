package com.altana.controller;

/*
 * CONCEPT: Resource Server Controller
 *
 * This controller knows nothing about Keycloak.
 * It receives the already-validated Jwt via @AuthenticationPrincipal.
 *
 * Spring Security guarantees that if a request reaches here:
 *   1. Valid RS256 signature (verified with Keycloak's public key)
 *   2. Token not expired
 *   3. Issuer = altana-dev
 *   4. Required role present (for RBAC endpoints)
 *
 * Comparison with FastAPI:
 *   FastAPI: user: TokenData = Depends(get_current_user)
 *   Spring:  @AuthenticationPrincipal Jwt jwt
 *   → Same concept: the framework injects the authenticated user.
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

    // ─── PUBLIC ──────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "supply-chain-api-java");
    }

    // ─── AUTHENTICATED (any user) ─────────────────────────────────────────

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

    // ─── RBAC ─────────────────────────────────────────────────────────────

    @GetMapping("/shipments")
    public Map<String, Object> listShipments(@AuthenticationPrincipal Jwt jwt) {
        // SecurityConfig already verified ROLE_ANALYST or ROLE_ADMIN
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
        // SecurityConfig already verified ROLE_ADMIN
        return Map.of(
            "deleted_by",  jwt.getClaimAsString("preferred_username"),
            "supplier_id", supplierId,
            "message",     "Supplier " + supplierId + " deleted"
        );
    }

    // ─── B2B2C — tenant-scoped data ───────────────────────────────────────

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

    // ─── HELPER ───────────────────────────────────────────────────────────

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
