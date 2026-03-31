# Altana – Keycloak Learning & Integration Project

## Claude's role in this project
You act as **expert architect + teacher** in Keycloak, IAM, OAuth2/OIDC, and security.
- Explain concepts while writing code
- Point out what the user learns at each step
- Highlight interview questions related to each topic
- Ultimate goal: prepare the user for a Keycloak + Python role at a supply-chain analytics company

## CRITICAL RULE — Never assume, always verify against the version in use

**Keycloak changes APIs, provider names, and behaviors between versions.**
Before using any mapper type, endpoint, configuration, or Keycloak feature:

1. **Verify available types via the API** — do not assume they exist based on generic documentation:
   ```bash
   # Available mapper types for an IDP:
   GET /admin/realms/{realm}/identity-provider/instances/{alias}/mapper-types

   # Available authenticator providers:
   GET /admin/realms/{realm}/authentication/authenticator-providers

   # Protocol mapper types:
   GET /admin/realms/{realm}/protocol-mappers/providers-per-protocol
   ```

2. **Consult the documentation for the exact version** — the version used in this project is
   **Keycloak 26.5.6**. Documentation:
   - REST API: `https://www.keycloak.org/docs-api/26.5/rest-api/`
   - Administration guide: `https://www.keycloak.org/docs/26.5/server_admin/`
   - Migration guide (changes between versions): `https://www.keycloak.org/docs/26.5/upgrading/`

3. **Lesson learned — real case in this project:**
   - `hardcoded-role-idp-mapper` → does NOT exist in Keycloak 26
   - `oidc-hardcoded-role-idp-mapper` → correct name in Keycloak 26
   - The `role` config accepts the role **name**, not the UUID
   - These errors cost multiple failed attempts
   - **Always query `mapper-types` before creating a mapper via API**

---

---

## Project structure

```
altana/
├── spring-app/          # USE CASE 1: Spring Boot + Keycloak as IdP
├── keycloak-extension/  # USE CASE 2: Keycloak SPI (custom authenticator, listener, mapper)
├── python-app/          # USE CASE 3: Python/FastAPI + Keycloak (main focus)
├── docker/              # Keycloak + PostgreSQL local dev
└── docs/concepts/       # Theoretical explanations and cheat sheets
```

---

## Use cases to build (in order)

### UC1 – Spring Boot as Resource Server
- Spring Boot app protected by Keycloak (OAuth2 Resource Server)
- Validates JWT tokens issued by Keycloak
- Demonstrates: token validation, role-based access, scope handling

### UC2 – Keycloak SPI Extension
- Custom Authenticator (custom login flow)
- Event Listener (audit of login/logout events)
- Protocol Mapper (adding custom claims to the JWT)
- Demonstrates: Keycloak extensibility at the SPI level

### UC3 – Python/FastAPI + Keycloak (MAIN FOCUS)
- FastAPI protected via Bearer token
- PKCE flow for frontend/mobile
- Client Credentials for service-to-service
- B2B and B2B2C patterns
- Demonstrates: real Python integration as in the target role

---

## Security rules (NEVER violate)

### Secrets and credentials
- NEVER hardcode client_secret in code or git
- Use environment variables: `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_URL`
- In production: use HashiCorp Vault or Azure KeyVault for secrets
- The `.env` file is NEVER committed (already in .gitignore)

### Keycloak client configuration
- Public clients (frontend/mobile): ALWAYS use PKCE (`S256`)
- Confidential clients (backend): ALWAYS use client_secret + mTLS if possible
- NEVER enable `Direct Access Grants` (Resource Owner Password) in production
- ALWAYS configure `Valid Redirect URIs` with exact URLs (no wildcards in prod)
- ALWAYS configure `Web Origins` for CORS

### JWT tokens
- Access token lifetime: maximum 5 minutes in production
- Refresh token lifetime: depends on use case (typical: 30 min to 8 hours)
- NEVER store tokens in localStorage (use httpOnly cookies)
- ALWAYS validate: signature, `exp`, `iss`, `aud`
- The `exp` field in JWT uses **UTC Unix timestamp** (no timezone, it is UTC)

### SSL/TLS
- NEVER disable SSL verification in production
- In local development: use `verify=False` only with an explicit flag and comment
- In Keycloak: configure HTTPS in the realm (not just in the proxy)

---

## Naming conventions

### Realms
- Format: `{client}-{environment}` → `altana-dev`, `altana-prod`
- One realm per client in B2B (tenant isolation)
- `master` realm NEVER for applications (admin only)

### Clients
- Backend services: `{service}-backend` → `supply-chain-backend`
- Frontend apps: `{app}-web` → `altana-web`
- Service accounts: `{service}-sa` → `notification-sa`
- Mobile: `{app}-mobile` → `altana-mobile`

### Roles
- Realm roles (global): `ROLE_ADMIN`, `ROLE_USER`
- Client roles (specific): `supply-chain:read`, `supply-chain:write`
- Groups for bulk assignment, roles for specific permissions

---

## OAuth2 flows – when to use each

