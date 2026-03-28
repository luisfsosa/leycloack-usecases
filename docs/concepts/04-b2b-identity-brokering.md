# Patrón B2B — Identity Brokering con Keycloak

> **Escenario:** Una empresa cliente (ej: Toyota) tiene su propio IdP.
> Sus empleados deben acceder a Altana **sin crear nuevas credenciales**.
> Keycloak actúa como **broker**: acepta el login del IdP externo y emite
> sus propios tokens para la aplicación Altana.

---

## ¿Qué es Identity Brokering?

```
Usuario Toyota           Keycloak altana-dev         Toyota IDP
      │                         │                         │
      │── click "Login Toyota" ─►│                         │
      │                         │── redirect auth request ►│
      │◄── redirect to Toyota ──│                         │
      │                                                   │
      │── login toyota123 ────────────────────────────────►│
      │◄── authorization code ───────────────────────────── │
      │                                                   │
      │── code ────────────────►│                         │
      │                         │── exchange code ────────►│
      │                         │◄── id_token + access ───│
      │                         │                         │
      │                         │ [IDP mappers ejecutados]│
      │                         │ → email: john@toyota.com│
      │                         │ → role: ROLE_ANALYST    │
      │                         │                         │
      │◄── Altana tokens ───────│                         │
```

**Keycloak resuelve:**
- Autenticación delegada al IdP externo
- Traducción de identidad (claims del IDP → claims Altana)
- Asignación automática de roles según el IDP de origen
- Una sola sesión SSO en Altana aunque el user autentique en Toyota

---

## Conceptos clave

### syncMode: IMPORT vs FORCE

| Modo | Comportamiento |
|------|---------------|
| `IMPORT` | Copia atributos del IDP **solo en el primer login** (creación de usuario en Altana) |
| `FORCE`  | Sobreescribe atributos en **cada login** desde el IDP (sync continuo) |

> ENTREVISTA: "¿Cuándo usarías FORCE vs IMPORT?"
> → FORCE cuando el IDP es la fuente de verdad (nombre, email corporativo, roles).
>   IMPORT cuando Altana puede modificar atributos localmente después del primer login.

### kc_idp_hint

Parámetro que le dice a Keycloak **qué IDP usar directamente**, saltándose la
pantalla de selección de IDP.

```
&kc_idp_hint=toyota-corp
```

> En un portal B2B con subdominio por empresa:
> `toyota.altana.com` → siempre añade `kc_idp_hint=toyota-corp`
> `ford.altana.com`   → siempre añade `kc_idp_hint=ford-corp`

---

## Setup completo — paso a paso con Admin API

### 1. Crear el realm "toyota-corp" (simula el IdP externo)

```bash
KC="http://localhost:8080"
ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s -X POST "$KC/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "realm": "toyota-corp",
    "displayName": "Toyota Corporation IDP",
    "enabled": true,
    "sslRequired": "external",
    "accessTokenLifespan": 300
  }'
```

### 2. Crear usuario de prueba en toyota-corp

```bash
curl -s -X POST "$KC/admin/realms/toyota-corp/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john.doe",
    "email": "john.doe@toyota.com",
    "firstName": "John",
    "lastName": "Doe",
    "enabled": true,
    "emailVerified": true
  }'

# Obtener su ID
TOYOTA_USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/toyota-corp/users?username=john.doe&exact=true" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")

# Asignar password
curl -s -X PUT "$KC/admin/realms/toyota-corp/users/$TOYOTA_USER_ID/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type": "password", "value": "toyota123", "temporary": false}'
```

### 3. Crear cliente altana-broker en toyota-corp

Este cliente representa a Altana como consumer del IdP Toyota.

```bash
curl -s -X POST "$KC/admin/realms/toyota-corp/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "altana-broker",
    "publicClient": false,
    "secret": "altana-broker-secret",
    "standardFlowEnabled": true,
    "redirectUris": ["http://localhost:8080/realms/altana-dev/broker/toyota-corp/endpoint"]
  }'
```

> El `redirectUri` es el endpoint del broker de Keycloak:
> `/realms/{consumer-realm}/broker/{idp-alias}/endpoint`
> Keycloak lo genera automáticamente al registrar el IDP.

### 4. Registrar toyota-corp como IDP en altana-dev

```bash
curl -s -X POST "$KC/admin/realms/altana-dev/identity-provider/instances" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "toyota-corp",
    "displayName": "Login con Toyota",
    "providerId": "oidc",
    "enabled": true,
    "trustEmail": true,
    "config": {
      "issuer": "http://localhost:8080/realms/toyota-corp",
      "authorizationUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/auth",
      "tokenUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/token",
      "jwksUrl": "http://localhost:8080/realms/toyota-corp/protocol/openid-connect/certs",
      "clientId": "altana-broker",
      "clientSecret": "altana-broker-secret",
      "defaultScope": "openid profile email",
      "validateSignature": "true",
      "useJwksUrl": "true",
      "syncMode": "FORCE"
    }
  }'
```

### 5. Agregar mappers al IDP

