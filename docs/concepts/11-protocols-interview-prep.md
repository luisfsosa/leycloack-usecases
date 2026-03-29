# Protocols Interview Prep — SAML / OIDC / OAuth2 / Flows

> Focused on the exact questions asked in Keycloak + IAM engineer interviews.
> Each section has the answer you should give, not just the theory.

---

## The #1 question: "What is the major difference between SAML, OIDC, and OAuth2?"

This is asked in almost every IAM interview. Have this answer memorized.

### The answer (say this in the interview)

> **OAuth2** is an *authorization framework* — it answers "what is this app allowed to do
> on behalf of the user?". It issues an Access Token, but that token says nothing about
> who the user is. It only says what permissions were granted.
>
> **OIDC** is an *authentication layer built on top of OAuth2* — it adds the question
> "who is the user?". It does this by adding an ID Token (a JWT) and a `/userinfo`
> endpoint. So OIDC = OAuth2 + identity.
>
> **SAML 2.0** is an older *authentication and authorization standard* from 2005,
> before REST and JSON existed. It uses XML-based Assertions instead of JWTs,
> relies on browser redirects and form POSTs (not APIs), and is dominant in legacy
> enterprise SSO. It solves the same problem as OIDC but in a completely different way.

### The one-line distinction (memorize this)

| Protocol | Question it answers | Token format | Transport |
|----------|--------------------|----|-----------|
| OAuth2 | "What can this app do?" | Opaque or JWT | REST/HTTP |
| OIDC | "Who is the user?" + "What can this app do?" | JWT (id_token + access_token) | REST/HTTP |
| SAML 2.0 | "Who is the user?" + SSO across apps | XML Assertion | HTTP Redirect + POST |

### Why OAuth2 is NOT authentication

This is a classic trap. The interviewer may say: "But Google login uses OAuth2."

> That's actually OIDC. Google, GitHub, and Microsoft all layer OIDC on top of OAuth2.
> Pure OAuth2 with no OIDC extension gives you an access token you can use to call an API,
> but the app has no standard way to know *who* the user is. That's why the
> `/userinfo` endpoint and `id_token` were added — those are the OIDC additions.

---

## SAML deep dive — what interviewers actually ask

### How SAML works (SP-initiated flow)

```
User                    Service Provider (SP)         Identity Provider (IdP)
  │                      (e.g. Salesforce)              (e.g. ADFS, Okta)
  │── access app ───────►│                              │
  │                      │── SAMLRequest (XML) ────────►│
  │                      │   (base64 + URL-encoded)     │
  │◄── 302 redirect ─────│                              │
  │                                                     │
  │── browser follows ──────────────────────────────────►│
  │◄── login form ──────────────────────────────────────│
  │── user+pass ────────────────────────────────────────►│
  │                                                     │
  │◄── HTML form with SAMLResponse ─────────────────────│
  │    (hidden input, auto-submitted via JS)
  │
  │── POST SAMLResponse ─►│
  │                      │ SP validates XML signature
  │                      │ extracts NameID + Attributes
  │◄── app content ───────│
```

### SAML Assertion structure (what's inside the XML)

```xml
<saml:Assertion>
  <saml:Issuer>https://idp.toyota.com</saml:Issuer>

  <saml:Subject>
    <saml:NameID Format="email">john.doe@toyota.com</saml:NameID>
  </saml:Subject>

  <saml:Conditions
    NotBefore="2026-03-28T14:00:00Z"
    NotOnOrAfter="2026-03-28T14:05:00Z">   ← 5-minute validity window
    <saml:AudienceRestriction>
      <saml:Audience>https://altana.com/saml/sp</saml:Audience>
    </saml:AudienceRestriction>
  </saml:Conditions>

  <saml:AuthnStatement AuthnInstant="2026-03-28T14:00:00Z">
    <saml:AuthnContext>
      <saml:AuthnContextClassRef>
        urn:oasis:names:tc:SAML:2.0:ac:classes:Password
      </saml:AuthnContextClassRef>
    </saml:AuthnContext>
  </saml:AuthnStatement>

  <saml:AttributeStatement>
    <saml:Attribute Name="department">
      <saml:AttributeValue>Engineering</saml:AttributeValue>
    </saml:Attribute>
  </saml:AttributeStatement>
</saml:Assertion>
```

