# UC1 — Spring Boot as a Keycloak Resource Server

## What you learn here

How to protect a Java/Spring Boot API with JWT tokens issued by Keycloak.
This is the most common pattern in enterprise microservice architectures:
Keycloak issues the token → your Spring service validates it and extracts roles/claims.

---

## Architecture of the flow

```
Client (curl / React / Python)
    │
    │  1. POST /token → Keycloak
    │  ← access_token (JWT RS256)
    │
    │  2. GET /supply-chain/shipments
    │     Authorization: Bearer <token>
    │
    ▼
Spring Boot (port 8081)
    │
    ├─ SecurityFilterChain
    │     ├─ Verifies RS256 signature (auto-downloads JWKS from Keycloak)
    │     ├─ Validates exp, iss
    │     ├─ Checks roles (RBAC)
    │     └─ Injects Jwt in the controller via @AuthenticationPrincipal
    │
    └─ SupplyChainController
          └─ Uses jwt.getClaim("preferred_username"), roles, tenant_id, etc.
```

**Key difference vs FastAPI:**
- FastAPI: you write `Depends(get_current_user)` manually
- Spring: the framework intercepts the request before the controller automatically

---

## Dependencies (build.gradle)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

`oauth2-resource-server` includes everything: JWT validation, JWKS client, Spring Security integration.

---

## application.yml — minimal configuration

```yaml
server:
  port: 8081

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/altana-dev
```

`issuer-uri` does **two things automatically**:
1. Downloads the public keys from `{issuer-uri}/protocol/openid-connect/certs` (JWKS)
2. Validates that the `iss` claim in the token matches this URI

> **INTERVIEW:** Where does Spring get the public keys to verify the JWT?
> → It downloads them automatically from the Keycloak JWKS endpoint using `issuer-uri`.
> Keycloak rotates keys periodically — Spring refreshes them in the background.

---

## SecurityConfig — the heart of protection

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))  // (1)
            .csrf(csrf -> csrf.disable())                                          // (2)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/supply-chain/health").permitAll()              // (3)
                .requestMatchers(HttpMethod.DELETE, "/supply-chain/suppliers/**")
                    .hasAuthority("ROLE_ADMIN")
                .requestMatchers("/supply-chain/shipments")
                    .hasAnyAuthority("ROLE_ANALYST", "ROLE_ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtConverter)) // (4)
            );

        return http.build();
    }
}
```

**(1) STATELESS:** REST APIs do not use HTTP sessions — each request carries its own token.

**(2) CSRF disabled:** CSRF protects against attacks in apps with session cookies.
Since we use Bearer tokens (not cookies), CSRF does not apply.

**(3) permitAll():** The `/health` endpoint requires no token (useful for load balancers, probes).

**(4) jwtAuthenticationConverter:** We tell Spring how to transform the JWT into an
`Authentication` object with the correct roles. Without this, Spring does not see Keycloak roles.

> **INTERVIEW:** Why do you disable CSRF in your Resource Server?
> → CSRF exploits the fact that the browser sends cookies automatically. Resource Servers
> use Bearer tokens in the `Authorization` header, not cookies — the browser never
> sends the token "automatically", so CSRF does not apply.

> **INTERVIEW (Spring Security 6):** Where did `WebSecurityConfigurerAdapter` go?
> → Removed in Spring Security 6. Replaced by `@Bean SecurityFilterChain`.
> Less inheritance, more composition.

---

## KeycloakJwtConverter — the roles problem

**The problem:** Spring Security reads roles from the `scope` claim by default.
Keycloak puts roles in `realm_access.roles`.

Without the converter → Spring sees no roles → all RBAC endpoints return **403**.

```java
@Component
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRoles(jwt);
        String username = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, username);
    }

    private Collection<GrantedAuthority> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return List.of();

        List<String> roles = (List<String>) realmAccess.getOrDefault("roles", List.of());

        // "ROLE_ANALYST" → SimpleGrantedAuthority("ROLE_ANALYST")
        return roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
