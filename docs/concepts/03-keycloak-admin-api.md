# Keycloak Admin REST API — Referencia práctica

> La Admin API es como manejas Keycloak desde código: CI/CD, scripts de onboarding,
> automatización de configuración. En el rol de Keycloak engineer la usarás constantemente.

## Autenticación — obtener Admin Token

Todo comienza aquí. El realm `master` tiene el cliente `admin-cli`.

```bash
ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

> ENTREVISTA: "¿Por qué usas el realm master para el admin token?"
> → El realm master es el realm de administración de Keycloak. Tiene el service account
>   admin-cli con permisos de superadmin. En producción deberías usar un service account
>   con permisos mínimos necesarios (principle of least privilege).

---

## REALMS

### Crear realm
```bash
curl -s -X POST "$KC/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "realm": "mi-realm",
    "displayName": "Mi Realm",
    "enabled": true,
    "sslRequired": "external",
    "accessTokenLifespan": 300,
    "loginWithEmailAllowed": true
  }'
```

### Leer configuración de un realm
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev" | python3 -m json.tool
```

### Actualizar realm (PATCH parcial)
```bash
curl -s -X PUT "$KC/admin/realms/altana-dev" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accessTokenLifespan": 300, "accessCodeLifespan": 300}'
```

### Exportar realm completo
```bash
# Via endpoint de exportación (incluye usuarios, clientes, roles)
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/partial-export?exportClients=true&exportGroupsAndRoles=true" \
  | python3 -m json.tool > altana-dev-export.json
```

### Eliminar realm
```bash
curl -s -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev"
```

---

## USUARIOS

### Listar usuarios
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users" | python3 -m json.tool

# Con filtros
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users?search=john&max=10"
```

### Crear usuario
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "nuevo-usuario",
    "email": "nuevo@empresa.com",
    "firstName": "Juan",
    "lastName": "Pérez",
    "enabled": true,
    "emailVerified": true,
    "attributes": {
      "department": ["engineering"],
      "company": ["altana"]
    }
  }'
```

### Reset de password
```bash
USER_ID="uuid-del-usuario"

curl -s -X PUT "$KC/admin/realms/altana-dev/users/$USER_ID/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type": "password", "value": "nueva-password", "temporary": false}'
```

### Obtener usuario por username
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users?username=admin-user&exact=true" \
  | python3 -c "import sys,json; users=json.load(sys.stdin); print(users[0]['id'] if users else 'not found')"
```

### Asignar roles a usuario
```bash
# Primero obtener los role objects
ROLE=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/roles/ROLE_ANALYST" | python3 -m json.tool)

# Asignar
curl -s -X POST "$KC/admin/realms/altana-dev/users/$USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "[$ROLE]"
```

### Ver sesiones activas de un usuario
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users/$USER_ID/sessions" | python3 -m json.tool
```

### Forzar logout de un usuario (invalida todas sus sesiones)
```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users/$USER_ID/logout"
```

---

## CLIENTES

### Listar clientes
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/clients" \
  | python3 -c "import sys,json; [print(c['clientId'], '|', c['id']) for c in json.load(sys.stdin)]"
```

### Obtener ID interno de un cliente por clientId
```bash
CLIENT_UUID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/clients?clientId=altana-web" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
```

### Crear cliente confidencial (backend)
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "mi-api",
    "publicClient": false,
    "secret": "mi-secret",
    "standardFlowEnabled": false,
    "serviceAccountsEnabled": true,
    "directAccessGrantsEnabled": false
  }'
```

### Crear cliente público con PKCE (SPA)
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "mi-spa",
    "publicClient": true,
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": false,
    "redirectUris": ["http://localhost:5173/*"],
    "webOrigins": ["http://localhost:5173"],
    "attributes": {"pkce.code.challenge.method": "S256"}
  }'
```

### Agregar scope a un cliente
```bash
# Obtener ID del scope
SCOPE_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/client-scopes" \
  | python3 -c "import sys,json; print(next(s['id'] for s in json.load(sys.stdin) if s['name']=='roles'))")

# Agregar como default scope
curl -s -X PUT \
  "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID/default-client-scopes/$SCOPE_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Rotar secret de un cliente
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID/client-secret" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool
```

---

## IDENTITY PROVIDERS (B2B)

### Listar IDPs registrados
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/identity-provider/instances" \
  | python3 -c "import sys,json; [print(i['alias'], i['providerId']) for i in json.load(sys.stdin)]"
```

### Registrar IDP OIDC externo
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/identity-provider/instances" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "empresa-idp",
    "displayName": "Login con Empresa",
    "providerId": "oidc",
    "enabled": true,
    "trustEmail": true,
    "config": {
      "issuer": "https://idp.empresa.com/realms/empresa",
      "authorizationUrl": "https://idp.empresa.com/realms/empresa/protocol/openid-connect/auth",
      "tokenUrl": "https://idp.empresa.com/realms/empresa/protocol/openid-connect/token",
      "jwksUrl": "https://idp.empresa.com/realms/empresa/protocol/openid-connect/certs",
      "clientId": "altana-broker",
      "clientSecret": "secret",
      "defaultScope": "openid profile email",
      "validateSignature": "true",
      "useJwksUrl": "true",
      "syncMode": "FORCE"
    }
  }'
```

### Agregar mapper de rol a un IDP
```bash
# Todos los usuarios de este IDP reciben ROLE_ANALYST automáticamente
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/empresa-idp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "assign analyst role",
    "identityProviderMapper": "oidc-role-idp-mapper",
    "identityProviderAlias": "empresa-idp",
    "config": {"syncMode": "INHERIT", "role": "ROLE_ANALYST"}
  }'
```

---

## TOKENS

### Introspección (verificar si un token es válido)
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token/introspect" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "supply-chain-backend:CHANGE-ME-IN-PRODUCTION" \
  -d "token=$ACCESS_TOKEN" | python3 -m json.tool
# "active": true  → válido
# "active": false → expirado o inválido
```

### Client Credentials (service-to-service)
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=supply-chain-backend&client_secret=SECRET"
```

### Revocar refresh token
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/revoke" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=altana-web&token=$REFRESH_TOKEN"
```

---

## Variables de entorno útiles para scripting

```bash
KC="http://localhost:8080"
REALM="altana-dev"

ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# A partir de aquí todas las llamadas usan:
# -H "Authorization: Bearer $ADMIN_TOKEN"
# URL base: $KC/admin/realms/$REALM/...
```