### SAML vs OIDC — the real differences interviewers probe

| Dimension | SAML 2.0 | OIDC |
|-----------|---------|------|
| Age / origin | 2005, enterprise B2B | 2014, web/mobile era |
| Token format | XML Assertion (signed, sometimes encrypted) | JWT (signed, base64url) |
| Transport | HTTP Redirect binding (GET) + POST binding | REST API calls (Bearer tokens) |
| Initiated by | SP-initiated or IdP-initiated | Always client-initiated |
| Mobile/SPA support | Poor (requires browser, no APIs) | Native (pure HTTP, REST) |
| Federation | Mature, widely supported | Growing support |
| Debugging | Hard (XML, base64 blobs) | Easy (jwt.io, base64 decode) |
| Keycloak role | Receives SAML assertions, issues OIDC tokens | Native protocol |

> **INTERVIEW:** "When would you choose SAML over OIDC?"
> → When the enterprise client's existing IdP (ADFS, Okta, Azure AD older configs)
>   only speaks SAML, or when their security team mandates it. In practice,
>   Keycloak acts as a broker: it speaks SAML to the legacy IdP and issues
>   OIDC tokens to your application. Your app only ever sees JWT.

> **INTERVIEW:** "Can SAML be used for APIs?"
> → No, not natively. SAML uses browser redirects and form POSTs.
>   For API-to-API calls you need OAuth2 (Client Credentials).
>   This is the main technical reason enterprises are migrating to OIDC.

---

## OAuth2 flows — decision matrix

```
Does the flow involve a human user?
    │
    ├── NO  → Client Credentials
    │         (machine-to-machine, service accounts)
    │
    └── YES
          │
          ├── Does the device have a browser?
          │       │
          │       ├── NO  → Device Code
          │       │         (CLI tools, Smart TV, IoT)
          │       │
          │       └── YES
          │               │
          │               └── Is it a public client? (SPA, mobile)
          │                       │
          │                       ├── YES → Authorization Code + PKCE
          │                       │
          │                       └── NO (confidential, server-side)
          │                               → Authorization Code (no PKCE required,
          │                                 but PKCE is recommended anyway)
```

**Flows you should never use in production:**
- ~~Implicit flow~~ — deprecated. Tokens in the URL fragment, no refresh token.
- ~~Resource Owner Password~~ — user gives password directly to the app. Breaks SSO, no MFA.

---

## Flow 1: Authorization Code + PKCE

### Why PKCE exists — the attack it prevents

Without PKCE, on a mobile device:

```
App requests code → Keycloak → redirect to myapp://callback?code=ABC
                                         ↑
                               MALICIOUS APP also registered
                               myapp:// URI scheme
                               → intercepts the code
                               → exchanges it for a token
                               → account takeover
```

PKCE prevents this because:
```
code verifier  = random 32-byte string
code challenge = BASE64URL(SHA256(verifier))

→ sent to Keycloak at start:  code_challenge
→ sent to Keycloak at exchange: code_verifier

Malicious app intercepted the code but does NOT have the verifier
→ cannot exchange the code → attack fails
```

### Full flow with all parameters

```
STEP 1 — Generate PKCE
  code_verifier = cryptoRandom(32)         e.g. "dBjftJeZ4CVP..."
  code_challenge = base64url(sha256(cv))   e.g. "E9Melhoa2Owen..."

STEP 2 — Authorization request
  GET /auth
    ?client_id=altana-web
    &response_type=code
    &scope=openid profile email
    &redirect_uri=http://localhost:5173/callback
    &code_challenge=E9Melhoa2Owen...
    &code_challenge_method=S256
    &state=xyzABC          ← anti-CSRF, must match on callback
    &nonce=abc123          ← OIDC: prevents replay of id_token
    [&login_hint=user@toyota.com]    ← pre-fill username
    [&kc_idp_hint=toyota-corp]       ← skip to specific IDP

  → user logs in → Keycloak redirects to:
  callback?code=AUTH_CODE&state=xyzABC

STEP 3 — Token exchange (back-channel)
  POST /token
    grant_type=authorization_code
    code=AUTH_CODE
    redirect_uri=http://localhost:5173/callback  ← must match exactly
    client_id=altana-web
    code_verifier=dBjftJeZ4CVP...               ← proves you started the flow

  ← { access_token, id_token, refresh_token, expires_in }
```