```

**`realm_access` claim in a Keycloak JWT:**
```json
{
  "realm_access": {
    "roles": ["ROLE_ANALYST", "ROLE_USER", "default-roles-altana-dev"]
  }
}
```

> **INTERVIEW:** Why does an endpoint with `hasRole("ANALYST")` return 403 with Keycloak
> without extra configuration?
> → Spring Security looks for the `scope` or `authorities` claim by default.
> Keycloak puts roles in `realm_access.roles`. Without a custom `JwtAuthenticationConverter`
> that reads that claim, Spring finds no roles and denies access.

---

## hasRole() vs hasAuthority()

| Method | Looks for | Example |
|--------|-----------|---------|
| `hasRole("ANALYST")` | `"ROLE_ANALYST"` (auto-adds prefix) | `.hasRole("ANALYST")` |
| `hasAuthority("ROLE_ANALYST")` | `"ROLE_ANALYST"` literally | `.hasAuthority("ROLE_ANALYST")` |

Since Keycloak roles already come with the `ROLE_` prefix, we use **`hasAuthority()`**
to avoid the double prefix `ROLE_ROLE_ANALYST`.

---

## Controller — receiving the injected JWT

```java
@GetMapping("/suppliers")
public Map<String, Object> listSuppliers(@AuthenticationPrincipal Jwt jwt) {
    String caller = jwt.getClaimAsString("preferred_username") != null
            ? jwt.getClaimAsString("preferred_username")
            : jwt.getClaimAsString("client_id"); // service account fallback
    return Map.of("requested_by", caller, ...);
}
```

`@AuthenticationPrincipal Jwt jwt` — Spring injects the already-validated JWT.
If the token is from a **service account** (Client Credentials), there is no `preferred_username`
→ we use `client_id` as fallback.

**Equivalent in FastAPI:**
```python
# FastAPI
@app.get("/suppliers")
async def list_suppliers(user: TokenData = Depends(get_current_user)):
    ...

# Spring
@GetMapping("/suppliers")
public Map listSuppliers(@AuthenticationPrincipal Jwt jwt) { ... }
```

---

## Implemented endpoints

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/supply-chain/health` | Public | Health check |
| GET | `/supply-chain/suppliers` | Authenticated | List suppliers |
| GET | `/supply-chain/me` | Authenticated | Current token info |
| GET | `/supply-chain/shipments` | `ROLE_ANALYST` or `ROLE_ADMIN` | List shipments (RBAC) |
| DELETE | `/supply-chain/suppliers/{id}` | `ROLE_ADMIN` only | Delete supplier |
| GET | `/supply-chain/my-shipments` | Authenticated | Shipments filtered by `tenant_id` (B2B2C) |

---

## Testing with curl

```bash
# 1. Get token (Client Credentials — service account)
TOKEN=$(curl -s -X POST http://localhost:8080/realms/altana-dev/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  | python -m json.tool | grep access_token | cut -d'"' -f4)

# 2. Public endpoint (no token)
curl http://localhost:8081/supply-chain/health

# 3. Authenticated endpoint
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/suppliers

# 4. RBAC endpoint — 403 if the token doesn't have ROLE_ANALYST
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/shipments

# 5. Inspect current token claims
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/me
```

---

## Common errors and how to diagnose them

| Error | Cause | Solution |
|-------|-------|---------|
| `401 Unauthorized` | Token missing, expired, or invalid signature | Check `exp`, renew token |
| `403 Forbidden` | Valid token but missing required role | Check `realm_access.roles` in JWT, review KeycloakJwtConverter |
| `500` on startup | `issuer-uri` unreachable | Verify Keycloak is running on the correct port |
| `ROLE_ROLE_ANALYST` doesn't work | Using `hasRole()` with roles that already have the prefix | Switch to `hasAuthority()` |

---

## Summary: what Spring does automatically

When a request arrives with `Authorization: Bearer <token>`:

1. `BearerTokenAuthenticationFilter` extracts the token from the header
2. Downloads JWKS from Keycloak (caches the keys)
3. Verifies RS256 signature
4. Validates `exp` and `iss`
5. Calls `KeycloakJwtConverter` → creates `JwtAuthenticationToken` with roles
6. Stores in `SecurityContextHolder`
7. `SecurityFilterChain` evaluates RBAC rules
8. If passed → reaches the controller with `@AuthenticationPrincipal Jwt jwt` ready

**All of this in ~3 lines of config** (`issuer-uri` + converter + filterChain).
