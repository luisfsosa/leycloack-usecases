# JWT Practical Guide — Troubleshooting, Anatomy, Interview Q&A

> Hands-on guide using real tokens from this project's Keycloak.
> Every example shows actual output you can reproduce.

---

## JWT structure — the three parts

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IlA3T0Y2OHJi...
    │
    HEADER (base64url)
                      .
                       eyJleHAiOjE3NzQ3NTg1MjUsImlhdCI6MTc3N...
                           │
                           PAYLOAD (base64url)
                                              .
                                               SflKxwRJSMeKKF2QT4f...
                                                   │
                                                   SIGNATURE (binary, base64url)
```

The dot (`.`) is just a separator. None of the parts are encrypted by default —
they are only **base64url-encoded and signed**. Anyone can read the payload.
The signature proves it wasn't tampered with.

> **INTERVIEW:** "Is a JWT encrypted?"
> → By default, no. A standard JWT (JWS) is signed but not encrypted.
>   The payload is base64url-encoded — anyone can decode it.
>   If you need confidentiality, use JWE (JSON Web Encryption), but that is rare.
>   Never put secrets or passwords in a JWT payload.

---

## Part 1: HEADER — decoded

Real header from this project's Keycloak:

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "P7OF68rbPugXKr87B6IDQectyyB-5IFdpvkdVeqlYdU"
}
```

| Field | Meaning | Interview note |
|-------|---------|---------------|
| `alg` | Signing algorithm | RS256 = RSA + SHA-256 (asymmetric). HS256 = HMAC-SHA256 (symmetric, shared secret) |
| `typ` | Token type | Always "JWT" |
| `kid` | Key ID | Which public key to use for verification. Keycloak rotates keys — `kid` tells the consumer which one to fetch from JWKS |