| Flow | When to use | Example |
|------|-------------|---------|
| **Authorization Code + PKCE** | Frontend, mobile, any public client | React app, mobile app |
| **Authorization Code** (confidential) | Web app with its own backend | Spring Boot MVC |
| **Client Credentials** | Service-to-service, no user | Microservice A → B |
| **Device Code** | Devices without a browser | Smart TV, CLI tools |
| **Refresh Token** | Renew access token without re-authenticating | All flows above |
| ~~Resource Owner Password~~ | **NEVER in production** | Internal testing only |

---

## Common interview questions (practice answers)

### Protocols
- What is the difference between OAuth2 and OIDC?
  → OAuth2 is authorization (what you can do), OIDC is authentication (who you are). OIDC adds `id_token` (JWT) and the `/userinfo` endpoint on top of OAuth2.
- When do you use SAML vs OIDC?
  → SAML: enterprise legacy, XML-based corporate SSO. OIDC: modern, JSON/JWT, better for APIs and mobile.

### JWT
- What timezone does the `exp` field in a JWT use?
  → UTC Unix timestamp (seconds since 1970-01-01 00:00:00 UTC). It has no timezone.
- How do you debug a JWT?
  → `jwt.io` or `python-jose`/`PyJWT` for decode. Verify: signature, exp, iss, aud.
- How do you troubleshoot an expired JWT in curl?
  → Inspect the 401, then `curl` the `/token` endpoint with the refresh_token to renew.

### Keycloak
- What is an SPI in Keycloak?
  → Service Provider Interface — Keycloak's extension mechanism. Allows custom authenticators, event listeners, protocol mappers.
- How do you configure B2B in Keycloak?
  → Identity Brokering: master realm federates external client realms via OIDC/SAML. Each B2B client has its own realm or IDP.

---

## Common development commands

### Docker – start local environment
```bash
cd docker && docker compose up -d
# Keycloak: http://localhost:8080 (admin/admin)
# PostgreSQL: localhost:5432
```

### Troubleshoot Authorization Code Flow with curl
```bash
# Step 1: get authorization code (open in browser)
# http://localhost:8080/realms/{realm}/protocol/openid-connect/auth?
#   client_id=altana-web&response_type=code&scope=openid&
#   redirect_uri=http://localhost:3000/callback&code_challenge=XXX&code_challenge_method=S256

# Step 2: exchange code for token
curl -X POST http://localhost:8080/realms/{realm}/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=altana-web" \
  -d "code={CODE}" \
  -d "redirect_uri=http://localhost:3000/callback" \
  -d "code_verifier={VERIFIER}"

# Client Credentials (service-to-service)
curl -X POST http://localhost:8080/realms/{realm}/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=${KEYCLOAK_CLIENT_SECRET}"

# Inspect token (decode base64)
echo "{JWT_PAYLOAD}" | base64 -d | python -m json.tool
```

### Python – verify token
```python
# Install: pip install python-jose[cryptography] httpx
from jose import jwt
import httpx

# Get JWKS from Keycloak
KEYCLOAK_URL = "http://localhost:8080"
REALM = "altana-dev"
jwks = httpx.get(f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/certs").json()

# Validate token
payload = jwt.decode(token, jwks, algorithms=["RS256"], audience="altana-web")
```

---

## B2B and B2B2C patterns

### B2B (business-to-business)
- Each enterprise client has its own Identity Provider (Okta, Azure AD, etc.)
- Keycloak acts as an **Identity Broker**: federates external IDPs
- Configure: `Identity Providers` → client's OIDC/SAML → map attributes to local roles
- SSL: always use valid certificates on the external IDP
- KeyVault: Azure KeyVault can be integrated to manage SAML/SSL certificates via admin scripts

### B2B2C (business → business → end consumer)
- Layer 1: enterprise client authenticates with its corporate IDP (B2B)
- Layer 2: end users of the enterprise client use Keycloak login
- Implement with: Organization feature (Keycloak 24+) or multiple realms
- Requires: role mapping between layers, token enrichment via protocol mapper

---

## Project technology stack

| Module | Stack | Version |
|--------|-------|---------|
| Keycloak | Docker (quay.io/keycloak/keycloak) | 26.5.6 |
| spring-app | Java 21, Spring Boot, Spring Security OAuth2 | Latest |
| keycloak-extension | Java 21, Keycloak SPI API | 26.5.6 |
| python-app | Python 3.12, FastAPI, python-jose, httpx | Latest |
| DB | PostgreSQL | 16 |

---

## Recommended learning order

1. **Start Keycloak locally** → understand the admin UI, create realm, client, user
2. **Authorization Code Flow with curl** → understand the complete flow by hand
3. **UC3 Python/FastAPI** → protect endpoints with JWT (main focus)
4. **JWT deep dive** → decode, validation, claims, expiry
5. **UC1 Spring Boot** → Resource Server, role-based access
6. **B2B/B2B2C patterns** → Identity Brokering in Keycloak
7. **UC2 SPI Extension** → custom authenticator and protocol mapper
8. **SSL and production** → HTTPS, certificates, KeyVault integration
