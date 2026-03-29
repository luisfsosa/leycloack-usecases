# OAuth2 vs OIDC vs SAML — Fundamentals

> **INTERVIEW:** "What is the difference between OAuth2 and OIDC?" — almost guaranteed question

## OAuth2 — Authorization (what can you do?)

OAuth2 is NOT an authentication protocol. It is an **authorization delegation** framework.
The Access Token says: "this user authorized this app to do X on their behalf".

```
User → App → "Permission to access your email" → Google → Access Token
```

The Access Token is **opaque** to the app (only the Resource Server understands it).

## OIDC — Authentication (who are you?)

OpenID Connect is an **identity layer on top of OAuth2**.
It adds the `id_token` (JWT) which contains user information (claims).

```
OAuth2:  Access Token (authorization)
OIDC:    Access Token + ID Token (authentication) + /userinfo endpoint
```

### Standard ID Token claims:
| Claim | Description | Example |
|-------|-------------|---------|
| `sub` | Subject — unique user ID | "f47ac10b-..." |
| `iss` | Issuer — who issued the token | "http://localhost:8080/realms/altana-dev" |
| `aud` | Audience — who the token is for | "altana-web" |
| `exp` | Expiration — Unix timestamp UTC | 1735689600 |
| `iat` | Issued at — when it was issued | 1735686000 |
| `email` | User email | "user@altana.dev" |
| `name` | Full name | "Felipe Sosa" |

## SAML — The enterprise legacy

SAML 2.0 predates OAuth2. It uses XML instead of JSON.
Still widely used in large enterprises (Salesforce, Azure AD enterprise).

| | OAuth2/OIDC | SAML |
|--|------------|------|
| Format | JSON/JWT | XML |
| Best for | APIs, mobile, SPA | Enterprise SSO |
| Complexity | Lower | Higher (XML, XML signatures) |
| Speed | Faster | Slower |

### When you use SAML in Keycloak:
- Integrating with enterprise client systems (Okta, ADFS, legacy Azure AD)
- The B2B client requires SAML (many large companies mandate it)
- Identity Brokering: Keycloak receives SAML assertions and issues OIDC tokens

## OAuth2 flows

### 1. Authorization Code + PKCE (the most important)
```
App → /auth?code_challenge=XXX → Keycloak (login) → code → App → /token?code+verifier → Token
```
- **PKCE** (Proof Key for Code Exchange): protects against code interception
- The `code_verifier` is a random string; `code_challenge = SHA256(verifier)`
- **Always use in**: SPA, mobile, any public client

### 2. Client Credentials (service-to-service)
```
ServiceA → /token?client_id+client_secret → Token → calls ServiceB
```
- No user involved
- The token represents the service, not a user
- **Use in**: microservices, batch jobs, automated scripts

### 3. Device Code (limited-input devices)
```
Smart TV → /device → {device_code, user_code} → user visits URL on phone → Token
```
- For devices without keyboard/browser
- **Use in**: CLI tools, Smart TV, IoT

### 4. Refresh Token
```
App → /token?refresh_token=XXX → New Access Token (no re-login)
```
- Allows renewing access tokens without interrupting the user
- Refresh token has a longer lifetime than the access token
- **IMPORTANT**: if the refresh token expires, the user must log in again

## JWT structure

A JWT has 3 parts separated by dots:
```
eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.SIGNATURE
    HEADER                  PAYLOAD              SIGNATURE
```

### How to decode (debug):
```bash
# With Python (without verifying signature — debug only)
echo "eyJzdWIiOiJ1c2VyMSJ9" | base64 -d

# With PyJWT
import jwt
# Decode without verifying (DEBUG ONLY)
payload = jwt.decode(token, options={"verify_signature": False})

# Online: jwt.io
```

### IMPORTANT about the `exp` field:
- It is a **Unix timestamp in UTC** (seconds since 1970-01-01 00:00:00 UTC)
- It has NO timezone information
- To convert: `datetime.utcfromtimestamp(exp)` in Python
- To check expiry: `exp < time.time()` → expired

```python
import time
from datetime import datetime, timezone

exp = 1735689600
print(datetime.fromtimestamp(exp, tz=timezone.utc))  # 2026-01-01 00:00:00+00:00
print("Expired" if exp < time.time() else "Valid")
```
