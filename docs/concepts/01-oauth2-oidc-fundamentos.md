# OAuth2 vs OIDC vs SAML – Fundamentos

> ENTREVISTA: "¿Cuál es la diferencia entre OAuth2 y OIDC?" — pregunta casi garantizada

## OAuth2 – Autorización (¿qué puedes hacer?)

OAuth2 NO es un protocolo de autenticación. Es un framework de **delegación de autorización**.
El Access Token dice: "este usuario autorizó a esta app a hacer X en su nombre".

```
Usuario → App → "Permiso para acceder a tu email" → Google → Access Token
```

El Access Token es **opaco** para la app (solo el Resource Server lo entiende).

## OIDC – Autenticación (¿quién eres?)

OpenID Connect es una **capa de identidad sobre OAuth2**.
Agrega el `id_token` (JWT) que contiene información del usuario (claims).

```
OAuth2:  Access Token (autorización)
OIDC:    Access Token + ID Token (autenticación) + /userinfo endpoint
```

### Claims estándar del ID Token:
| Claim | Descripción | Ejemplo |
|-------|-------------|---------|
| `sub` | Subject – ID único del usuario | "f47ac10b-..." |
| `iss` | Issuer – quién emitió el token | "http://localhost:8080/realms/altana-dev" |
| `aud` | Audience – para quién es el token | "altana-web" |
| `exp` | Expiration – Unix timestamp UTC | 1735689600 |
| `iat` | Issued at – cuándo se emitió | 1735686000 |
| `email` | Email del usuario | "user@altana.dev" |
| `name` | Nombre completo | "Felipe Sosa" |

## SAML – El legacy enterprise

SAML 2.0 es anterior a OAuth2. Usa XML en lugar de JSON.
Sigue siendo muy usado en empresas grandes (Salesforce, Azure AD enterprise).

| | OAuth2/OIDC | SAML |
|--|------------|------|
| Formato | JSON/JWT | XML |
| Mejor para | APIs, mobile, SPA | Enterprise SSO |
| Complejidad | Menor | Mayor (XML, firmas XML) |
| Velocidad | Más rápido | Más lento |

### Cuándo usas SAML en Keycloak:
- Integrar con sistemas enterprise del cliente (Okta, ADFS, Azure AD legacy)
- El cliente B2B exige SAML (muchas empresas grandes lo requieren)
- Identity Brokering: Keycloak recibe assertions SAML y emite tokens OIDC

## Los flujos OAuth2

### 1. Authorization Code + PKCE (el más importante)
```
App → /auth?code_challenge=XXX → Keycloak (login) → code → App → /token?code+verifier → Token
```
- **PKCE** (Proof Key for Code Exchange): protege contra interceptación del code
- El `code_verifier` es un string aleatorio; `code_challenge = SHA256(verifier)`
- **Usar siempre en**: SPA, mobile, cualquier cliente público

### 2. Client Credentials (service-to-service)
```
ServiceA → /token?client_id+client_secret → Token → llama a ServiceB
```
- Sin usuario involucrado
- El token representa al servicio, no a un usuario
- **Usar en**: microservicios, jobs, scripts automatizados

### 3. Device Code (dispositivos limitados)
```
Smart TV → /device → {device_code, user_code} → usuario va a URL en su teléfono → Token
```
- Para dispositivos sin teclado/browser
- **Usar en**: CLI tools, Smart TV, IoT

### 4. Refresh Token
```
App → /token?refresh_token=XXX → Nuevo Access Token (sin re-login)
```
- Permite renovar access tokens sin interrumpir al usuario
- El refresh token tiene lifetime más largo que el access token
- **IMPORTANTE**: si el refresh token expira, el usuario debe hacer login de nuevo

## Estructura de un JWT

Un JWT tiene 3 partes separadas por puntos:
```
eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.FIRMA
    HEADER                  PAYLOAD           SIGNATURE
```

### Cómo decodificar (debug):
```bash
# Con Python (sin verificar firma – solo para debug)
echo "eyJzdWIiOiJ1c2VyMSJ9" | base64 -d

# Con python-jose
from jose import jwt
# Decode sin verificar (SOLO DEBUG)
payload = jwt.decode(token, options={"verify_signature": False})

# Online: jwt.io
```

### IMPORTANTE sobre el campo `exp`:
- Es un **Unix timestamp en UTC** (segundos desde 1970-01-01 00:00:00 UTC)
- NO tiene información de timezone
- Para convertir: `datetime.utcfromtimestamp(exp)` en Python
- Para verificar expiración: `exp < time.time()` → expirado

```python
import time
from datetime import datetime, timezone

exp = 1735689600
print(datetime.fromtimestamp(exp, tz=timezone.utc))  # 2026-01-01 00:00:00+00:00
print("Expirado" if exp < time.time() else "Válido")
```
