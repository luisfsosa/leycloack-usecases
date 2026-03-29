# UC6 — Supply Chain Partner Portal (Reverse B2B)

## What you learn here

- Invitation tokens: HS256-signed JWTs for onboarding without an external IdP
- Keycloak self-registration: redirecting to the registration form via PKCE
- Context handoff across OAuth2 redirects using `sessionStorage`
- Multi-tenant data scoping using JWT claims
- The Reverse B2B pattern: the enterprise client invites its suppliers

---

## The Problem

Altana has enterprise clients (Toyota, BMW, Foxconn) who need their tier-2
and tier-3 suppliers to upload data to the platform.

**The problem:** these suppliers are small companies. They have no Okta, no
Azure AD, no corporate Identity Provider. They only have an email address.

**The solution:** Reverse B2B — Toyota invites its suppliers. Altana manages
supplier identity directly in Keycloak.

---

## Flow Diagram

```
Toyota (admin)                 Altana API               Supplier
      │                            │                        │
      │── POST /invitations ──────►│                        │
      │   {tenant_id: "toyota",    │                        │
      │    supplier_tier: 2,       │                        │
      │    email: "supplier@..."}  │                        │
      │                            │                        │
      │◄── invitation_token ───────│                        │
      │    invite_link: http://    │                        │
      │    localhost:5173/accept-  │                        │
      │    invite?token=XXX        │                        │
      │                            │                        │
      │──── (sends link by email) ─────────────────────────►│
      │                            │                        │
      │                            │◄── GET /invitations/XXX│
      │                            │    (React loads details)
      │                            │                        │
      │                            │── details ────────────►│
      │                            │   "Toyota invited you" │
      │                            │                        │
      │                            │        ["Create account"]
      │                            │                        │
      │          ◄────────────── redirect to Keycloak /registrations
      │                            │                        │
      │                            │    (fills in form)     │
      │                            │◄───────────────────────│
      │                            │    code + state        │
      │                            │                        │
      │          POST /callback    │                        │
      │          (exchangeCode)    │                        │
      │                            │                        │
      │          POST /invitations/XXX/complete             │
      │          Bearer: <access_token>                     │
      │                            │                        │
      │          ────────────── /supplier/dashboard ───────►│
```

---

## Components Implemented

### Backend (FastAPI)

**`routers/invitations.py`**

| Endpoint | Auth | Description |
|----------|------|-------------|
| `POST /invitations` | `ROLE_ADMIN` | Signs a JWT with supplier context |
| `GET /invitations/{token}` | Public | Validates and exposes details |
| `POST /invitations/{token}/complete` | Authenticated | Marks as used, returns profile |

**`routers/supplier.py`**

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /supplier/dashboard` | Authenticated | Supplier's POs and certifications |
| `POST /supplier/upload` | Authenticated | Simulates document upload |

### Frontend (React)

- **`AcceptInvitePage.jsx`** — shows invitation details; offers create account / login
- **`SupplierDashboardPage.jsx`** — portal with POs and certifications
- **`keycloak.js`** — new `startRegistration()` function using the `/registrations` endpoint
- **`AuthContext.jsx`** — new `register(loginHint)` function exposed in context

---

## Key Concepts

### Why JWT for invitation tokens? (not session IDs)

```
Option A: UUID in a database
  + Easy to revoke (delete row)
  - Requires a DB query on every validation
  - Creates a dependency: the validator needs DB access

Option B: Signed JWT (what we implemented)
  + Self-contained: validation without DB
  + Portable: can be embedded in an email link
  - Harder to revoke before expiry
  → For invitations (single-use, short-lived): JWT is sufficient
```

### HS256 vs RS256

```
HS256 (symmetric):
  - Same key for signing and verifying
  - Only works when one service both signs AND verifies
  - Fast, simple
  → Ideal for invitation tokens (FastAPI is the only issuer AND verifier)

RS256 (asymmetric):
  - Private key signs, public key verifies
  - Multiple services can verify without knowing the private key
  → Ideal for Keycloak tokens (issued by 1 issuer, verified by N microservices)
