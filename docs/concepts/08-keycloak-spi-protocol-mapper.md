# UC2 — Keycloak SPI: Custom Protocol Mapper

## What you learn here

How to extend Keycloak with Java code to add custom claims to JWT tokens.
This is the most common SPI extension in enterprise Keycloak deployments.

---

## The problem this solves

Keycloak's built-in mappers handle standard user attributes, but sometimes you need:
- Custom business logic when building the token (transformations, lookups)
- Claims derived from multiple user attributes
- Claims that require external data enrichment

A Protocol Mapper SPI gives you full control over what goes into the JWT.

---

## How it fits into the token flow

```
User authenticates → Keycloak builds the JWT
    │
    ├─ Standard claims (sub, iss, exp, ...)
    ├─ realm_access.roles
    └─ For each mapper configured on the client:
           mapper.setClaim(token, ...)   ← your Java code runs here
    │
    └─ Keycloak signs the JWT and returns it
```

---

## Two ways to add custom claims

| Approach | When to use |
|----------|-------------|
| **UI mapper** (user-attribute type) | Simple: read user attribute → add as claim |
| **SPI mapper** (Java code) | Complex: transformations, conditions, external lookups |

---

## Project structure

```
keycloak-extension/
├── build.gradle
├── gradle.properties
└── src/main/
    ├── java/com/altana/keycloak/mapper/
    │   └── TenantIdMapper.java
    └── resources/META-INF/services/
        └── org.keycloak.protocol.ProtocolMapper   ← ServiceLoader registry
```

---

## build.gradle — dependencies

```groovy
dependencies {
    // All Keycloak deps are compileOnly — they are provided by the server at runtime.
    // Including them in the JAR would cause ClassLoader conflicts.
    compileOnly 'org.keycloak:keycloak-core:26.5.6'
    compileOnly 'org.keycloak:keycloak-server-spi:26.5.6'
    compileOnly 'org.keycloak:keycloak-server-spi-private:26.5.6'
    // AbstractOIDCProtocolMapper and OIDCAttributeMapperHelper live here
    compileOnly 'org.keycloak:keycloak-services:26.5.6'
}
```

> **Interview:** Why `compileOnly` and not `implementation` for a Keycloak extension?
> → Keycloak provides these classes at runtime. Including them in your JAR causes
>   ClassLoader conflicts — two copies of the same class loaded by different classloaders.
>   It's the same concept as `provided` scope in Maven.

---

## Class hierarchy

```
AbstractOIDCProtocolMapper  (Keycloak base class)
    └── TenantIdMapper
          ├── implements OIDCAccessTokenMapper  → adds claim to access_token
          └── implements OIDCIDTokenMapper      → adds claim to id_token

Note (Keycloak 26): OIDCUserInfoMapper was removed.
Userinfo inclusion is now controlled via the "Add to userinfo" checkbox,
which OIDCAttributeMapperHelper.addIncludeInTokensConfig() adds automatically.
```

---

## TenantIdMapper.java — annotated

```java
public class TenantIdMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "altana-tenant-id-mapper";

    // Declares the config fields shown in Keycloak Admin UI when setting up the mapper:
    //   - "Token Claim Name" → name of the claim in the JWT (e.g. "tenant_id")
    //   - "Add to access token" → checkbox
    //   - "Add to ID token" → checkbox
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, TenantIdMapper.class);
    }

    @Override public String getId()              { return PROVIDER_ID; }
    @Override public String getDisplayCategory() { return TOKEN_MAPPER_CATEGORY; }
    @Override public String getDisplayType()     { return "Altana Tenant ID Mapper"; }
    @Override public String getHelpText()        { return "Maps 'tenant_id' user attribute to a JWT claim."; }
    @Override public List<ProviderConfigProperty> getConfigProperties() { return configProperties; }

    @Override
    protected void setClaim(IDToken token,
                            ProtocolMapperModel mappingModel,
                            UserSessionModel userSession,
                            KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        // Read the user attribute from Keycloak's user store
        String tenantId = userSession.getUser().getFirstAttribute("tenant_id");

        if (tenantId == null || tenantId.isBlank()) {
            return;  // no claim added if attribute is missing
        }

        // mapClaim reads the claim name from mappingModel ("Token Claim Name" config field)
        // Supports dot notation: "org.tenant" → nested claim
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, tenantId);
    }
}
```

> **Interview:** How do you access user data inside a Protocol Mapper?
> → `userSession.getUser()` returns the `UserModel`.
>   `getFirstAttribute("name")` reads custom user attributes.
>   `getEmail()`, `getUsername()` for standard fields.
>   `keycloakSession` gives access to all Keycloak services (DB, other providers, etc).

---

## ServiceLoader registration

