# B2B2C Pattern — Multi-Tenant with Keycloak and FastAPI

> **Scenario:** Toyota (Altana's enterprise client) has two types of users:
> - **Internal employees** (`user_type=employee`) who analyze supply chain data
> - **End customers** (`user_type=customer`) who only see their own shipments
>
> Both authenticate against the Toyota IdP, but have different access in Altana.

---

## What is B2B2C?

```
Altana (SaaS)
  └── Toyota (B2B client)
        ├── john.doe       → internal employee → ROLE_ANALYST → sees everything
        └── jane.consumer  → end customer      → ROLE_VIEWER  → sees only their tenant
```

**B2B** = Toyota authenticates against Altana via Identity Brokering
**B2B2C** = Toyota's end users (consumers) also use Altana, but with access restricted to the Toyota tenant

---

## Architecture of the flow

```
jane.consumer            Keycloak altana-dev         Toyota IDP (toyota-corp)
      │                         │                           │
      │── login "Toyota" ──────►│                           │
      │                         │── redirect ──────────────►│
      │◄── redirect to Toyota ──│                           │
      │                                                     │
      │── credentials ──────────────────────────────────────►│
      │◄── code ────────────────────────────────────────────│
      │                                                     │
      │── code ────────────────►│                           │
      │                         │── exchange code ─────────►│
      │                         │◄── id_token               │
      │                         │    (sub, email,           │
      │                         │     user_type=customer)   │
      │                         │                           │
      │                         │ [IDP Mappers executed]    │
      │                         │  sync user_type=customer  │
      │                         │  → assign ROLE_VIEWER     │
      │                         │  → set tenant_id=toyota   │
      │                         │                           │
      │                         │ [Protocol Mappers]        │
      │                         │  → add user_type to JWT   │
      │                         │  → add tenant_id to JWT   │
      │                         │                           │
      │◄── Altana JWT ──────────│                           │
      │    roles: [ROLE_VIEWER] │                           │
      │    tenant_id: toyota    │                           │
      │    user_type: customer  │                           │
```

---

## Keycloak Configuration

### 1. Toyota IDP (realm `toyota-corp`) — User Profile

Keycloak 24+ requires declaring custom attributes in User Profile before using them.
Without this declaration, the attribute is silently ignored.

```
Admin → toyota-corp → Realm Settings → User Profile
→ Add attribute: user_type (String, not required)
```

```json
// PUT /admin/realms/toyota-corp/users/profile
{
  "attributes": [
    { "name": "username", ... },
    { "name": "email", ... },
    {
      "name": "user_type",
      "displayName": "User Type",
      "validations": {},
      "permissions": { "view": ["admin", "user"], "edit": ["admin"] }
    }
  ]
}
```

### 2. Protocol Mapper on `altana-broker` client (toyota-corp)

For `user_type` to appear in the token that toyota-corp issues toward altana-dev:

```
toyota-corp → Clients → altana-broker → Client Scopes → Mappers
→ Add mapper: user_type claim
  Type: User Attribute
  User Attribute: user_type
  Token Claim Name: user_type
  Add to access token: ON
```

### 3. IDP Mappers in altana-dev (for toyota-corp IDP)

Five mappers process the toyota-corp token when a user arrives:

| Mapper | Type | What it does |
|--------|------|--------------|
| `email mapper` | `oidc-user-attribute-idp-mapper` | Copies email from IdP to local user |
| `sync user_type attribute` | `oidc-user-attribute-idp-mapper` | Copies `user_type` claim as attribute |
| `employee gets ANALYST` | `oidc-advanced-role-idp-mapper` | If `user_type=employee` → assigns ROLE_ANALYST |
| `customer gets VIEWER` | `oidc-advanced-role-idp-mapper` | If `user_type=customer` → assigns ROLE_VIEWER |
| `tenant_id toyota` | `hardcoded-attribute-idp-mapper` | Always assigns `tenant_id=toyota` |

**Critical configuration — `syncMode: FORCE`**

```json
// mapper employee gets ANALYST
{
  "identityProviderMapper": "oidc-advanced-role-idp-mapper",
  "config": {
    "claims": "[{\"key\": \"user_type\", \"value\": \"employee\"}]",
    "role": "ROLE_ANALYST",
    "syncMode": "FORCE"
  }
}
```

> **IMPORT vs FORCE:**
> - `IMPORT`: the mapper only runs the first time the user arrives (new user)
> - `FORCE`: the mapper runs on every broker login, updating attributes and roles
>
> Using `FORCE` is safer: if a user changes from `employee` to `customer`,
> their roles are updated on the next login.

### 4. Protocol Mappers on `altana-web` client (altana-dev)

For `tenant_id` and `user_type` to reach the final JWT that FastAPI receives:

```
altana-dev → Clients → altana-web → Client Scopes → Mappers
→ user_type claim:  User Attribute "user_type"  → claim "user_type"
→ tenant_id claim:  User Attribute "tenant_id"  → claim "tenant_id"
```

---

## The resulting JWT

### john.doe (employee)
```json
{
  "sub": "...",
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "realm_access": {
    "roles": ["ROLE_ANALYST", "default-roles-altana-dev"]
  },
  "tenant_id": "toyota",
  "user_type": "employee"
}
```

### jane.consumer (customer)
```json
{
  "sub": "...",
  "preferred_username": "jane.consumer",
  "email": "jane.consumer@toyota.com",
  "realm_access": {
    "roles": ["ROLE_VIEWER", "default-roles-altana-dev"]
  },
  "tenant_id": "toyota",
  "user_type": "customer"
}
```

---

## FastAPI implementation

### TokenData — the B2B2C fields

```python
@dataclass
class TokenData:
    sub: str
    username: str
    email: str | None
    roles: list[str]
    tenant_id: str | None  # "toyota" | "ford" | None
    user_type: str | None  # "employee" | "customer" | None
    raw: dict
```

### Tenant filtering in the endpoint

```python
@router.get("/my-shipments")
async def my_shipments(
    user: TokenData = Depends(require_role("ROLE_VIEWER", "ROLE_ANALYST", "ROLE_ADMIN"))
):
    all_shipments = [
        {"id": "SH-001", "tenant_id": "toyota", ...},
        {"id": "SH-002", "tenant_id": "toyota", ...},
        {"id": "SH-003", "tenant_id": "ford",   ...},
        {"id": "SH-004", "tenant_id": "toyota", ...},
    ]

    # ROLE_VIEWER can only see their own tenant
    if "ROLE_VIEWER" in user.roles \
       and "ROLE_ANALYST" not in user.roles \
       and "ROLE_ADMIN" not in user.roles:
        tenant = user.tenant_id or ""
        return {
            "filter": f"tenant={tenant}",
            "shipments": [s for s in all_shipments if s["tenant_id"] == tenant]
        }

    # ANALYST / ADMIN see everything
    return {"filter": "none (full access)", "shipments": all_shipments}
```

**Key principle:** `tenant_id` comes from the JWT — already cryptographically validated by FastAPI.
The backend does not need to query Keycloak to know which company the user belongs to.

---

## Test results

```
GET /supply-chain/my-shipments

john.doe   (ROLE_ANALYST)  → filter: "none (full access)" → 4 shipments (toyota + ford)
jane.consumer (ROLE_VIEWER) → filter: "tenant=toyota"      → 3 shipments (toyota only)

GET /supply-chain/shipments  (ANALYST/ADMIN only)

jane.consumer (ROLE_VIEWER) → 403 Forbidden
  "Required one of these roles: ['ROLE_ANALYST', 'ROLE_ADMIN']"
```

---

## Lessons learned (real errors in this project)

### 1. Keycloak 24+ User Profile blocks undeclared attributes

**Symptom:** `user_type` was saved on the toyota-corp user without error,
but when reading the user via API the attribute did not appear.

**Cause:** Keycloak 24 introduced User Profile. Undeclared attributes
are silently ignored when saving via Admin API.

**Fix:** Declare the attribute in `PUT /admin/realms/{realm}/users/profile`
before trying to use it.

### 2. syncMode IMPORT does not re-run mappers on existing users

**Symptom:** already imported users were not receiving new attributes/roles
even though the mappers were correctly configured.

**Cause:** `syncMode: IMPORT` only runs the first time the user
arrives via the broker (first login). If the user already exists, the mapper does not run.

**Fix:** Switch to `syncMode: FORCE`. Or delete the federated user
from altana-dev to force a clean re-import.

### 3. oidc-advanced-role-idp-mapper — `claims` config format

The `claims` field expects a JSON array serialized as a string:

```json
// CORRECT
"claims": "[{\"key\": \"user_type\", \"value\": \"employee\"}]"

// WRONG (direct object)
"claims": {"key": "user_type", "value": "employee"}
```

### 4. Always verify mapper types via API before creating mappers

```bash
GET /admin/realms/{realm}/identity-provider/instances/{alias}/mapper-types
```

Type names change between Keycloak versions.
In Keycloak 26: `oidc-advanced-role-idp-mapper` (not `advanced-role-idp-mapper`).

---

## Production pattern

In production, tenant filtering happens at the database layer:

```python
# Instead of in-memory filtering:
shipments = db.query(Shipment).filter(
    Shipment.tenant_id == user.tenant_id  # tenant from JWT
).all()
```

The `tenant_id` from the JWT is trustworthy because:
1. The JWT was signed by Keycloak (cryptographic verification)
2. The `tenant_id` was assigned by a `hardcoded-attribute-idp-mapper` in Keycloak
   (the user cannot change it)
3. FastAPI validates the signature before extracting any claim

**Interview question:** How do you prevent a Toyota user from seeing Ford data?
> The `tenant_id` comes in the JWT signed by Keycloak. The Resource Server (FastAPI)
> validates the signature and extracts the tenant. All DB queries include
> `WHERE tenant_id = :tenant_id` with the value from the token. The user can never
> manipulate their own tenant_id because that would require forging the JWT.
