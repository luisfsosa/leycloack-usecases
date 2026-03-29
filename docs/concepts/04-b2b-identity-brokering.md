# B2B Pattern — Identity Brokering with Keycloak

> **Scenario:** A client company (e.g. Toyota) has its own IdP.
> Their employees must access Altana **without creating new credentials**.
> Keycloak acts as a **broker**: accepts the login from the external IdP and
> issues its own tokens for the Altana application.

---

## What is Identity Brokering?

```
Toyota User              Keycloak altana-dev         Toyota IDP
      │                         │                         │
      │── click "Login Toyota" ─►│                         │
      │                         │── redirect auth request ►│
      │◄── redirect to Toyota ──│                         │
      │                                                   │
      │── login credentials ──────────────────────────────►│
      │◄── authorization code ───────────────────────────── │
      │                                                   │
      │── code ────────────────►│                         │
      │                         │── exchange code ────────►│
      │                         │◄── id_token + access ───│
      │                         │                         │
      │                         │ [IDP mappers executed]  │
      │                         │ → email: john@toyota.com│
      │                         │ → role: ROLE_ANALYST    │
      │                         │                         │
      │◄── Altana tokens ───────│                         │
```

**Keycloak handles:**
- Delegated authentication to the external IdP
- Identity translation (IdP claims → Altana claims)
- Automatic role assignment based on the source IdP
- A single SSO session in Altana even though the user authenticated at Toyota

---

## Key concepts

### syncMode: IMPORT vs FORCE

| Mode | Behavior |
|------|---------|
| `IMPORT` | Copies attributes from the IdP **only on first login** (user creation in Altana) |
| `FORCE`  | Overwrites attributes on **every login** from the IdP (continuous sync) |

> **INTERVIEW:** "When would you use FORCE vs IMPORT?"
> → FORCE when the IdP is the source of truth (name, corporate email, roles).
>   IMPORT when Altana can modify attributes locally after the first login.

### kc_idp_hint

Parameter that tells Keycloak **which IDP to use directly**, skipping the
IDP selection screen.

```
&kc_idp_hint=toyota-corp
```

> In a B2B portal with per-company subdomain:
> `toyota.altana.com` → always adds `kc_idp_hint=toyota-corp`
> `ford.altana.com`   → always adds `kc_idp_hint=ford-corp`

---

## Full setup — step by step with Admin API

### 1. Create the "toyota-corp" realm (simulates the external IdP)

```bash
KC="http://localhost:8080"
ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s -X POST "$KC/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "realm": "toyota-corp",
    "displayName": "Toyota Corporation IDP",
    "enabled": true,
    "sslRequired": "external",
    "accessTokenLifespan": 300
  }'
```

### 2. Create test user in toyota-corp

```bash
curl -s -X POST "$KC/admin/realms/toyota-corp/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john.doe",
    "email": "john.doe@toyota.com",
    "firstName": "John",
    "lastName": "Doe",
    "enabled": true,
    "emailVerified": true
  }'

# Get user ID
TOYOTA_USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/toyota-corp/users?username=john.doe&exact=true" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")

# Set password
curl -s -X PUT "$KC/admin/realms/toyota-corp/users/$TOYOTA_USER_ID/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type": "password", "value": "Test1234!", "temporary": false}'
```

### 3. Create altana-broker client in toyota-corp

This client represents Altana as a consumer of the Toyota IdP.

```bash
curl -s -X POST "$KC/admin/realms/toyota-corp/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "altana-broker",
    "publicClient": false,
    "secret": "altana-broker-secret",
    "standardFlowEnabled": true,
    "redirectUris": ["http://localhost:8080/realms/altana-dev/broker/toyota-corp/endpoint"]
  }'
```

> The `redirectUri` is the Keycloak broker endpoint:
> `/realms/{consumer-realm}/broker/{idp-alias}/endpoint`
> Keycloak generates this automatically when you register the IDP.

### 4. Register toyota-corp as an IDP in altana-dev

```bash
curl -s -X POST "$KC/admin/realms/altana-dev/identity-provider/instances" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "toyota-corp",
    "displayName": "Login with Toyota",
    "providerId": "oidc",
    "enabled": true,
    "trustEmail": true,
    "config": {
      "issuer": "http://localhost:8080/realms/toyota-corp",
      "authorizationUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/auth",
      "tokenUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/token",
      "jwksUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/certs",
      "clientId": "altana-broker",
      "clientSecret": "altana-broker-secret",
      "defaultScope": "openid profile email",
      "validateSignature": "true",
      "useJwksUrl": "true",
      "syncMode": "FORCE"
    }
  }'
```