### Interview questions on this flow

**"Why does redirect_uri need to match exactly?"**
> Open redirect attack prevention. If Keycloak accepted any URI, an attacker could
> craft a URL like `/auth?redirect_uri=https://evil.com` and steal the code.
> Keycloak validates the redirect_uri against the whitelist configured on the client.

**"What is the state parameter for?"**
> Anti-CSRF. The app generates a random value, sends it, and Keycloak echoes it back.
> If the state in the callback doesn't match what you stored in sessionStorage,
> someone else initiated this flow — discard the code.

**"What is the nonce parameter for?"**
> OIDC-specific. Prevents replay attacks on the id_token. The app puts a random
> nonce in the request; Keycloak embeds it in the id_token. The app verifies it
> matches. This prevents an attacker from reusing a captured id_token.

**"Why is PKCE recommended even for confidential (server-side) clients?"**
> The original threat model for PKCE was public clients (mobile, SPA).
> But PKCE also protects against authorization code injection attacks on
> confidential clients. RFC 9700 (2025) recommends PKCE for all clients.
> Keycloak supports it for all client types.

---

## Flow 2: Client Credentials

### What it is

No user involved. The application itself authenticates with its own credentials
(client_id + client_secret) and receives an access token that represents the
**service**, not a person.

```
Microservice A                    Keycloak                  Microservice B
      │                               │                           │
      │── POST /token ───────────────►│                           │
      │   grant_type=client_credentials                           │
      │   client_id=service-a                                     │
      │   client_secret=***                                       │
      │◄── access_token (no user claims) ─────────────────────── │
      │                               │                           │
      │── GET /api/data ──────────────────────────────────────────►│
      │   Authorization: Bearer <token>                           │
      │◄── 200 data ─────────────────────────────────────────────│
```

### Token characteristics

```json
{
  "iss": "http://localhost:8080/realms/altana-dev",
  "sub": "service-a-uuid",          ← the CLIENT uuid, not a user
  "azp": "service-a",
  "clientId": "service-a",          ← no preferred_username
  "realm_access": { "roles": [] },  ← roles from service account
  "scope": "email profile"
}
```

Note: no `preferred_username`, no `email` from a real person.
In Spring Boot: `jwt.getClaimAsString("preferred_username")` returns null
→ use `jwt.getClaimAsString("client_id")` as fallback (as in our project).

### Interview questions

**"Where do you store the client_secret in a microservice?"**
> Never in code or git. In Kubernetes: use a Secret mounted as env variable.
> In production: HashiCorp Vault or Azure Key Vault with dynamic secret rotation.
> The secret is injected at runtime via `KEYCLOAK_CLIENT_SECRET` env var.

**"How do you rotate a client_secret without downtime?"**
> Two approaches:
> 1. Keycloak allows multiple active secrets per client (secret rotation window).
>    Rotate in Keycloak → update the app secret → after a grace period, remove the old.
> 2. Use mTLS client authentication instead of a secret — the certificate is the credential.

**"Does Client Credentials support refresh tokens?"**
> No. There is no user session to refresh. When the access token expires,
> the service simply requests a new one with the same client_secret.
> Short token lifetimes (5 min) are fine because re-requesting is cheap.

---

## Flow 3: Device Code

### When you need it

The device cannot open a browser or cannot receive a redirect:
- CLI tool running in a terminal (no browser)
- Smart TV, set-top box (no keyboard)
- IoT sensor with limited UI
- Any headless server process

### The flow

