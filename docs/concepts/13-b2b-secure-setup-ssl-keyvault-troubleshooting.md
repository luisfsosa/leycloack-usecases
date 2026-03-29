# B2B/B2B2C Secure Setup — SSL, KeyVault, and Auth Code Troubleshooting

---

## Secure B2B setup checklist

What "secure" means at each layer:

```
React (HTTPS)
    │
    └─► Keycloak altana-dev  (HTTPS — TLS terminated at load balancer)
              │
              └─► Toyota IDP  (HTTPS — Keycloak validates their cert)
                       │
                       └─► Returns SAML Assertion or OIDC token
                              (signed — Keycloak validates the signature)
```

| Layer | What to secure | How |
|-------|---------------|-----|
| Client → Keycloak | HTTPS only | TLS at load balancer; `sslRequired=external` in realm |
| Keycloak → External IDP | Validate IDP certificate | Truststore or load balancer CA bundle |
| IDP → Keycloak (SAML) | Validate XML signature | Upload IDP signing cert to Keycloak |
| App → Keycloak | HTTPS, validate `iss` | `issuer-uri` in Spring / PyJWT `issuer` param |
| Service → Service | mTLS or client_secret | Client Credentials + secret rotation |

### Realm SSL setting

```bash
# Force HTTPS for all external requests (not localhost)
curl -s -X PUT "$KC/admin/realms/altana-dev" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sslRequired": "external"}'

# Options:
# "none"     → no HTTPS required (dev only)
# "external" → HTTPS required for non-localhost (production minimum)
# "all"      → HTTPS required even for localhost
```

> **INTERVIEW:** "What does `sslRequired=external` do in Keycloak?"
> → It rejects HTTP requests from non-localhost origins with a 403.
>   Localhost still works over HTTP (useful for local dev and admin access
>   within the same host). In production, set `"all"` if you control every
>   access path.

---

## SSL/TLS in Keycloak — two patterns

### Pattern 1 (recommended): TLS termination at the load balancer

Keycloak runs on HTTP internally. The load balancer (nginx, AWS ALB,
Azure Application Gateway) handles TLS.

```
Internet (HTTPS :443)
    │
    ▼
Load Balancer / Ingress  ← holds the TLS certificate
    │  TLS terminated here
    │  forwards plain HTTP internally
    ▼
Keycloak (HTTP :8080)    ← never sees TLS
    │
    ▼
PostgreSQL (private network)
```

Keycloak config required (`KC_PROXY=edge` or `--proxy-headers=xforwarded`):

```yaml
# docker-compose / Kubernetes env
KC_PROXY: edge                    # tells Keycloak it's behind a proxy
KC_HOSTNAME: keycloak.altana.com  # public hostname for redirect URIs
KC_HOSTNAME_STRICT: "true"
KC_HTTP_ENABLED: "true"           # allow HTTP from the load balancer
```

```bash
# Keycloak start command
/opt/keycloak/bin/kc.sh start \
  --proxy-headers xforwarded \
  --hostname keycloak.altana.com \
  --http-enabled true
```

Why this pattern is preferred:
- Certificate rotation happens at the load balancer, zero Keycloak restart
- Java keystore management avoided
- Load balancer can integrate natively with KeyVault (see below)

### Pattern 2: Keycloak native HTTPS (Java keystore)

Used when you cannot put a load balancer in front (bare metal, simple VM).

```bash
# Generate a keystore (production: use a CA-signed cert, not self-signed)
keytool -genkeypair -alias keycloak \
  -keyalg RSA -keysize 2048 \
  -validity 365 \
  -keystore keycloak.jks \
  -storepass changeit

# Start Keycloak with HTTPS
/opt/keycloak/bin/kc.sh start \
  --https-key-store-file=/opt/keycloak/conf/keycloak.jks \
  --https-key-store-password=changeit \
  --hostname=keycloak.altana.com
```

Keycloak (Quarkus) also supports PEM files directly (simpler than JKS):

```bash
/opt/keycloak/bin/kc.sh start \
  --https-certificate-file=/opt/keycloak/conf/tls.crt \
  --https-certificate-key-file=/opt/keycloak/conf/tls.key
```

> **INTERVIEW:** "What certificate does Keycloak use for HTTPS vs SAML signing?"
> → They are different. The HTTPS certificate secures the transport layer
>   (TLS handshake). The SAML signing certificate is a separate RSA keypair
>   managed per-realm in Keycloak Admin → Realm Settings → Keys.
>   Keycloak uses the realm signing key to sign SAML assertions and JWTs.
>   The HTTPS cert is managed outside Keycloak (load balancer or keystore).

