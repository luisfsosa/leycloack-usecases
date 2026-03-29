# B2B and B2B2C Login UX Patterns in React

> **Core question:** if I have multiple enterprise clients (Toyota, Ford, BMW),
> how does my React app know which Identity Provider to send the user to?

---

## Pattern 1: IDP list with logos

```
┌─────────────────────────────────┐
│  Sign in to Altana              │
│                                 │
│  [🔵 Continue with Toyota]      │
│  [🟢 Continue with Ford]        │
│  [🔴 Continue with BMW]         │
│                                 │
│  ──── or ────                   │
│  [Username / Password]          │
└─────────────────────────────────┘
```

React hardcodes the known clients. Each button starts the PKCE flow
with `kc_idp_hint=toyota-corp` to go directly to the IDP without passing through
the Keycloak login screen.

**When to use:** few known clients (< 10), SaaS with a closed list.

---

## Pattern 2: Email domain discovery

```
┌─────────────────────────────────┐
│  Your corporate email:          │
│  [john@toyota.com        ]      │
│  [Continue →]                   │
└─────────────────────────────────┘
        ↓ React detects @toyota.com
        ↓ maps to kc_idp_hint=toyota-corp
        ↓ redirects directly to Toyota's IDP
```

React maintains a `domain → IDP alias` map. The user types their email,
React extracts the domain and knows which IDP to send them to. If the domain
is not in the map, it falls back to the generic Keycloak login.

**When to use:** many clients, non-technical users, flow like
Google Workspace or Microsoft 365.

### Implementation

```js
// Domain → Keycloak IDP alias map
const DOMAIN_IDP_MAP = {
  'toyota.com': 'toyota-corp',
  'ford.com':   'ford-corp',
  'bmw.com':    'bmw-corp',
};

function getIdpHint(email) {
  const domain = email.split('@')[1]?.toLowerCase();
  return DOMAIN_IDP_MAP[domain] ?? null;  // null → generic Keycloak login
}

// In the form submit handler:
function handleSubmit(e) {
  e.preventDefault();
  const hint = getIdpHint(email);
  login(hint);  // login(null) → no kc_idp_hint → Keycloak login page
}
```

The `kc_idp_hint` parameter is added to the PKCE authorization URL:
```
/auth?...&kc_idp_hint=toyota-corp
```
Keycloak skips its login screen and redirects immediately to the Toyota IdP.

> **INTERVIEW:** "What happens if the domain is not recognized?"
> → `getIdpHint` returns `null`, and `startLogin` is called without `kc_idp_hint`.
>   The user lands on the standard Keycloak login page where they can enter
>   credentials directly or see any other configured IDPs.

---

## Pattern 3: Per-client subdomain (white-label)

```
toyota.altana.com  →  kc_idp_hint=toyota-corp  →  Toyota Login
ford.altana.com    →  kc_idp_hint=ford-corp    →  Ford Login
app.altana.com     →  generic selector
```

React reads `window.location.hostname` on startup. If it detects a known
subdomain, it starts the PKCE flow already with `kc_idp_hint` set. The Toyota
end user never sees a selection screen — they go directly to their SSO.

**When to use:** enterprise clients that want their own URL, portals
with client branding (client's colors and logo in the app).

---

## B2B2C: one app or multiple?

### Option A: Single app, conditional UI by `user_type`

Login is the same for everyone. After the callback, React reads the
JWT claims (`user_type`, `roles`) and renders the corresponding UI.

```
same login URL
    ↓
JWT with user_type=employee → analyst dashboard
JWT with user_type=customer → end customer dashboard
```

**When to use:** the difference between employee and customer is only
permissions and view, the app itself is the same.

### Option B: Dedicated portal per user type

```
app.altana.com          → internal employees / analysts
toyota.altana.com       → Toyota end customers portal
  └── kc_idp_hint=toyota-corp hardcoded
  └── Toyota branding (logo, colors)
```

The Toyota portal uses a fixed `kc_idp_hint`. The end user doesn't even know
that Keycloak exists behind it.

**When to use:** the experience is different enough to justify a separate
app, or the client pays for full white-label.

---

## Summary: which pattern to use?

| Situation | Pattern |
|-----------|---------|
| Few known clients | Button list with logos |
| Many clients, non-technical users | Email domain discovery |
| Enterprise clients with their own URL | Subdomain + fixed `kc_idp_hint` |
| B2B2C same app, different roles | Single app, conditional UI by `user_type` |
| B2B2C dedicated portal per client | Separate subdomain + white-label |

---

## Interview question

**"How does your React app know which Identity Provider to send the user to?"**

> It depends on the context. For a SaaS with a closed client list, we show
> buttons per IDP using `kc_idp_hint`. For self-service flows with many
> clients we use email domain discovery: the user types their email,
> we extract the domain and map it to the IDP alias in Keycloak.
> For enterprise clients with white-label we use a dedicated subdomain —
> `toyota.altana.com` carries `kc_idp_hint=toyota-corp` hardcoded and the
> user never sees a selection screen.
> In all cases Keycloak handles the OAuth2/OIDC protocol —
> React only decides which `kc_idp_hint` to start the PKCE flow with.