```
Device (CLI)                    Keycloak                    User's Phone/PC
      │                               │                           │
      │── POST /device ──────────────►│                           │
      │   client_id=my-cli                                        │
      │◄── {                          │                           │
      │     device_code: "GmRhmhcx",  │                           │
      │     user_code:   "WDJB-MJHT", │ ← short, human-typeable  │
      │     verification_uri: "http://localhost:8080/device",     │
      │     expires_in: 1800,         │                           │
      │     interval: 5               │ ← poll every 5 seconds    │
      │   }                           │                           │
      │                               │                           │
      │  Display to user:             │                           │
      │  "Go to http://.../device"    │                           │
      │  "Enter code: WDJB-MJHT"      │                           │
      │                               │                           │
      │── POST /token (polling) ──────►│                           │
      │   grant_type=urn:ietf:params:oauth:grant-type:device_code │
      │   device_code=GmRhmhcx        │                           │
      │◄── {"error":"authorization_pending"} (keep polling)       │
      │                               │                           │
      │                               │◄── user visits URL ───────│
      │                               │◄── enters WDJB-MJHT ──────│
      │                               │◄── user logs in ──────────│
      │                               │                           │
      │── POST /token (polling) ──────►│                           │
      │◄── { access_token, ... }      │                           │ ← success
```

### Interview questions

**"What errors can the polling return?"**
> - `authorization_pending` — user hasn't approved yet, keep polling
> - `slow_down` — polling too fast, increase interval by 5 seconds
> - `access_denied` — user denied the request
> - `expired_token` — device_code expired, restart the flow

**"Is Device Code secure without PKCE?"**
> Yes. The device_code is a server-side correlation key — it never appears in a
> redirect URI, so there is no code interception risk. PKCE exists to protect
> the redirect URI in Authorization Code flow. Device Code has a different threat
> model: the risk is a user approving a code they didn't request (phishing).
> Mitigations: short code expiry, rate limiting on the polling endpoint.

---

## Flow 4: Refresh Token

### Why it exists

Access tokens are short-lived (5 min in production) to limit damage if stolen.
But you can't ask the user to log in every 5 minutes. The refresh token solves this:

```
Access Token:   5 min lifetime  ← used in every API call (exposed)
Refresh Token:  8-24h lifetime  ← used only to get new access tokens (less exposed)
```

### The exchange

```
POST /token
  grant_type=refresh_token
  client_id=altana-web
  refresh_token=eyJhbGci...

← {
    access_token:  "new token",
    refresh_token: "new refresh token",  ← Refresh Token Rotation (RTR)
    expires_in: 300
  }
```

### Refresh Token Rotation (RTR)

Every refresh token use issues a **new refresh token** and **invalidates the old one**.

```
State at start:          RT1 (valid)
App uses RT1:            → new AT + new RT2 issued, RT1 invalidated
Attacker also uses RT1:  → REJECTED (already used) → RT2 invalidated → user logged out
```

> **INTERVIEW:** "How does Refresh Token Rotation prevent token theft?"
> → If an attacker steals RT1 and uses it after the legitimate app already used it,
>   Keycloak detects the reuse, invalidates the entire session, and the user must
>   re-authenticate. The window of vulnerability is the time between theft and first use.

### Where to store the refresh token

| Storage | Risk | Verdict |
|---------|------|---------|
| `localStorage` | XSS can read it directly | Never |
| `sessionStorage` | XSS can read it | Avoid |
| `httpOnly` cookie | XSS cannot read, CSRF risk | OK with CSRF tokens |
| Memory (`useRef`) | Lost on page refresh, XSS cannot read | Best for SPA |
| BFF (Backend-for-Frontend) | Server holds tokens, client gets session cookie | Best for security |

In our React app: access token in `useRef`, refresh token also in `useRef`.
On page refresh the user must re-authenticate (or implement silent refresh via hidden iframe).

### Interview questions

**"What is the difference between refresh token expiry and access token expiry?"**
> Access token: short (5 min), used in every API call, validated on each request.
> Refresh token: long (hours to days), used only to renew the access token.
> When the refresh token expires, the user must log in again — there is no way to
> renew it without user interaction (by design).