### 5. Add mappers to the IDP

#### Email mapper (sync email from IdP → Altana)
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "email mapper",
    "identityProviderMapper": "oidc-user-attribute-idp-mapper",
    "identityProviderAlias": "toyota-corp",
    "config": {
      "syncMode": "INHERIT",
      "claim": "email",
      "user.attribute": "email"
    }
  }'
```

#### Role mapper (all Toyota users → ROLE_ANALYST)
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "toyota analyst role",
    "identityProviderMapper": "oidc-role-idp-mapper",
    "identityProviderAlias": "toyota-corp",
    "config": {
      "syncMode": "INHERIT",
      "role": "ROLE_ANALYST"
    }
  }'
```

---

## Testing the full B2B flow

### Step 1 — Generate PKCE URL with kc_idp_hint

```bash
# Use the script included in the project:
python scripts/generate_b2b_url.py
```

The script generates:
- `code_verifier` and `code_challenge` (PKCE S256)
- `state` for anti-CSRF
- Complete URL with **all** parameters including `code_challenge_method=S256`

### Step 2 — Start capture server

```bash
# In a separate terminal:
python scripts/capture_callback.py
```

### Step 3 — Open the URL in the browser

The automatic flow:
1. Browser → Keycloak altana-dev (with `kc_idp_hint=toyota-corp`)
2. Keycloak **does not show** the IDP selection screen
3. Immediate redirect → Keycloak toyota-corp login page
4. User: `john.doe` / `Test1234!`
5. toyota-corp issues code → Keycloak altana-dev exchanges it
6. Mappers executed: email + ROLE_ANALYST assigned
7. altana-dev issues its own code → redirects to `localhost:3000/callback`
8. Capture server shows the `code`

### Step 4 — Exchange code → tokens

```bash
CODE=<captured code>

curl -s -X POST "http://localhost:8080/realms/altana-dev/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=altana-web" \
  -d "redirect_uri=http://localhost:3000/callback" \
  -d "code=$CODE" \
  -d "code_verifier=<verifier from script>" \
  | python3 -m json.tool
```

### Step 5 — Verify the Altana token

```bash
ACCESS_TOKEN="<token from previous step>"

echo $ACCESS_TOKEN | cut -d'.' -f2 | \
  python3 -c "import sys,base64,json; d=sys.stdin.read().strip(); d+='='*(4-len(d)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(d)), indent=2))"
```

**Expected payload:**
```json
{
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "realm_access": {
    "roles": ["ROLE_ANALYST", "offline_access", "default-roles-altana-dev"]
  },
  "iss": "http://localhost:8080/realms/altana-dev"
}
```

> The issuer is `altana-dev` — not `toyota-corp`. Keycloak issued its own tokens.
> The user was federated but the token is 100% Altana.

---

## What the Python backend sees

```bash
# Call /supply-chain/shipments with the Toyota user's token
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8002/supply-chain/shipments" | python3 -m json.tool
```

FastAPI validates:
1. Signature → JWKS from `altana-dev` (not from toyota-corp)
2. Issuer → `http://localhost:8080/realms/altana-dev`
3. Roles → `ROLE_ANALYST` ✓ → access granted

The backend **does not know** the user came from Toyota. For it, it's a normal
altana-dev user with ROLE_ANALYST.

---

## INTERVIEW: Common questions about B2B Identity Brokering

**"What happens if the external IdP goes down during a session?"**
→ The session in Altana stays active until the refresh token expires.
  The external IdP is only needed for the initial login.
  Once Keycloak has its own session, it is independent of the external IdP.

**"How do you handle multiple enterprise clients?"**
→ Each company gets its own IDP alias in altana-dev.
  `kc_idp_hint` is injected based on the subdomain or tenant ID.
  Role mappers can be per-IDP (Toyota → ROLE_ANALYST) or based on IdP claims.

**"What is a First Broker Login Flow?"**
→ When a federated user arrives for the first time, Keycloak can run a
  "first broker login flow" to: verify email, link to an existing account,
  or create a new account automatically. Configured on the IDP.

**"Difference between Identity Brokering and Federation?"**
→ Identity Brokering: Keycloak as broker between SPA and external IdP (the user
  has an account in the external IdP and Keycloak links it).
  Federation: Keycloak syncs a full directory (LDAP/AD) as if it were
  its own user database.

**"Why use syncMode=FORCE in B2B?"**
→ In B2B the external IdP is the source of truth (client's HR manages the users).
  FORCE guarantees that changes in the IdP (name, email, department) are reflected
  in Altana on the next login, without manual intervention.
