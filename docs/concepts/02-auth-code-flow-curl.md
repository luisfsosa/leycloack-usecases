# Authorization Code Flow + PKCE with curl

## The complete flow (diagram)

```
User        Browser/App         Keycloak              Your API
  |              |                   |                   |
  |--click-----→ |                   |                   |
  |              |--GET /auth?----→  |                   |
  |              |  client_id        |                   |
  |              |  code_challenge   |                   |
  |←--login UI---|←--302 redirect--- |                   |
  |--user+pass-→ |--POST login-----→ |                   |
  |              |←--302 +code------ |                   |
  |              |--POST /token---→  |                   |
  |              |  code             |                   |
  |              |  code_verifier    |                   |
  |              |←--access_token--- |                   |
  |              |--GET /resource + Bearer token-------→ |
  |              |←--200 data--------------------------------|
```

## STEP 1 — Generate PKCE (code_verifier + code_challenge)

PKCE protects the Authorization Code against interception.
- `code_verifier`: random string (43–128 chars, URL-safe)
- `code_challenge`: BASE64URL(SHA256(code_verifier))

```bash
# Generate code_verifier (32 bytes random → base64url)
CODE_VERIFIER=$(python3 -c "
import secrets, base64
verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode()
print(verifier)
")
echo "CODE_VERIFIER: $CODE_VERIFIER"

# Generate code_challenge = BASE64URL(SHA256(verifier))
CODE_CHALLENGE=$(python3 -c "
import hashlib, base64, sys
verifier = '$CODE_VERIFIER'
digest = hashlib.sha256(verifier.encode()).digest()
challenge = base64.urlsafe_b64encode(digest).rstrip(b'=').decode()
print(challenge)
")
echo "CODE_CHALLENGE: $CODE_CHALLENGE"
```

## STEP 2 — Build the Authorization URL and open in browser

```bash
KEYCLOAK_URL="http://localhost:8080"
REALM="altana-dev"
CLIENT_ID="altana-web"
REDIRECT_URI="http://localhost:3000/callback"
SCOPE="openid profile email"

AUTH_URL="${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/auth?\
client_id=${CLIENT_ID}&\
response_type=code&\
scope=$(echo $SCOPE | sed 's/ /+/g')&\
redirect_uri=${REDIRECT_URI}&\
code_challenge=${CODE_CHALLENGE}&\
code_challenge_method=S256&\
state=random-state-123"

echo "Open this URL in the browser:"
echo $AUTH_URL
```

Keycloak redirects to: `http://localhost:3000/callback?code=XXXX&state=random-state-123`
→ Copy the `code=` value from the URL (it expires in ~60 seconds)

## STEP 3 — Exchange the code for tokens

```bash
CODE="PASTE-CODE-HERE"

curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=${CLIENT_ID}" \
  -d "code=${CODE}" \
  -d "redirect_uri=${REDIRECT_URI}" \
  -d "code_verifier=${CODE_VERIFIER}" \
  | python3 -m json.tool
```

### Expected response:
```json
{
  "access_token": "eyJhbGci...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGci...",
  "token_type": "Bearer",
  "id_token": "eyJhbGci...",
  "scope": "openid profile email"
}
```

## STEP 4 — Inspect the JWT (without a library)

A JWT has 3 parts: HEADER.PAYLOAD.SIGNATURE

```bash
ACCESS_TOKEN="PASTE-ACCESS-TOKEN-HERE"

# Extract and decode the PAYLOAD (middle part)
echo $ACCESS_TOKEN | cut -d'.' -f2 | \
  python3 -c "
import sys, base64, json
payload = sys.stdin.read().strip()
# Add padding if missing
padding = 4 - len(payload) % 4
if padding != 4:
    payload += '=' * padding
decoded = base64.urlsafe_b64decode(payload)
print(json.dumps(json.loads(decoded), indent=2))
"
```

### Key claims you will see:
```json
{
  "exp": 1735689600,        ← UTC Unix timestamp (INTERVIEW: no timezone)
  "iat": 1735686000,        ← Issued at
  "iss": "http://localhost:8080/realms/altana-dev",  ← Issuer
  "sub": "f47ac10b-...",    ← Subject (user ID in Keycloak)
  "aud": "altana-web",      ← Audience
  "typ": "Bearer",
  "azp": "altana-web",      ← Authorized party (client that requested the token)
  "realm_access": {
    "roles": ["ROLE_USER", "ROLE_ADMIN"]   ← User roles
  },
  "email": "admin@altana.dev",
  "name": "Admin User"
}
```

## STEP 5 — Renew with Refresh Token

```bash
REFRESH_TOKEN="PASTE-REFRESH-TOKEN-HERE"

curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token" \
  -d "client_id=${CLIENT_ID}" \
  -d "refresh_token=${REFRESH_TOKEN}" \
  | python3 -m json.tool
```

## STEP 6 — Client Credentials (service-to-service, no user)

```bash
# supply-chain-backend is a confidential client (has a secret)
curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=CHANGE-ME-IN-PRODUCTION" \
  | python3 -m json.tool
```

## STEP 7 — Token introspection (verify if a token is valid)

```bash
curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token/introspect" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "supply-chain-backend:CHANGE-ME-IN-PRODUCTION" \
  -d "token=${ACCESS_TOKEN}" \
  | python3 -m json.tool
# "active": true  → valid token
# "active": false → expired or invalid
```

## Common errors and how to diagnose them

| HTTP Error | Message | Cause |
|-----------|---------|-------|
| 400 | `invalid_grant` | Code already used or expired (>60 s) |
| 400 | `invalid_grant` | `code_verifier` does not match `code_challenge` |
| 400 | `invalid_client` | Incorrect `client_id` |
| 401 | `unauthorized_client` | Client does not have the flow enabled |
| 400 | `redirect_uri_mismatch` | `redirect_uri` not in the allowed list |

## Discovery Document (auto-configuration)

Keycloak publishes all its endpoints at:
```
GET http://localhost:8080/realms/altana-dev/.well-known/openid-configuration
```

```bash
curl -s http://localhost:8080/realms/altana-dev/.well-known/openid-configuration \
  | python3 -m json.tool | head -40
```

Returns: authorization_endpoint, token_endpoint, jwks_uri, etc.
Frameworks (Spring Security, FastAPI) use this URL to auto-configure themselves.