**"What is offline_access scope?"**
> Requesting `scope=offline_access` gives you a refresh token with a much longer
> lifetime (days to months), designed for apps that run when the user is not present
> (background sync, mobile apps). In Keycloak, offline tokens are stored in the DB
> and can be revoked individually. They are not affected by user session expiry.

---

## SAML in Keycloak — the broker pattern

In B2B, clients often have SAML-only IdPs (ADFS, legacy Azure AD).
Keycloak bridges the gap:

```
React app (OIDC/PKCE)
    │
    └─► Keycloak altana-dev  (speaks OIDC to React)
              │
              └─► Toyota IdP (ADFS)  (Keycloak speaks SAML to it)
                      │
                      └─► issues SAML Assertion
                               │
                      Keycloak validates XML signature
                      extracts NameID + Attributes
                      maps to local user
                      issues OIDC tokens to React
```

Your React app and your FastAPI backend never see XML. They only see JWTs.
Keycloak absorbs the SAML complexity.

> **INTERVIEW:** "A client says they only support SAML. How do you integrate them?"
> → Register their IdP in Keycloak as a SAML Identity Provider. Configure the
>   SP metadata (Keycloak's `/realms/altana-dev/protocol/saml/descriptor`).
>   Map SAML attributes to Keycloak user attributes via IDP mappers.
>   Your applications remain on OIDC/JWT — Keycloak handles the translation.

---

## Token validation — what you MUST verify

When a Resource Server (FastAPI, Spring) receives a token, it must validate:

```
1. SIGNATURE   → verify RS256 with Keycloak's public key (from JWKS endpoint)
                  never skip this — it's the cryptographic proof the token is real

2. exp         → Unix UTC timestamp. If exp < now() → reject (expired)

3. iss         → must equal http://keycloak-host/realms/altana-dev
                  prevents tokens from other issuers being accepted

4. aud         → must contain this service's client_id
                  prevents a token issued for service-A being used at service-B
                  (confused deputy attack)

5. nbf         → "not before" — token not valid before this time (clock skew)
```

> **INTERVIEW:** "What is the confused deputy problem in OAuth2?"
> → Service A has a token issued for its own audience. It calls Service B,
>   forwarding that same token. If B accepts tokens for any audience,
>   B is acting on behalf of A using A's permissions — B is the "confused deputy".
>   Solution: B validates `aud` and rejects tokens not explicitly issued for it.

---

## Quick-reference comparison table

| | OAuth2 | OIDC | SAML 2.0 |
|--|--------|------|----------|
| Purpose | Authorization | Authentication + Authorization | Authentication + SSO |
| Year | 2012 | 2014 | 2005 |
| Token | Access Token (opaque or JWT) | Access Token + ID Token (JWT) | XML Assertion |
| Identity claim | None standard | `sub`, `email`, `name` in id_token | `NameID` + `AttributeStatement` |
| API-friendly | Yes | Yes | No (browser-only) |
| Mobile-friendly | Yes (with PKCE) | Yes (with PKCE) | No |
| Logout | Token expiry | `/logout` endpoint (OIDC Session) | SLO (complex XML) |
| Debugging | Moderate | Easy (jwt.io) | Hard (XML, base64) |
| Keycloak native | Yes | Yes | Yes (broker + IdP) |

---

## The flows at a glance

| Flow | Has user? | Has redirect? | Token target | Use when |
|------|-----------|---------------|-------------|---------|
| Auth Code + PKCE | Yes | Yes | User token | SPA, mobile, web app |
| Client Credentials | No | No | Service token | Microservice-to-microservice |
| Device Code | Yes | No (polling) | User token | CLI, Smart TV, IoT |
| Refresh Token | Yes (renew) | No | New user token | Keep session alive |
| ~~Implicit~~ | Yes | Yes (fragment) | User token | **Deprecated — don't use** |
| ~~ROPC~~ | Yes | No | User token | **Never in production** |