```

### Context handoff across OAuth2 redirects

The supplier visits `/accept-invite?token=XXX`, is then redirected to Keycloak,
and returns to `/callback`. How does the callback know the user came from an invitation?

**Option 1: `sessionStorage`** (what we implemented)
```javascript
// Before redirecting to Keycloak:
sessionStorage.setItem('pendingInviteToken', token);

// On return from callback:
const pending = sessionStorage.getItem('pendingInviteToken');
if (pending) { /* complete onboarding */ }
```

**Option 2: OAuth2 `state` parameter**
```javascript
// The state can carry base64-encoded context:
const state = btoa(JSON.stringify({
  nonce: randomString(),
  inviteToken: token,
}));
// Keycloak returns the same state in the callback
```

The `state` approach is more robust (survives the user opening another tab),
but more complex to implement. For an MVP, `sessionStorage` is sufficient.

### Keycloak Registration Endpoint

```
Normal login:
  GET /realms/{realm}/protocol/openid-connect/auth
      ?client_id=altana-web&response_type=code...
  → Shows the LOGIN form

Registration:
  GET /realms/{realm}/protocol/openid-connect/registrations
      ?client_id=altana-web&response_type=code...
  → Shows the REGISTRATION form

The callback is identical in both cases. There is no difference in the code exchange.
After registration, Keycloak issues tokens exactly as it does after login.
```

---

## Keycloak Configuration Required

In the `altana-dev` realm, ensure:

1. **Registration enabled:**
   Realm Settings → Login tab → `User registration: ON`

2. **Email verification** (optional in dev, mandatory in prod):
   Realm Settings → Login tab → `Verify email: ON/OFF`

3. **Client `altana-web`** must have:
   - Valid Redirect URIs: `http://localhost:5173/callback`
   - Web Origins: `http://localhost:5173`

---

## How to Test

```bash
# Terminal 1 — FastAPI
cd python-app
uvicorn altana.main:app --port 8081 --reload

# Terminal 2 — React
cd react-app
npm run dev

# Terminal 3 — Test script
python scripts/test_uc6.py
```

The script prints the `invite_link`. Open it in the browser to complete the flow.

---

## Interview Questions (UC6)

**Q: "How would you onboard a supplier with no corporate IdP?"**
> Keycloak can manage the supplier's identity directly (self-registration).
> The invitation flow ensures the supplier was pre-authorised by the enterprise
> client before being allowed to register. The invitation carries context
> (tenant, tier) that is associated with the user during onboarding.

**Q: "How do you prevent a supplier from using the same invitation twice?"**
> When onboarding completes, we store a fingerprint of the token (email + iat)
> in the database. Before completing, we check it is not already in that set.
> In production: an `invitation_usages` table with a unique index on `token_jti`.

**Q: "How would you revoke an invitation before it expires?"**
> With a pure JWT you cannot revoke without a blocklist. Options:
> 1. Add a `jti` (JWT ID) to the token and store revoked `jti` values in Redis/DB.
> 2. Use UUIDs in DB instead of JWT — easier to revoke, requires a lookup.
> 3. Very short TTL (24h) reduces the abuse window.

**Q: "How would you scale this system to thousands of suppliers?"**
> - Invitations in a PostgreSQL table (tenant_id, invited_email, expires_at, used_at)
> - Automatic role assignment via Keycloak Admin API after registration
>   (Event Listener SPI that listens for `REGISTER` and assigns roles from the invitation token)
> - Notifications via webhook/email using the UC2 Event Listener

---

## Production Improvements

1. **PostgreSQL invitation table** — real persistence, revocation, audit trail
2. **Automatic role assignment** — Keycloak Event Listener (SPI) that assigns
   `ROLE_SUPPLIER` and custom claims when it detects a registration with an invitation token
3. **Transactional email** — send the invite_link via SendGrid / AWS SES
4. **Rate limiting** — limit invitation creation per admin (anti-spam)
5. **Azure KeyVault** — manage `INVITATION_SECRET` via vault, not an env var