#### Mapper de email (sync email del IDP → Altana)
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "email mapper",
    "identityProviderMapper": "oidc-user-attribute-idp-mapper",
    "identityProviderAlias": "toyota-corp",
    "config": {
      "syncMode": "INHERIT",
      "claim": "email",
      "user.attribute": "email"
    }
  }'
```

#### Mapper de rol (todos los usuarios Toyota → ROLE_ANALYST)
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "toyota analyst role",
    "identityProviderMapper": "oidc-role-idp-mapper",
    "identityProviderAlias": "toyota-corp",
    "config": {
      "syncMode": "INHERIT",
      "role": "ROLE_ANALYST"
    }
  }'
```

---

## Probar el flujo B2B completo

### Paso 1 — Generar URL PKCE con kc_idp_hint

```bash
# Usar el script incluido en el proyecto:
python scripts/generate_b2b_url.py
```

El script genera:
- `code_verifier` y `code_challenge` (PKCE S256)
- `state` para anti-CSRF
- URL completa con **todos** los parámetros incluyendo `code_challenge_method=S256`

### Paso 2 — Iniciar servidor de captura

```bash
# Terminal separado:
python scripts/capture_callback.py
```

### Paso 3 — Abrir el URL en el browser

El flow automático:
1. Browser → Keycloak altana-dev (con `kc_idp_hint=toyota-corp`)
2. Keycloak **no muestra** pantalla de selección de IDP
3. Redirect inmediato → Keycloak toyota-corp login page
4. Usuario: `john.doe` / `toyota123`
5. Toyota-corp emite code → Keycloak altana-dev lo intercambia
6. Mappers ejecutados: email + ROLE_ANALYST asignado
7. Altana-dev emite su propio code → redirect a `localhost:3000/callback`
8. Servidor de captura muestra el `code`

### Paso 4 — Exchange code → tokens

```bash
CODE=<code capturado>

curl -s -X POST "http://localhost:8080/realms/altana-dev/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=altana-web" \
  -d "redirect_uri=http://localhost:3000/callback" \
  -d "code=$CODE" \
  -d "code_verifier=<verifier del script>" \
  | python3 -m json.tool
```

### Paso 5 — Verificar el token Altana

```bash
# Decodificar el access_token (sin verificar firma — solo ver claims)
ACCESS_TOKEN="<token del paso anterior>"

echo $ACCESS_TOKEN | cut -d'.' -f2 | \
  python3 -c "import sys,base64,json; d=sys.stdin.read().strip(); d+='='*(4-len(d)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(d)), indent=2))"
```

**Debes ver en el payload:**
```json
{
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "realm_access": {
    "roles": ["ROLE_ANALYST", "offline_access", "default-roles-altana-dev"]
  },
  "iss": "http://localhost:8080/realms/altana-dev"
}
```

> El issuer es `altana-dev` — no `toyota-corp`. Keycloak emitió sus propios tokens.
> El usuario se federó pero el token es 100% Altana.

---

## Lo que el Python backend ve

```bash
# Llamar a /supply-chain/shipments con el token del usuario Toyota
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8003/supply-chain/shipments" | python3 -m json.tool
```

FastAPI valida:
1. Firma → JWKS de `altana-dev` (no de toyota-corp)
2. Issuer → `http://localhost:8080/realms/altana-dev`
3. Roles → `ROLE_ANALYST` ✓ → acceso concedido

El backend **no sabe** que el usuario viene de Toyota. Para él es un usuario
normal de altana-dev con ROLE_ANALYST.

---

## ENTREVISTA: Preguntas frecuentes sobre B2B Identity Brokering

**"¿Qué pasa si el IDP externo cae durante la sesión?"**
→ La sesión en Altana sigue activa hasta que expire el refresh token.
  El IDP externo solo es necesario para el login inicial.
  Una vez que Keycloak tiene su propia sesión, es independiente del IDP externo.

**"¿Cómo manejas múltiples empresas clientes?"**
→ Cada empresa → su propio IDP alias en altana-dev.
  `kc_idp_hint` se inyecta según el subdominio o tenant ID.
  Los mappers de rol pueden ser por IDP (Toyota → ROLE_ANALYST) o basados en claims del IDP.

**"¿Qué es un First Broker Login Flow?"**
→ Cuando un usuario federated llega por primera vez, Keycloak puede ejecutar
  un "first broker login flow" para: verificar email, vincular a cuenta existente,
  o crear cuenta nueva automáticamente. Se configura en el IDP.

**"¿Diferencia entre Identity Brokering y Federation?"**
→ Identity Brokering: Keycloak como broker entre SPA y IdP externo (el usuario
  tiene cuenta en el IdP externo y Keycloak la vincula).
  Federation: Keycloak sincroniza un directorio completo (LDAP/AD) como si fuera
  su propia base de datos de usuarios.

**"¿Por qué usar syncMode=FORCE en B2B?"**
→ En B2B el IdP externo es la fuente de verdad (HR del cliente maneja los usuarios).
  FORCE garantiza que cambios en el IdP (nombre, email, departamento) se reflejan
  en Altana en el siguiente login, sin intervención manual.
