# Authorization Code Flow + PKCE con curl

## El flujo completo (diagrama)

```
Usuario     Browser/App         Keycloak              Tu API
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

## PASO 1 — Generar PKCE (code_verifier + code_challenge)

PKCE protege el Authorization Code contra interceptación.
- `code_verifier`: string aleatorio (43-128 chars, URL-safe)
- `code_challenge`: BASE64URL(SHA256(code_verifier))

```bash
# Generar code_verifier (32 bytes random → base64url)
CODE_VERIFIER=$(python3 -c "
import secrets, base64
verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode()
print(verifier)
")
echo "CODE_VERIFIER: $CODE_VERIFIER"

# Generar code_challenge = BASE64URL(SHA256(verifier))
CODE_CHALLENGE=$(python3 -c "
import hashlib, base64, sys
verifier = '$CODE_VERIFIER'
digest = hashlib.sha256(verifier.encode()).digest()
challenge = base64.urlsafe_b64encode(digest).rstrip(b'=').decode()
print(challenge)
")
echo "CODE_CHALLENGE: $CODE_CHALLENGE"
```

## PASO 2 — Construir la Authorization URL y abrir en browser

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

echo "Abre esta URL en el browser:"
echo $AUTH_URL
```

Keycloak redirige a: `http://localhost:3000/callback?code=XXXX&state=random-state-123`
→ Copia el valor de `code=` de la URL (dura ~60 segundos)

## PASO 3 — Intercambiar el code por tokens

```bash
CODE="PEGA-EL-CODE-AQUI"

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

### Respuesta esperada:
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

## PASO 4 — Inspeccionar el JWT (sin librería)

Un JWT tiene 3 partes: HEADER.PAYLOAD.SIGNATURE

```bash
ACCESS_TOKEN="PEGA-EL-ACCESS-TOKEN-AQUI"

# Extraer y decodificar el PAYLOAD (parte del medio)
echo $ACCESS_TOKEN | cut -d'.' -f2 | \
  python3 -c "
import sys, base64, json
payload = sys.stdin.read().strip()
# Agregar padding si falta
padding = 4 - len(payload) % 4
if padding != 4:
    payload += '=' * padding
decoded = base64.urlsafe_b64decode(payload)
print(json.dumps(json.loads(decoded), indent=2))
"
```

### Claims importantes que verás:
```json
{
  "exp": 1735689600,        ← Unix timestamp UTC (ENTREVISTA: no tiene timezone)
  "iat": 1735686000,        ← Issued at
  "iss": "http://localhost:8080/realms/altana-dev",  ← Issuer
  "sub": "f47ac10b-...",    ← Subject (user ID en Keycloak)
  "aud": "altana-web",      ← Audience
  "typ": "Bearer",
  "azp": "altana-web",      ← Authorized party (client que pidió el token)
  "realm_access": {
    "roles": ["ROLE_USER", "ROLE_ADMIN"]   ← Roles del usuario
  },
  "email": "admin@altana.dev",
  "name": "Admin User"
}
```

## PASO 5 — Renovar con Refresh Token

```bash
REFRESH_TOKEN="PEGA-EL-REFRESH-TOKEN-AQUI"

curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token" \
  -d "client_id=${CLIENT_ID}" \
  -d "refresh_token=${REFRESH_TOKEN}" \
  | python3 -m json.tool
```

## PASO 6 — Client Credentials (service-to-service, sin usuario)

```bash
# supply-chain-backend es un cliente confidencial (tiene secret)
curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=CHANGE-ME-IN-PRODUCTION" \
  | python3 -m json.tool
```

## PASO 7 — Introspección de token (verificar si es válido)

```bash
curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token/introspect" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "supply-chain-backend:CHANGE-ME-IN-PRODUCTION" \
  -d "token=${ACCESS_TOKEN}" \
  | python3 -m json.tool
# Si "active": true → token válido
# Si "active": false → expirado o inválido
```

## Errores frecuentes y cómo diagnosticarlos

| Error HTTP | Mensaje | Causa |
|-----------|---------|-------|
| 400 | `invalid_grant` | Code ya usado o expirado (>60 seg) |
| 400 | `invalid_grant` | `code_verifier` no coincide con `code_challenge` |
| 400 | `invalid_client` | `client_id` incorrecto |
| 401 | `unauthorized_client` | Cliente no tiene el flujo habilitado |
| 400 | `redirect_uri_mismatch` | `redirect_uri` no está en la lista permitida |

## Discovery Document (autoconfiguración)

Keycloak publica todos sus endpoints en:
```
GET http://localhost:8080/realms/altana-dev/.well-known/openid-configuration
```

```bash
curl -s http://localhost:8080/realms/altana-dev/.well-known/openid-configuration \
  | python3 -m json.tool | head -40
```

Devuelve: authorization_endpoint, token_endpoint, jwks_uri, etc.
Los frameworks (Spring Security, FastAPI) usan esta URL para auto-configurarse.