> **INTERVIEW:** "What is the difference between RS256 and HS256?"
> → RS256 is **asymmetric**: Keycloak signs with a private key, your API verifies
>   with a public key (downloaded from the JWKS endpoint). The private key never
>   leaves Keycloak. Any service can verify without a shared secret.
>
>   HS256 is **symmetric**: the same secret is used to sign and verify. Every service
>   that needs to verify must know the secret — this creates a secret distribution
>   problem in microservices. Use RS256 (Keycloak's default) in production.

> **INTERVIEW:** "What is the `kid` field for?"
> → Keycloak rotates its signing keys periodically. The `kid` (Key ID) tells the
>   verifier which key in the JWKS to use. Without it, you would have to try all
>   keys. With it, verification is O(1). When you see a 401 with "invalid signature"
>   right after a key rotation, it means your service cached the old JWKS —
>   force a refresh of the JWKS cache.

---

## Part 2: PAYLOAD — the access_token decoded

Real access token from `analyst-user` in this project:

```json
{
  "exp": 1774758525,
  "iat": 1774758225,
  "jti": "onrtro:53889c2b-ba71-7abd-2f5f-9f6e7ed64c2c",
  "iss": "http://localhost:8080/realms/altana-dev",
  "typ": "Bearer",
  "azp": "altana-web",
  "sid": "E3u5z75jnx24-KpfVM1qFAdT",
  "realm_access": {
    "roles": ["ROLE_USER", "ROLE_ANALYST"]
  },
  "scope": "email profile",
  "tenant_id": "toyota",
  "user_type": "employee",
  "email_verified": true,
  "name": "Supply Analyst",
  "preferred_username": "analyst-user",
  "given_name": "Supply",
  "family_name": "Analyst",
  "email": "analyst@altana.dev"
}
```

### Every claim explained

**Time claims (the ones interviewers always ask about):**

| Claim | Full name | Value | Meaning |
|-------|-----------|-------|---------|
| `exp` | Expiration | `1774758525` | Token invalid after this time |
| `iat` | Issued At | `1774758225` | When the token was created |
| `nbf` | Not Before | *(optional)* | Token not valid before this time |

**The timezone question — guaranteed in every interview:**

> **INTERVIEW:** "What timezone does the `exp` field use?"
> → UTC Unix timestamp — seconds elapsed since **1970-01-01 00:00:00 UTC**.
>   The JWT spec (RFC 7519) mandates UTC. There is no timezone indicator
>   in the value — it is always UTC by definition.

```python
from datetime import datetime, timezone
import time

exp = 1774758525

# Convert to human-readable UTC
print(datetime.fromtimestamp(exp, tz=timezone.utc))
# → 2026-03-29 04:28:45+00:00

# Check if expired
print("expired" if exp < time.time() else f"valid for {int(exp - time.time())}s")
# → valid for 282s

# WRONG: datetime.fromtimestamp(exp)  — this uses LOCAL timezone → wrong result
# RIGHT: datetime.fromtimestamp(exp, tz=timezone.utc)  — always UTC
```

**Identity claims:**

| Claim | Value | Meaning |
|-------|-------|---------|
| `sub` | UUID | **Stable user ID** — never changes even if username changes. Use this as the primary key for user records, not `preferred_username` |
| `preferred_username` | `"analyst-user"` | Display name. Can change. Don't use as a DB key |
| `email` | `"analyst@altana.dev"` | User email (only if `email` scope requested) |
| `name` | `"Supply Analyst"` | Full name from `given_name + family_name` |
| `email_verified` | `true` | Whether Keycloak verified the email address |

> **INTERVIEW:** "Why should you use `sub` and not `preferred_username` as the user key in your DB?"
> → `preferred_username` can change — an admin can rename the user.
>   `sub` is an immutable UUID assigned at account creation, guaranteed unique within the realm.
>   If you key your DB on `preferred_username`, a rename breaks all historical records.

**OAuth2 / session claims:**

| Claim | Value | Meaning |
|-------|-------|---------|
| `iss` | `http://.../realms/altana-dev` | Issuer — the exact URL of the Keycloak realm. Must match your config |
| `aud` | *(in id_token)* | Audience — who this token is for. Access tokens may omit it or set it to the resource server |
| `azp` | `"altana-web"` | Authorized Party — the client that *requested* the token (can differ from `aud`) |
| `jti` | UUID | JWT ID — unique identifier for this token. Used for revocation lists or replay detection |
| `sid` | session ID | Keycloak session. All tokens from the same login share a `sid`. Used for logout |
| `typ` | `"Bearer"` | Confirms this is an access token (vs `"ID"` for id_token, `"Refresh"` for refresh) |

**Authorization claims (Keycloak-specific):**

| Claim | Value | Meaning |
|-------|-------|---------|
| `realm_access.roles` | `["ROLE_ANALYST"]` | Roles granted at the realm level (global) |
| `resource_access.<client>.roles` | *(if configured)* | Roles specific to one client |
| `scope` | `"email profile"` | OAuth2 scopes granted (what data the token allows access to) |

**Custom business claims (added by our TenantIdMapper SPI):**

| Claim | Value | Meaning |
|-------|-------|---------|
| `tenant_id` | `"toyota"` | Which B2B tenant this user belongs to |
| `user_type` | `"employee"` | Employee (full access) or customer (restricted) |

---

## Part 3: access_token vs id_token vs refresh_token

Real comparison from this project:

```
access_token payload:               id_token payload:
  "typ": "Bearer"                     "typ": "ID"
  "aud": not set / resource server    "aud": "altana-web"  ← client that requested it
  "realm_access": { roles }           no realm_access
  custom claims (tenant_id)           custom claims (tenant_id)
  "at_hash": not present              "at_hash": "9C1OnjJT..."  ← hash of access_token
```

| Token | Used by | Sent to | Lifetime | Purpose |
|-------|---------|---------|----------|---------|
| `access_token` | Client | Every API call (Bearer header) | Short (5 min) | Prove authorization to Resource Servers |
| `id_token` | Client only | Never sent to APIs | Short (5 min) | Prove identity to the client app (show username, email) |
| `refresh_token` | Client | Only to `/token` endpoint | Long (hours) | Renew access_token without re-login |

> **INTERVIEW:** "Why should you never send the id_token to your API?"
> → The id_token is for the *client application* — it proves who logged in.
>   The access_token is for the *API* — it proves what the app is allowed to do.
>   Your API should validate the access_token, not the id_token.
>   Sending the id_token to an API is a misuse of the protocol.

> **INTERVIEW:** "What is `at_hash` in the id_token?"
> → Access Token Hash. It's the base64url of the left half of SHA256(access_token).
>   It cryptographically binds the id_token to its corresponding access_token.
>   The client can verify both tokens came from the same issuance event.

---

## How to decode a JWT — all methods

### Method 1: bash (no tools required)

```bash
TOKEN="eyJhbGci..."

# Header
echo $TOKEN | cut -d'.' -f1 | python3 -c "
import sys,base64,json
p=sys.stdin.read().strip(); p+='='*(4-len(p)%4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))"

# Payload
echo $TOKEN | cut -d'.' -f2 | python3 -c "
import sys,base64,json
p=sys.stdin.read().strip(); p+='='*(4-len(p)%4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))"
```

### Method 2: Python — decode without verification (debug only)

```python
import jwt  # pip install PyJWT

# WARNING: never use verify_signature=False in production
payload = jwt.decode(token, options={"verify_signature": False})
print(payload)
```

### Method 3: Python — full verification (production)

```python
import jwt
import httpx

KEYCLOAK_URL = "http://localhost:8080"
REALM        = "altana-dev"

# Fetch public keys from Keycloak (cache this in production)
jwks_url  = f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/certs"
jwks_data = httpx.get(jwks_url).json()

# Decode and verify signature + exp + iss + aud
payload = jwt.decode(
    token,
    jwks_data,
    algorithms=["RS256"],
    audience="altana-web",   # validates aud claim
    issuer=f"{KEYCLOAK_URL}/realms/{REALM}"  # validates iss claim
)

print(f"User:      {payload['preferred_username']}")
print(f"Roles:     {payload['realm_access']['roles']}")
print(f"Tenant:    {payload.get('tenant_id')}")
print(f"Expires:   {payload['exp']}  (UTC Unix timestamp)")
```

### Method 4: curl — token introspection (server-side validation)

```bash
# Asks Keycloak itself if a token is valid
curl -s -X POST "http://localhost:8080/realms/altana-dev/protocol/openid-connect/token/introspect" \
  -u "supply-chain-backend:CHANGE-ME-IN-PRODUCTION" \
  -d "token=$TOKEN" | python3 -m json.tool

# Response:
# { "active": true,  ... }  → valid
# { "active": false }       → expired or revoked
```

> **INTERVIEW:** "When would you use introspection instead of local JWT verification?"
> → When you need to know if a token was revoked before its `exp`.
>   Local JWT verification can only check the signature and expiry time.
>   Introspection asks Keycloak in real time — but adds latency (one HTTP call per request).
>   For most APIs, local verification is the right choice. Use introspection only
>   when revocation is a strict requirement (high-security operations, token logout).

---

## Troubleshooting flow — "I got a 401 / 403, what do I check?"

```
Got 401 Unauthorized?
    │
    ├─ Is the token present in the request?
    │   No  → add Authorization: Bearer <token> header
    │
    ├─ Is the token expired?
    │   exp < now()  → renew with refresh_token or re-login
    │
    ├─ Is the issuer correct?
    │   iss != "http://keycloak-host/realms/altana-dev"
    │   → wrong Keycloak URL or realm name in app config
    │
    ├─ Is the audience correct?  (for id_token / strict APIs)
    │   aud != "altana-web"
    │   → wrong client_id in the token request
    │
    └─ Is the signature valid?
        "invalid signature" error
        → Keycloak rotated its keys — force JWKS cache refresh
        → wrong KEYCLOAK_URL in your service (fetching keys from wrong place)

Got 403 Forbidden (valid token but access denied)?
    │
    ├─ Check realm_access.roles — does it contain the required role?
    │   Missing role → assign the role to the user in Keycloak Admin
    │
    ├─ Check the JwtConverter (Spring) or require_role (FastAPI)
    │   Are roles being extracted from the right claim?
    │   (Common: developer reads from scope instead of realm_access.roles)
    │
    └─ Check resource_access.<client>.roles vs realm_access.roles
        Maybe the role is client-specific, not realm-wide
```

### Practical debugging script

```python
import jwt, httpx, time
from datetime import datetime, timezone

def debug_token(token: str, keycloak_url: str, realm: str):
    """Decode a JWT and print a human-readable debug report."""

    # Step 1: decode without verification to see the claims
    try:
        header  = jwt.get_unverified_header(token)
        payload = jwt.decode(token, options={"verify_signature": False})
    except Exception as e:
        print(f"DECODE ERROR: {e}")
        return

    print("=== JWT DEBUG REPORT ===")
    print(f"Algorithm:  {header.get('alg')}")
    print(f"Key ID:     {header.get('kid')}")
    print(f"Token type: {payload.get('typ')}")
    print()

    # Step 2: check expiry
    exp = payload.get("exp", 0)
    now = time.time()
    exp_utc = datetime.fromtimestamp(exp, tz=timezone.utc)
    iat_utc = datetime.fromtimestamp(payload.get("iat", 0), tz=timezone.utc)

    print(f"Issued at:  {iat_utc}  (UTC)")
    print(f"Expires at: {exp_utc}  (UTC)")

    if exp < now:
        print(f"STATUS:     *** EXPIRED {int(now - exp)}s ago ***")
    else:
        print(f"STATUS:     VALID — expires in {int(exp - now)}s")
    print()

    # Step 3: identity
    print(f"Subject:    {payload.get('sub')}")
    print(f"Username:   {payload.get('preferred_username')}")
    print(f"Email:      {payload.get('email')}")
    print(f"Issuer:     {payload.get('iss')}")
    print(f"Audience:   {payload.get('aud')}")
    print(f"AZP:        {payload.get('azp')}")
    print()

    # Step 4: roles
    roles = payload.get("realm_access", {}).get("roles", [])
    print(f"Realm roles: {roles}")
    print(f"Scope:       {payload.get('scope')}")
    print()

    # Step 5: custom claims
    print(f"tenant_id:  {payload.get('tenant_id')}")
    print(f"user_type:  {payload.get('user_type')}")
    print()

    # Step 6: verify signature (optional, requires Keycloak running)
    try:
        jwks = httpx.get(
            f"{keycloak_url}/realms/{realm}/protocol/openid-connect/certs"
        ).json()
        jwt.decode(token, jwks, algorithms=["RS256"],
                   options={"verify_aud": False})
        print("Signature:  VALID (verified against Keycloak JWKS)")
    except Exception as e:
        print(f"Signature:  INVALID — {e}")


# Usage:
# debug_token(token, "http://localhost:8080", "altana-dev")
```

---

## Common errors and what they mean

| Error | HTTP code | Cause | Fix |
|-------|-----------|-------|-----|
| `Token is expired` | 401 | `exp < now()` | Renew with refresh_token |
| `Invalid issuer` | 401 | `iss` doesn't match config | Check realm URL in app.yml / .env |
| `Invalid audience` | 401 | `aud` doesn't match | Check client_id in token request |
| `Invalid signature` | 401 | Keys rotated or wrong JWKS URL | Force JWKS cache refresh |
| `Access denied` | 403 | Token valid, role missing | Add role to user in Keycloak admin |
| `JWTDecodeError` | — | Token truncated or malformed | Check if Authorization header is complete |
| `DecodeError: padding` | — | Missing base64 padding | Add `p += '='*(4-len(p)%4)` before decode |

### The padding fix (always needed in bash/Python)

JWT uses base64**url** encoding which strips `=` padding.
When you decode manually, you must re-add it:

```python
import base64

def decode_b64url(s: str) -> bytes:
    # re-add padding stripped by base64url encoding
    s += '=' * (4 - len(s) % 4)
    return base64.urlsafe_b64decode(s)
```

---

## Interview Q&A — rapid fire

**Q: What timezone does `exp` use?**
> UTC Unix timestamp. No timezone in the value — UTC by RFC 7519 spec.
> `datetime.fromtimestamp(exp, tz=timezone.utc)` in Python.

**Q: What's the difference between `iat` and `nbf`?**
> `iat` = when the token was issued (past).
> `nbf` = earliest time the token is valid (present/future).
> A token can be issued now but only become valid in 5 minutes (`nbf = iat + 300`).
> Use case: pre-issue tokens for scheduled jobs.

**Q: Can you tell if a JWT was tampered with without calling Keycloak?**
> Yes — by verifying the RS256 signature against Keycloak's public key,
> which you download once from the JWKS endpoint and cache.
> Any modification to the header or payload invalidates the signature.

**Q: How do you revoke a JWT before it expires?**
> Short answer: you can't, locally — that's the trade-off of stateless tokens.
> Options:
> 1. Short access token lifetime (5 min) + revoke the refresh token
> 2. Token introspection endpoint — ask Keycloak if it's still active
> 3. Maintain a local revocation list (denylist) — but this adds state back
> 4. Keycloak's session logout — invalidates the session so refresh tokens stop working

**Q: Why does the access_token not have `aud` in some cases?**
> In Keycloak, access tokens don't always have `aud` set to the client by default.
> The `azp` claim identifies who requested it. Add `aud` by configuring
> the "Audience" mapper on the client scope in Keycloak.
> Your Resource Server should validate `azp` or a configured `aud`.

**Q: What is `jti` and when do you use it?**
> JWT ID — a unique identifier for this specific token.
> Use cases:
> - Replay prevention: store used `jti` values and reject duplicates (for sensitive one-time operations)
> - Audit logging: correlate logs across services using the same `jti`
> - Revocation lists: denylist specific `jti` values without revoking the whole session

**Q: What's the difference between `scope` and `roles` in a Keycloak JWT?**
> `scope` is an OAuth2 concept — it controls *what data/endpoints* the app can access
> (`openid`, `email`, `profile`, custom scopes like `supply-chain:read`).
> `roles` (in `realm_access.roles`) are Keycloak's RBAC mechanism — they control
> *what the user is allowed to do*. You can have the scope `supply-chain:read`
> but still be denied if you don't have `ROLE_ANALYST`.
> They work at different layers: scope = OAuth2 authorization, roles = application RBAC.