---

## Keycloak truststore — trusting external IDP certificates

When Keycloak connects to an external IDP (OIDC broker or SAML) over HTTPS,
it validates that IDP's certificate. If the IDP uses a private CA or
self-signed cert, Keycloak will reject the connection.

### Adding a trusted CA to Keycloak's truststore

```bash
# Import the IDP's CA certificate into a Java truststore
keytool -importcert \
  -alias toyota-idp-ca \
  -file toyota-corp-ca.crt \
  -keystore trustore.jks \
  -storepass changeit \
  -noprompt

# Mount it in Docker / Kubernetes and configure Keycloak
KC_SPI_TRUSTSTORE_FILE_FILE=/opt/keycloak/conf/truststore.jks
KC_SPI_TRUSTSTORE_FILE_PASSWORD=changeit
KC_SPI_TRUSTSTORE_FILE_HOSTNAME_VERIFICATION_POLICY=WILDCARD
```

Or with environment variables (Keycloak 26+):
```bash
KC_HTTPS_TRUST_STORE_FILE=/opt/keycloak/conf/truststore.jks
KC_HTTPS_TRUST_STORE_PASSWORD=changeit
```

### SAML signing certificate validation

For SAML IDPs, Keycloak validates the XML Assertion signature.
The IDP's signing certificate must be imported into the IDP configuration:

```bash
# When registering a SAML IDP:
curl -s -X POST "$KC/admin/realms/altana-dev/identity-provider/instances" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "toyota-saml",
    "providerId": "saml",
    "enabled": true,
    "config": {
      "entityId": "https://adfs.toyota.com/adfs/services/trust",
      "singleSignOnServiceUrl": "https://adfs.toyota.com/adfs/ls/",
      "signingCertificate": "MIIC...base64...",  ← Toyota ADFS signing cert
      "validateSignature": "true",               ← ALWAYS true in production
      "wantAuthnRequestsSigned": "true",
      "postBindingResponse": "true",
      "postBindingAuthnRequest": "true"
    }
  }'
```

> **INTERVIEW:** "What happens if you set `validateSignature=false` for a SAML IDP?"
> → Anyone could forge a SAML assertion claiming to be any user.
>   The XML signature is the only proof that the assertion came from the real IDP.
>   Never disable signature validation except in a sandboxed dev environment.

---

## Azure KeyVault integration — how it actually works

**Keycloak has no native Azure KeyVault plugin.** But in production you
integrate KeyVault at the infrastructure layer — Keycloak doesn't need to
know about KeyVault at all.

### Option A: Azure Application Gateway (most common)

```
Internet
    │ HTTPS with cert from KeyVault
    ▼
Azure Application Gateway
    ├─ Fetches TLS cert from Key Vault automatically
    ├─ Renews it when it expires (Azure manages rotation)
    └─ Forwards plain HTTP to Keycloak
                │
                ▼
        Keycloak (HTTP internally)
```

Setup in Azure:
```bash
# Associate a Key Vault cert with an Application Gateway listener
az network application-gateway ssl-cert create \
  --resource-group rg-altana \
  --gateway-name agw-altana \
  --name keycloak-tls-cert \
  --key-vault-secret-id "https://kv-altana.vault.azure.net/secrets/keycloak-cert"
```

Certificate rotation is automatic: Key Vault renews the cert before expiry,
Application Gateway picks up the new version with no Keycloak restart.

### Option B: Kubernetes — Azure Key Vault CSI Driver

```
Key Vault
    │ Azure Key Vault Secrets Store CSI Driver
    │ mounts secret as a file in the pod
    ▼
/mnt/secrets-store/tls.crt  ← mounted cert file
/mnt/secrets-store/tls.key  ← mounted key file
    │
    ▼
nginx sidecar (TLS termination)
    │ forwards HTTP to Keycloak on localhost:8080
    ▼
Keycloak container
```

```yaml
# Kubernetes SecretProviderClass
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: keycloak-tls-csi
spec:
  provider: azure
  parameters:
    usePodIdentity: "false"
    clientID: "<managed-identity-client-id>"
    keyvaultName: "kv-altana"
    objects: |
      array:
        - |
          objectName: keycloak-tls-cert
          objectType: secret        # Key Vault secret containing PEM
    tenantId: "<azure-tenant-id>"
---
# Deployment
spec:
  containers:
    - name: keycloak
      image: quay.io/keycloak/keycloak:26.5.6
      env:
        - name: KC_PROXY
          value: edge
    - name: nginx-tls
      image: nginx:alpine
      volumeMounts:
        - name: tls-secret
          mountPath: /etc/nginx/certs
          readOnly: true
  volumes:
    - name: tls-secret
      csi:
        driver: secrets-store.csi.k8s.io
        readOnly: true
        volumeAttributes:
          secretProviderClass: keycloak-tls-csi
```

