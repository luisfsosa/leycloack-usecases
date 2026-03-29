# UC1 — Spring Boot como Resource Server con Keycloak

## ¿Qué aprendes aquí?

Cómo proteger una API Java/Spring Boot con tokens JWT emitidos por Keycloak.
Es el patrón más común en arquitecturas microservicios enterprise:
Keycloak emite el token → tu servicio Spring lo valida y extrae roles/claims.

---

## Arquitectura del flujo

```
Cliente (curl / React / Python)
    │
    │  1. POST /token → Keycloak
    │  ← access_token (JWT RS256)
    │
    │  2. GET /supply-chain/shipments
    │     Authorization: Bearer <token>
    │
    ▼
Spring Boot (puerto 8081)
    │
    ├─ SecurityFilterChain
    │     ├─ Verifica firma RS256 (descarga JWKS de Keycloak automáticamente)
    │     ├─ Valida exp, iss
    │     ├─ Verifica roles (RBAC)
    │     └─ Inyecta Jwt en el controller via @AuthenticationPrincipal
    │
    └─ SupplyChainController
          └─ Usa jwt.getClaim("preferred_username"), roles, tenant_id, etc.
```

**Diferencia clave vs FastAPI:**
- FastAPI: tú escribes `Depends(get_current_user)` manualmente
- Spring: el framework intercepta el request antes del controller automáticamente

---

## Dependencias (build.gradle)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

`oauth2-resource-server` incluye todo: validación JWT, JWKS client, integración con Spring Security.

---

## application.yml — configuración mínima

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

`issuer-uri` hace **dos cosas automáticamente**:
1. Descarga las public keys desde `{issuer-uri}/protocol/openid-connect/certs` (JWKS)
2. Valida que el claim `iss` del token coincida con esta URI

> **ENTREVISTA:** ¿Dónde obtiene Spring las public keys para verificar el JWT?
> → Las descarga automáticamente del endpoint JWKS de Keycloak usando `issuer-uri`.
> Keycloak rota las keys periódicamente — Spring las refresca en background.

---

## SecurityConfig — el corazón de la protección

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

**(1) STATELESS:** Las APIs REST no usan sesión HTTP — cada request lleva su propio token.

**(2) CSRF deshabilitado:** CSRF protege contra ataques en apps con cookies de sesión.
Como usamos Bearer tokens (no cookies), CSRF no aplica.

**(3) permitAll():** El endpoint `/health` no requiere token (útil para load balancers, probes).

**(4) jwtAuthenticationConverter:** Le decimos a Spring cómo transformar el JWT en un objeto
`Authentication` con los roles correctos. Sin esto, Spring no ve los roles de Keycloak.

> **ENTREVISTA:** ¿Por qué deshabilitas CSRF en tu Resource Server?
> → CSRF explota que el browser envía cookies automáticamente. Los Resource Servers
> usan Bearer tokens en el header `Authorization`, no cookies — el browser nunca
> envía el token "automáticamente", así que CSRF no aplica.

> **ENTREVISTA (Spring Security 6):** ¿Dónde fue `WebSecurityConfigurerAdapter`?
> → Eliminado en Spring Security 6. Se reemplaza por `@Bean SecurityFilterChain`.
> Menos herencia, más composición.

---

## KeycloakJwtConverter — el problema de los roles

**El problema:** Spring Security lee roles del claim `scope` por defecto.
Keycloak pone los roles en `realm_access.roles`.

Sin el converter → Spring no ve ningún rol → todos los endpoints RBAC dan **403**.

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

**Claim `realm_access` en un JWT de Keycloak:**
```json
{
  "realm_access": {
    "roles": ["ROLE_ANALYST", "ROLE_USER", "default-roles-altana-dev"]
  }
}
```

> **ENTREVISTA:** ¿Por qué un endpoint con `hasRole("ANALYST")` da 403 con Keycloak
> sin configuración extra?
> → Spring Security busca el claim `scope` o `authorities` por defecto.
> Keycloak pone los roles en `realm_access.roles`. Sin un `JwtAuthenticationConverter`
> custom que lea ese claim, Spring no encuentra ningún rol y deniega el acceso.

