# Altana – Keycloak Learning & Integration Project

## Rol de Claude en este proyecto
Actúas como **arquitecto experto + profesor** en Keycloak, IAM, OAuth2/OIDC y seguridad.
- Explica los conceptos mientras escribes código
- Indica qué aprende el usuario en cada paso
- Señala preguntas de entrevista relacionadas con cada tema
- El objetivo final: preparar al usuario para un rol de Keycloak + Python en empresa de supply-chain analytics

## REGLA CRÍTICA — Nunca asumir, siempre verificar contra la versión en uso

**Keycloak cambia APIs, nombres de providers y comportamientos entre versiones.**
Antes de usar cualquier mapper type, endpoint, configuración o feature de Keycloak:

1. **Verificar los tipos disponibles via API** — no asumir que existen por documentación genérica:
   ```bash
   # Mapper types disponibles para un IDP:
   GET /admin/realms/{realm}/identity-provider/instances/{alias}/mapper-types

   # Authenticator providers disponibles:
   GET /admin/realms/{realm}/authentication/authenticator-providers

   # Protocol mapper types:
   GET /admin/realms/{realm}/protocol-mappers/providers-per-protocol
   ```

2. **Consultar la documentación de la versión exacta** — la versión en este proyecto es
   **Keycloak 26.5.6**. Documentación:
   - API REST: `https://www.keycloak.org/docs-api/26.5/rest-api/`
   - Guía de administración: `https://www.keycloak.org/docs/26.5/server_admin/`
   - Migration guide (cambios entre versiones): `https://www.keycloak.org/docs/26.5/upgrading/`

3. **Lección aprendida — caso real en este proyecto:**
   - `hardcoded-role-idp-mapper` → NO existe en Keycloak 26
   - `oidc-hardcoded-role-idp-mapper` → nombre correcto en Keycloak 26
   - El config `role` acepta el **nombre** del rol, no el UUID
   - Estos errores costaron múltiples intentos fallidos
   - **Siempre consultar `mapper-types` antes de crear un mapper via API**

---

---

## Estructura del proyecto

```
altana/
├── spring-app/          # USE CASE 1: Spring Boot + Keycloak como IdP
├── keycloak-extension/  # USE CASE 2: Keycloak SPI (custom authenticator, listener, mapper)
├── python-app/          # USE CASE 3: Python/FastAPI + Keycloak (foco del rol)
├── docker/              # Keycloak + PostgreSQL local dev
└── docs/concepts/       # Explicaciones teóricas y cheat sheets
```

---

## Casos de uso a construir (en orden)

### UC1 – Spring Boot como Resource Server
- App Spring Boot protegida por Keycloak (OAuth2 Resource Server)
- Valida JWT tokens emitidos por Keycloak
- Demuestra: token validation, role-based access, scope handling

### UC2 – Keycloak SPI Extension
- Custom Authenticator (flujo de login personalizado)
- Event Listener (auditoría de eventos de login/logout)
- Protocol Mapper (agregar claims custom al JWT)
- Demuestra: extensibilidad de Keycloak a nivel SPI

### UC3 – Python/FastAPI + Keycloak (FOCO PRINCIPAL)
- FastAPI con protección via Bearer token
- PKCE flow para frontend/mobile
- Client Credentials para service-to-service
- B2B y B2B2C patterns
- Demuestra: integración Python real como en el rol objetivo

---

## Reglas de seguridad (NUNCA violar)

### Secrets y credenciales
- NUNCA hardcodear client_secret en código o git
- Usar variables de entorno: `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_URL`
- En producción: usar HashiCorp Vault o Azure KeyVault para secrets
- El archivo `.env` NUNCA se commitea (ya está en .gitignore)

### Configuración de clientes Keycloak
- Clientes públicos (frontend/mobile): SIEMPRE usar PKCE (`S256`)
- Clientes confidenciales (backend): SIEMPRE usar client_secret + mTLS si es posible
- NUNCA habilitar `Direct Access Grants` (Resource Owner Password) en producción
- SIEMPRE configurar `Valid Redirect URIs` con URLs exactas (no wildcards en prod)
- SIEMPRE configurar `Web Origins` para CORS

### Tokens JWT
- Access token lifetime: máximo 5 minutos en producción
- Refresh token lifetime: según el caso de uso (típico: 30 min a 8 horas)
- NUNCA almacenar tokens en localStorage (usar httpOnly cookies)
- SIEMPRE validar: signature, `exp`, `iss`, `aud`
- El campo `exp` en JWT usa **UTC Unix timestamp** (no tiene timezone, es UTC)

### SSL/TLS
- NUNCA deshabilitar verificación SSL en producción
- En desarrollo local: usar `verify=False` solo con flag explícito y comentario
- En Keycloak: configurar HTTPS en el realm (no solo en el proxy)

---

## Convenciones de naming

### Realms
- Formato: `{cliente}-{ambiente}` → `altana-dev`, `altana-prod`
- Un realm por cliente en B2B (aislamiento de tenants)
- Realm `master` NUNCA para aplicaciones (solo admin)

### Clients
- Backend services: `{servicio}-backend` → `supply-chain-backend`
- Frontend apps: `{app}-web` → `altana-web`
- Service accounts: `{servicio}-sa` → `notification-sa`
- Mobile: `{app}-mobile` → `altana-mobile`

### Roles
- Roles de realm (globales): `ROLE_ADMIN`, `ROLE_USER`
- Roles de client (específicos): `supply-chain:read`, `supply-chain:write`
- Grupos para asignación masiva, roles para permisos específicos

---

## Flujos OAuth2 – cuándo usar cada uno