### Option C: cert-manager (Kubernetes, automatic renewal)

```yaml
# cert-manager Certificate resource
# Fetches from Let's Encrypt or internal CA, stores as K8s Secret
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: keycloak-tls
spec:
  secretName: keycloak-tls-secret
  issuerRef:
    name: letsencrypt-prod    # or azure-keyvault-issuer
    kind: ClusterIssuer
  dnsNames:
    - keycloak.altana.com
---
# Keycloak deployment reads the K8s Secret
spec:
  containers:
    - name: keycloak
      volumeMounts:
        - name: tls
          mountPath: /opt/keycloak/conf/tls
  volumes:
    - name: tls
      secret:
        secretName: keycloak-tls-secret
```

### Keycloak Vault SPI — for client_secret, not SSL

Keycloak has an experimental Vault SPI for reading **client secrets** from
HashiCorp Vault or Azure KeyVault. This is unrelated to SSL certificates.

```bash
# Configure HashiCorp Vault provider (experimental)
KC_VAULT=hashicorp-vault
KC_VAULT_ADDR=https://vault.internal:8200
KC_VAULT_TOKEN=<token>

# Reference secret in Keycloak client config:
# client_secret = ${vault.my-service-secret}
```

For Azure KeyVault specifically, there is no official Vault SPI provider
(as of Keycloak 26). The community approach is to sync KeyVault secrets
to Kubernetes Secrets using the CSI driver, then Keycloak reads them as files.

> **INTERVIEW:** "How would you integrate Azure KeyVault with Keycloak in production?"
> → I wouldn't integrate KeyVault directly into Keycloak. Instead, I'd let the
>   infrastructure handle it. For SSL/TLS, use Azure Application Gateway which
>   natively fetches and rotates certs from Key Vault — Keycloak runs on HTTP
>   behind it. For client secrets, use the Kubernetes CSI driver to mount Key Vault
>   secrets as files, which Keycloak env vars reference. This keeps Keycloak
>   stateless and decoupled from the secrets store.

---

## Troubleshooting Authorization Code flow with curl — step by step

The challenge: the Authorization Code flow requires a browser for the login step.
curl alone cannot fill in the login form. The practical approach is:

```
STEP 1: Build the auth URL (curl-compatible)
STEP 2: Open in browser OR use curl with form submission (for non-interactive testing)
STEP 3: Capture the code from the redirect
STEP 4: Exchange code → token with curl
STEP 5: Inspect and validate the token
```

### Complete troubleshooting script

```bash
#!/usr/bin/env bash
# Full Authorization Code + PKCE flow via curl + Python

KC="http://localhost:8080"
REALM="altana-dev"
CLIENT_ID="altana-web"
REDIRECT_URI="http://localhost:3000/callback"

# ─── STEP 1: Generate PKCE ───────────────────────────────────────────────────
CODE_VERIFIER=$(python3 -c "
import secrets, base64
print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode())
")

CODE_CHALLENGE=$(python3 -c "
import hashlib, base64
v = '$CODE_VERIFIER'
d = hashlib.sha256(v.encode()).digest()
print(base64.urlsafe_b64encode(d).rstrip(b'=').decode())
")

STATE=$(python3 -c "import secrets; print(secrets.token_urlsafe(16))")

echo "verifier:  $CODE_VERIFIER"
echo "challenge: $CODE_CHALLENGE"
echo "state:     $STATE"

# ─── STEP 2: Build authorization URL ────────────────────────────────────────
AUTH_URL="$KC/realms/$REALM/protocol/openid-connect/auth\
?client_id=$CLIENT_ID\
&response_type=code\
&scope=openid%20profile%20email\
&redirect_uri=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$REDIRECT_URI'))")\
&code_challenge=$CODE_CHALLENGE\
&code_challenge_method=S256\
&state=$STATE"

echo ""
echo "Open this URL in your browser:"
echo "$AUTH_URL"
echo ""
echo "After login, Keycloak will redirect to:"
echo "  $REDIRECT_URI?code=XXXX&state=$STATE"
echo "Copy the 'code' value."
echo ""
read -p "Paste the code here: " AUTH_CODE

# ─── STEP 3: Exchange code for tokens ────────────────────────────────────────
echo ""
echo "Exchanging code for tokens..."
RESPONSE=$(curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=$CLIENT_ID" \
  -d "code=$AUTH_CODE" \
  -d "redirect_uri=$REDIRECT_URI" \
  -d "code_verifier=$CODE_VERIFIER")

echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "ERROR: $RESPONSE"

# ─── STEP 4: Decode the access_token ─────────────────────────────────────────
ACCESS_TOKEN=$(echo "$RESPONSE" | python3 -c "import sys,json; t=json.load(sys.stdin); print(t.get('access_token',''))" 2>/dev/null)

if [ -n "$ACCESS_TOKEN" ]; then
  echo ""
  echo "=== ACCESS TOKEN PAYLOAD ==="
  echo "$ACCESS_TOKEN" | cut -d'.' -f2 | python3 -c "
import sys,base64,json
p=sys.stdin.read().strip(); p+='='*(4-len(p)%4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(p)),indent=2))"
fi
```