---

## hasRole() vs hasAuthority()

| Método | Busca | Ejemplo |
|--------|-------|---------|
| `hasRole("ANALYST")` | `"ROLE_ANALYST"` (añade prefijo automáticamente) | `.hasRole("ANALYST")` |
| `hasAuthority("ROLE_ANALYST")` | `"ROLE_ANALYST"` literalmente | `.hasAuthority("ROLE_ANALYST")` |

Como los roles de Keycloak ya vienen con prefijo `ROLE_`, usamos **`hasAuthority()`**
para evitar el doble prefijo `ROLE_ROLE_ANALYST`.

---

## Controller — recibir el JWT inyectado

```java
@GetMapping("/suppliers")
public Map<String, Object> listSuppliers(@AuthenticationPrincipal Jwt jwt) {
    String caller = jwt.getClaimAsString("preferred_username") != null
            ? jwt.getClaimAsString("preferred_username")
            : jwt.getClaimAsString("client_id"); // service account fallback
    return Map.of("requested_by", caller, ...);
}
```

`@AuthenticationPrincipal Jwt jwt` — Spring inyecta el JWT ya validado.
Si el token es de un **service account** (Client Credentials), no hay `preferred_username`
→ usamos `client_id` como fallback.

**Equivalente en FastAPI:**
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

## Endpoints implementados

| Método | Ruta | Acceso | Descripción |
|--------|------|--------|-------------|
| GET | `/supply-chain/health` | Público | Health check |
| GET | `/supply-chain/suppliers` | Autenticado | Lista proveedores |
| GET | `/supply-chain/me` | Autenticado | Info del token actual |
| GET | `/supply-chain/shipments` | `ROLE_ANALYST` o `ROLE_ADMIN` | Lista envíos (RBAC) |
| DELETE | `/supply-chain/suppliers/{id}` | Solo `ROLE_ADMIN` | Eliminar proveedor |
| GET | `/supply-chain/my-shipments` | Autenticado | Envíos filtrados por `tenant_id` (B2B2C) |

---

## Probar con curl

```bash
# 1. Obtener token (Client Credentials — service account)
TOKEN=$(curl -s -X POST http://localhost:8080/realms/altana-dev/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  | python -m json.tool | grep access_token | cut -d'"' -f4)

# 2. Endpoint público (sin token)
curl http://localhost:8081/supply-chain/health

# 3. Endpoint autenticado
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/suppliers

# 4. Endpoint RBAC — 403 si el token no tiene ROLE_ANALYST
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/shipments

# 5. Inspeccionar claims del token actual
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/me
```

---

## Errores comunes y cómo diagnosticarlos

| Error | Causa | Solución |
|-------|-------|---------|
| `401 Unauthorized` | Token ausente, expirado o firma inválida | Verificar `exp`, renovar token |
| `403 Forbidden` | Token válido pero sin el rol requerido | Verificar `realm_access.roles` en el JWT, revisar KeycloakJwtConverter |
| `500` al arrancar | `issuer-uri` no alcanzable | Verificar que Keycloak esté corriendo en el puerto correcto |
| `ROLE_ROLE_ANALYST` no funciona | Usar `hasRole()` con roles que ya tienen prefijo | Cambiar a `hasAuthority()` |

---

## Resumen: lo que hace Spring automáticamente

Cuando llega un request con `Authorization: Bearer <token>`:

1. `BearerTokenAuthenticationFilter` extrae el token del header
2. Descarga JWKS de Keycloak (cachea las keys)
3. Verifica firma RS256
4. Valida `exp` e `iss`
5. Llama a `KeycloakJwtConverter` → crea `JwtAuthenticationToken` con roles
6. Guarda en `SecurityContextHolder`
7. `SecurityFilterChain` evalúa reglas RBAC
8. Si pasa → llega al controller con `@AuthenticationPrincipal Jwt jwt` ya listo

**Todo esto en ~3 líneas de config** (`issuer-uri` + converter + filterChain).