| Flujo | Cuándo usarlo | Ejemplo |
|-------|---------------|---------|
| **Authorization Code + PKCE** | Frontend, mobile, cualquier cliente público | React app, app móvil |
| **Authorization Code** (confidential) | Web app con backend propio | Spring Boot MVC |
| **Client Credentials** | Service-to-service, sin usuario | Microservicio A → B |
| **Device Code** | Dispositivos sin browser | Smart TV, CLI tools |
| **Refresh Token** | Renovar access token sin re-autenticar | Todos los flujos anteriores |
| ~~Resource Owner Password~~ | **NUNCA en producción** | Solo testing interno |

---

## Preguntas de entrevista frecuentes (practicar respuestas)

### Protocolos
- ¿Cuál es la diferencia entre OAuth2 y OIDC?
  → OAuth2 es autorización (qué puedes hacer), OIDC es autenticación (quién eres). OIDC agrega `id_token` (JWT) y el endpoint `/userinfo` sobre OAuth2.
- ¿Cuándo usas SAML vs OIDC?
  → SAML: enterprise legacy, SSO corporativo basado en XML. OIDC: moderno, JSON/JWT, mejor para APIs y móvil.

### JWT
- ¿Qué timezone usa el campo `exp` en un JWT?
  → UTC Unix timestamp (segundos desde 1970-01-01 00:00:00 UTC). No tiene timezone.
- ¿Cómo debuggeas un JWT?
  → `jwt.io` o `python-jose`/`PyJWT` para decode. Verificar: firma, exp, iss, aud.
- ¿Cómo troubleshooteas un JWT expirado en curl?
  → Inspeccionar el 401, hacer `curl` al endpoint `/token` con refresh_token para renovar.

### Keycloak
- ¿Qué es un SPI en Keycloak?
  → Service Provider Interface – mecanismo de extensión de Keycloak. Permite custom authenticators, event listeners, protocol mappers.
- ¿Cómo configuras B2B en Keycloak?
  → Identity Brokering: realm maestro federa realms de clientes externos vía OIDC/SAML. Cada cliente B2B tiene su propio realm o IDP.

---

## Comandos frecuentes de desarrollo

### Docker – iniciar ambiente local
```bash
cd docker && docker compose up -d
# Keycloak: http://localhost:8080 (admin/admin)
# PostgreSQL: localhost:5432
```

### Troubleshoot Authorization Code Flow con curl
```bash
# Paso 1: obtener authorization code (abrir en browser)
# http://localhost:8080/realms/{realm}/protocol/openid-connect/auth?
#   client_id=altana-web&response_type=code&scope=openid&
#   redirect_uri=http://localhost:3000/callback&code_challenge=XXX&code_challenge_method=S256

# Paso 2: intercambiar code por token
curl -X POST http://localhost:8080/realms/{realm}/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=altana-web" \
  -d "code={CODE}" \
  -d "redirect_uri=http://localhost:3000/callback" \
  -d "code_verifier={VERIFIER}"

# Client Credentials (service-to-service)
curl -X POST http://localhost:8080/realms/{realm}/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=supply-chain-backend" \
  -d "client_secret=${KEYCLOAK_CLIENT_SECRET}"

# Inspeccionar token (decode base64)
echo "{JWT_PAYLOAD}" | base64 -d | python -m json.tool
```

### Python – verificar token
```python
# Instalar: pip install python-jose[cryptography] httpx
from jose import jwt
import httpx

# Obtener JWKS de Keycloak
KEYCLOAK_URL = "http://localhost:8080"
REALM = "altana-dev"
jwks = httpx.get(f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/certs").json()

# Validar token
payload = jwt.decode(token, jwks, algorithms=["RS256"], audience="altana-web")
```

---

## B2B y B2B2C patterns

### B2B (empresa-a-empresa)
- Cada empresa cliente tiene su propio Identity Provider (Okta, Azure AD, etc.)
- Keycloak actúa como **Identity Broker**: federa los IDPs externos
- Configurar: `Identity Providers` → OIDC/SAML del cliente → mapear atributos a roles locales
- SSL: siempre usar certificados válidos en el IDP externo
- KeyVault: se puede integrar Azure KeyVault para gestionar certificados SAML/SSL vía scripts de admin

### B2B2C (empresa → empresa → consumidor final)
- Capa 1: empresa cliente autentica con su IDP corporativo (B2B)
- Capa 2: usuarios finales de la empresa cliente usan login de Keycloak
- Implementar con: Organization feature (Keycloak 24+) o múltiples realms
- Requiere: mapeo de roles entre capas, token enrichment via protocol mapper

---

## Stack tecnológico del proyecto

| Módulo | Stack | Versión |
|--------|-------|---------|
| Keycloak | Docker (quay.io/keycloak/keycloak) | 26.5.6 |
| spring-app | Java 21, Spring Boot, Spring Security OAuth2 | Latest |
| keycloak-extension | Java 21, Keycloak SPI API | 26.5.6 |
| python-app | Python 3.12, FastAPI, python-jose, httpx | Latest |
| DB | PostgreSQL | 16 |

---

## Orden de aprendizaje recomendado

1. **Levantar Keycloak local** → entender la UI de admin, crear realm, client, user
2. **Authorization Code Flow con curl** → entender el flujo completo a mano
3. **UC3 Python/FastAPI** → proteger endpoints con JWT (foco del rol)
4. **JWT deep dive** → decode, validation, claims, expiry
5. **UC1 Spring Boot** → Resource Server, role-based access
6. **B2B/B2B2C patterns** → Identity Brokering en Keycloak
7. **UC2 SPI Extension** → custom authenticator y protocol mapper
8. **SSL y producción** → HTTPS, certificates, KeyVault integration