### Diagnose specific errors

#### `invalid_grant` — most common error

```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token" \
  -d "grant_type=authorization_code&client_id=altana-web&code=EXPIRED_CODE&..."

# Response:
{"error":"invalid_grant","error_description":"Code not valid"}
```

Causes and fixes:

| Cause | Symptom | Fix |
|-------|---------|-----|
| Code expired (>60s) | `Code not valid` | Re-run the flow, exchange within 60 seconds |
| Code already used | `Code not valid` | Each code is one-time use |
| Wrong `code_verifier` | `Code not valid` | Verifier must match the challenge sent in Step 1 |
| Wrong `redirect_uri` | `Code not valid` | Must be byte-for-byte identical to Step 1 |

#### `redirect_uri_mismatch`

```json
{"error":"invalid_redirect_uri","error_description":"Invalid redirect_uri"}
```

```bash
# Check what redirect URIs are allowed on the client
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/clients?clientId=altana-web" \
  | python3 -c "import sys,json; c=json.load(sys.stdin)[0]; print(c['redirectUris'])"

# Fix: add the URI (wildcard for dev only — never in production)
curl -s -X PUT "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"redirectUris": ["http://localhost:5173/*", "http://localhost:3000/*"]}'
```

#### `unauthorized_client` — flow not enabled

```json
{"error":"unauthorized_client","error_description":"Client not allowed to initiate browser login"}
```

```bash
# Enable Authorization Code flow on the client
curl -s -X PUT "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"standardFlowEnabled": true}'
```

#### `invalid_client` — wrong client_id or secret

```json
{"error":"invalid_client","error_description":"Invalid client credentials"}
```

For a confidential client, the secret must be included:
```bash
-d "client_id=supply-chain-backend" \
-d "client_secret=$KEYCLOAK_CLIENT_SECRET"
```

#### PKCE error — missing or wrong challenge method

```json
{"error":"invalid_request","error_description":"Missing parameter: code_challenge_method"}
```

The client has PKCE enforced (`pkce.code.challenge.method=S256`) but the URL
didn't include `code_challenge_method=S256`. Both parameters are required:
```
&code_challenge=E9Melhoa...
&code_challenge_method=S256     ← often forgotten
```

#### State mismatch — CSRF protection

The `state` in the callback doesn't match what you sent:
```
callback?code=XXX&state=DIFFERENT_VALUE
```

This means either:
1. You have two login flows running simultaneously (two tabs)
2. The `state` wasn't stored before the redirect (race condition in the app)
3. Actual CSRF attack — reject the code and do not exchange it

### Curl-only flow (using Resource Owner Password — DEV ONLY)

When you just need a token fast for API testing and don't want a browser:

```bash
# WARNING: Resource Owner Password flow — testing only, never production
# It bypasses MFA, SSO, and breaks the security model
TOKEN=$(curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=altana-web" \
  -d "username=analyst-user" \
  -d "password=Analyst123!" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Use it
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/supply-chain/me
```

> **INTERVIEW:** "You used Resource Owner Password in your curl tests. Is that safe?"
> → No, only for local development testing. ROPC sends the user's password
>   directly to the application — it bypasses MFA, breaks SSO (no session in
>   Keycloak), and violates the principle that the app should never see the
>   password. In CI/CD, use Client Credentials (service account) instead.
>   In production, never enable ROPC (`directAccessGrantsEnabled: false`).

### Inspecting what Keycloak received — event log

After any failed flow, check what Keycloak logged:

```bash
# Get recent events (requires eventsEnabled=true on the realm)
ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/events?type=LOGIN_ERROR&max=10" \
  | python3 -m json.tool

# Or stream from logs
docker logs altana-keycloak 2>&1 | grep "ALTANA-AUDIT" | tail -20
```

---

## B2B/B2B2C additional security hardening

### Token lifetime configuration (production values)

```bash
curl -s -X PUT "$KC/admin/realms/altana-dev" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accessTokenLifespan":          300,
    "ssoSessionMaxLifespan":        28800,
    "ssoSessionIdleTimeout":        1800,
    "accessCodeLifespan":           60,
    "refreshTokenMaxReuse":         0
  }'
```

| Setting | Value | Meaning |
|---------|-------|---------|
| `accessTokenLifespan` | 300s (5 min) | Short — limits damage if access token is stolen |
| `ssoSessionMaxLifespan` | 28800s (8h) | Full work day session |
| `ssoSessionIdleTimeout` | 1800s (30 min) | Idle logout |
| `accessCodeLifespan` | 60s | Authorization code valid for 60s only |
| `refreshTokenMaxReuse` | 0 | Refresh Token Rotation enforced (0 = no reuse) |

### Disabling unsafe flows on all clients

```bash
# For every client: disable flows you don't use
curl -s -X PUT "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "directAccessGrantsEnabled": false,
    "implicitFlowEnabled":        false,
    "serviceAccountsEnabled":     false
  }'
```

### mTLS for service-to-service (alternative to client_secret)

Instead of sharing a `client_secret`, the client presents an X.509 certificate:

```bash
# Configure mTLS on a confidential client
curl -s -X PUT "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientAuthenticatorType": "client-jwt",
    "attributes": {
      "token.endpoint.auth.signing.alg": "RS256",
      "use.jwks.url": "true",
      "jwks.url": "https://my-service.internal/certs"
    }
  }'

# The service signs a JWT with its private key instead of sending a secret
# No secret to rotate, no secret to leak
```

> **INTERVIEW:** "How would you secure service-to-service calls in production?"
> → Client Credentials flow with either a strong client_secret stored in
>   a secrets manager (KeyVault / HashiCorp Vault) and rotated regularly,
>   OR mTLS — the service authenticates with an X.509 certificate signed by
>   an internal CA. mTLS is stronger because there's no shared secret that
>   can be exfiltrated; compromise requires stealing the private key.

---

## Interview Q&A — rapid fire

**Q: A B2B client says their JWT token is being rejected by your API. Walk me through troubleshooting.**
> 1. Decode the token (no verification): check `exp`, `iss`, `aud`
> 2. Is it expired? → client needs to refresh
> 3. Does `iss` match `http://keycloak-host/realms/altana-dev`? → if not, wrong realm or wrong Keycloak
> 4. Does `aud` contain our service identifier? → add Audience mapper in Keycloak if missing
> 5. Does `realm_access.roles` contain the required role? → check IDP mappers in broker config
> 6. Try the token against introspection endpoint → if active=false, session was revoked
> 7. Check Keycloak event logs for LOGIN_ERROR or the specific user session

**Q: How do you handle certificate expiry in a B2B SAML integration?**
> SAML signing certificates have a validity period. When the Toyota ADFS cert expires:
> 1. Toyota sends the new signing cert to you
> 2. In Keycloak Admin, go to the toyota-corp IDP → import the new cert
> 3. During the rotation window, you can have both the old and new cert configured
>    so logins aren't interrupted while Toyota switches over
> The SSL/TLS cert on the IDP endpoint is separate — handled by Toyota's infra team.

**Q: What is the difference between frontchannel and backchannel logout?**
> **Frontchannel logout**: Keycloak redirects the user's browser to each app's
> logout URL. The app invalidates its session. Depends on the browser being open.
>
> **Backchannel logout**: Keycloak sends an HTTP POST directly to each app's
> backchannel logout endpoint (server-to-server, no browser involved). More reliable —
> works even if the user closed the browser. Configure in Keycloak client settings:
> `backchannelLogoutUrl = https://my-app.com/logout/back-channel`

**Q: You have 50 B2B enterprise clients. One realm or one realm per client?**
> Depends on isolation requirements:
> - **One realm, one IDP per client** — simpler to manage, shared infrastructure.
>   Risk: a misconfigured IDP or mapper could affect other clients.
> - **One realm per client** — full isolation, each client has their own users/roles/settings.
>   Used when clients have strict data residency or regulatory requirements (HIPAA, etc.)
>
> In most SaaS B2B deployments: single realm with one IDP per client.
> Use Keycloak Organizations feature (24+) for tenant-level isolation within one realm.
