# B2B Testing Guide — Identity Brokering

> This guide explains how to reproduce the complete B2B flow from scratch using
> the project scripts. Use it whenever you want to test the
> toyota-corp → altana-dev → FastAPI B2B pattern.

---

## Prerequisites — check before starting

```powershell
# 1. Keycloak running
curl http://localhost:8080/realms/altana-dev/.well-known/openid-configuration
# should respond with JSON containing "issuer": "http://localhost:8080/realms/altana-dev"

# 2. FastAPI running (in another terminal)
# cd python-app\src
# uvicorn altana.main:app --reload --port 8003

# 3. Verify that the toyota-corp IDP exists in altana-dev
# (if it doesn't, see the "Setup from scratch" section at the end of this guide)
```

If Keycloak is not running:
```powershell
cd docker
docker compose up -d
```

---

## Test flow — step by step

### Terminal 1 — Callback capture server

Leave it running throughout the test. It captures the `code` automatically
when Keycloak redirects back after login.

```powershell
python scripts\capture_callback.py
```

You will see:
```
Capture server listening on http://localhost:3000
Waiting for Keycloak callback...
```

---

### Terminal 2 — Generate PKCE authorization URL

```powershell
python scripts\generate_b2b_url.py
```

The script prints three sections:

```
======================================================================
PKCE VALUES (save for the exchange):
  code_verifier  : abc123...    ← NOTE THIS, needed for the exchange
  code_challenge : xyz789...
  state          : qrs456...

======================================================================
AUTHORIZATION URL (copy and paste in the browser):

http://localhost:8080/realms/altana-dev/...&kc_idp_hint=toyota-corp

======================================================================
EXCHANGE COMMAND (reference — use exchange_code.py on Windows):
  -d "code_verifier=abc123..."
```

> **IMPORTANT:** The `code_verifier` is single-use and expires with the code
> (300 seconds). If the login takes too long, regenerate with the same script.

---

### Browser — B2B Login

1. Copy the full URL from the `AUTHORIZATION URL` section
2. Paste it in the browser (**incognito window** recommended to avoid SSO)
3. Keycloak redirects directly to toyota-corp (thanks to `kc_idp_hint`)
4. Login with: `john.doe` / `toyota123`
5. The browser redirects to `localhost:3000/callback?code=XXX&state=YYY`

**Terminal 1 prints automatically:**
```
======================================================================
CALLBACK CAPTURED
  code  : e81da983-ee62-...     ← copy this
  state : qrs456...
======================================================================
```

---

### Terminal 2 — Exchange code → tokens

```powershell
python scripts\exchange_code.py
```

The script asks interactively:

```
TOKEN EXCHANGE — Authorization Code → Tokens
============================================================

Paste the captured code:
> e81da983-ee62-...      ← paste the code from Terminal 1

Paste the code_verifier (from generate_b2b_url script):
> abc123...              ← paste the verifier from before
```

**Expected output:**
```
======================================================================
TOKENS OBTAINED
======================================================================
  token_type   : Bearer
  expires_in   : 300s
  access_token : eyJhbGci...

======================================================================
ACCESS TOKEN PAYLOAD
======================================================================
{
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "iss": "http://localhost:8080/realms/altana-dev",
  "realm_access": {
    "roles": [
      "default-roles-altana-dev",
      "offline_access",
      "ROLE_ANALYST",            ← must be here
      "uma_authorization"
    ]
  }
}

======================================================================
SUMMARY
======================================================================
  User   : john.doe
  Email  : john.doe@toyota.com
  Issuer : http://localhost:8080/realms/altana-dev
  Roles  : ['default-roles-altana-dev', 'offline_access', 'ROLE_ANALYST', ...]

[token saved to scripts/.last_access_token]
```

---

### Terminal 2 — Test the token against FastAPI

The `exchange_code.py` script saves the token to `scripts/.last_access_token`.
Use it to call protected endpoints:

```powershell
# Read the saved token
$token = Get-Content scripts\.last_access_token

# Public endpoint (no auth)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/health `
  | Select-Object -ExpandProperty Content

# Authenticated endpoint — any user with a valid token
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/me `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content

# Endpoint requiring ROLE_ANALYST (john.doe should pass)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/shipments `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content

# Endpoint requiring ROLE_ADMIN (john.doe cannot — expect 403)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/suppliers/test-id `
  -Method Delete `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content
```

**Expected results:**

| Endpoint | john.doe (ROLE_ANALYST) |
|----------|------------------------|
| `GET /health` | ✅ 200 — public |
| `GET /me` | ✅ 200 — authenticated |
| `GET /shipments` | ✅ 200 — has ROLE_ANALYST |
| `DELETE /suppliers/{id}` | ❌ 403 — requires ROLE_ADMIN |

---

## Troubleshooting — common issues

### "State mismatch" or expired token

The authorization code expires in 300 seconds. If too much time has passed:
```powershell
# Regenerate everything from step 2
python scripts\generate_b2b_url.py
```

### "ROLE_ANALYST does not appear in the token"

This means the mapper did not run because john.doe already existed in altana-dev
(IMPORT only runs when the user is created):

```powershell
python scripts\reset_b2b_user.py
```

Then go back to the browser step and log in again.

### "target is null" in Keycloak logs

The mapper has an incorrect type. Verify:
```powershell
python scripts\verify_b2b_setup.py
```

### "connection refused" on localhost:3000

The capture server is not running. Open Terminal 1 and run:
```powershell
python scripts\capture_callback.py
```

### john.doe cannot log in (wrong credentials)

The credentials in toyota-corp are `john.doe` / `toyota123`.
If the realm was recreated, the password may have been lost:
```powershell
python scripts\reset_toyota_user.py
```

---

## Support scripts

| Script | Purpose |
|--------|---------|
| `scripts\generate_b2b_url.py` | Generates PKCE + authorization URL with kc_idp_hint |
| `scripts\capture_callback.py` | HTTP server on port 3000 that captures the code |
| `scripts\exchange_code.py` | Exchanges code + verifier for tokens, shows payload |
| `scripts\reset_b2b_user.py` | Deletes john.doe from altana-dev to force re-import |
| `scripts\verify_b2b_setup.py` | Verifies that the entire B2B configuration is correct |

---

## Setup from scratch (if realms do not exist)

If you lost your Keycloak data (e.g.: recreated containers without a persistent volume),
run in order:

```powershell
# 1. Create toyota-corp realm + user john.doe
python scripts\setup_toyota_idp.py

# 2. Verify the configuration
python scripts\verify_b2b_setup.py

# 3. Proceed with the normal flow from step 1
```

---

## Quick reference — B2B environment data

```
Keycloak:         http://localhost:8080
Admin:            admin / admin

Broker realm:     altana-dev
  SPA client:     altana-web (public, PKCE)
  Registered IDP: toyota-corp (alias)
  Mapper:         oidc-hardcoded-role-idp-mapper → ROLE_ANALYST

External IDP:     toyota-corp (simulates enterprise client)
  Broker client:  altana-broker (secret: altana-broker-secret)
  Test user:      john.doe / toyota123

FastAPI:          http://localhost:8003
  /supply-chain/health     → public
  /supply-chain/me         → any valid token
  /supply-chain/shipments  → ROLE_ANALYST or ROLE_ADMIN
  /supply-chain/suppliers  → DELETE requires ROLE_ADMIN
```