```
META-INF/services/org.keycloak.protocol.ProtocolMapper
└── com.altana.keycloak.mapper.TenantIdMapper
```

Keycloak uses `java.util.ServiceLoader` to discover providers at startup.
Without this file, the JAR exists but Keycloak never loads the mapper.

> **Interview:** How does Keycloak discover custom providers?
> → Via Java's `ServiceLoader` mechanism. Each SPI type has a corresponding
>   file under `META-INF/services/`. Keycloak scans all JARs in
>   `/opt/keycloak/providers/` and loads every class listed in those files.

---

## Declarative User Profile (Keycloak 24+)

In Keycloak 24+, user attributes must be declared in the realm's User Profile schema
before they can be set. Without this, `PUT /users/{id}` with custom attributes
returns HTTP 204 but silently discards the values.

```bash
# Add tenant_id to the realm schema via Admin API
GET /admin/realms/{realm}/users/profile    # get current schema
PUT /admin/realms/{realm}/users/profile    # add your attribute to the list
```

Schema entry:
```json
{
  "name": "tenant_id",
  "displayName": "Tenant ID",
  "permissions": { "view": ["admin", "user"], "edit": ["admin"] },
  "multivalued": false
}
```

> **Interview:** Why would setting user attributes via Admin API return 204 but not save?
> → Keycloak 24+ introduced Declarative User Profile. Attributes not declared
>   in the realm schema are silently ignored. Always declare custom attributes
>   in the User Profile configuration before trying to set them on users.

---

## Deployment

```bash
# 1. Build
./gradlew jar
# Output: build/libs/altana-keycloak-extension-1.0.0.jar

# 2. Copy to providers directory
cp build/libs/*.jar docker/keycloak/providers/

# 3. Recreate container (new volume mount requires container recreation, not just restart)
docker compose up -d --force-recreate keycloak

# 4. Verify it loaded
docker logs altana-keycloak 2>&1 | grep "altana-tenant-id-mapper"
# Expected: KC-SERVICES0047: altana-tenant-id-mapper ... is implementing the internal SPI
```

---

## Configure in Keycloak Admin UI

1. **Clients** → `supply-chain-backend` → **Client scopes** tab
2. Click `supply-chain-backend-dedicated` → **Add mapper** → **By configuration**
3. Select **Altana Tenant ID Mapper**
4. Set **Token Claim Name** = `tenant_id`
5. Enable **Add to access token** and **Add to ID token**
6. Save

---

## Verify in the token

```bash
# Get a token for a user with tenant_id attribute set
TOKEN=$(curl -s -X POST http://localhost:8080/realms/altana-dev/protocol/openid-connect/token \
  -d "grant_type=password&client_id=altana-web&username=analyst-user&password=Analyst123!" \
  | python -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Decode the payload
echo $TOKEN | cut -d'.' -f2 | python -c "
import sys, base64, json
p = sys.stdin.read().strip()
p += '=' * (4 - len(p) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)), indent=2))
" | grep -A1 "tenant_id\|user_type"
```

Expected output:
```json
"tenant_id": "toyota",
"user_type": "employee"
```

---

## End-to-end flow

```
User attribute in Keycloak: tenant_id = "toyota"
    │
    └─ TenantIdMapper.setClaim()          ← your Java code
         │
         └─ JWT: { "tenant_id": "toyota" }   ← Keycloak signs it
              │
              └─ Spring Boot / FastAPI reads jwt.getClaim("tenant_id")
                   │
                   └─ Filter DB queries: WHERE tenant_id = 'toyota'
                        (multi-tenancy enforced at the API layer)
```

---

## Interview questions

**Q: What is a Keycloak SPI?**
> Service Provider Interface — Keycloak's extension mechanism. You implement a Java
> interface, package it as a JAR, place it in `/opt/keycloak/providers/`, and Keycloak
> discovers it via `ServiceLoader`. SPIs exist for: authenticators, protocol mappers,
> event listeners, user storage, email, and more.

**Q: What is the difference between a UI mapper and an SPI mapper?**
> A UI mapper (e.g. `user-attribute` type) is configured entirely through the Admin UI —
> it reads a user attribute and adds it as a claim with no custom code.
> An SPI mapper runs Java code, allowing transformations, conditions, data enrichment,
> or reading from external systems. Use UI mappers when simple attribute → claim
> mapping is sufficient; use SPI when you need logic.

**Q: Why does `setClaim()` use `OIDCAttributeMapperHelper.mapClaim()` instead of setting the claim directly on the token?**
> `mapClaim()` reads the claim name from the mapper configuration (`mappingModel`),
> so the admin can change the claim name in the UI without modifying the code.
> It also supports dot notation for nested claims (`org.tenant` → `{"org": {"tenant": "..."}}`)
> and respects the "Add to access token / id token / userinfo" checkboxes.
