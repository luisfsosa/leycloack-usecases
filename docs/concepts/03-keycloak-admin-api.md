# Keycloak Admin REST API — Practical Reference

> The Admin API is how you manage Keycloak from code: CI/CD, onboarding scripts,
> configuration automation. As a Keycloak engineer you will use it constantly.

## Authentication — get an Admin Token

Everything starts here. The `master` realm has the `admin-cli` client.

```bash
ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

> **INTERVIEW:** "Why do you use the master realm for the admin token?"
> → The master realm is Keycloak's administration realm. It has the admin-cli
>   service account with superadmin permissions. In production you should use a
>   service account with least-privilege permissions.

---

## REALMS

### Create realm
```bash
curl -s -X POST "$KC/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "realm": "my-realm",
    "displayName": "My Realm",
    "enabled": true,
    "sslRequired": "external",
    "accessTokenLifespan": 300,
    "loginWithEmailAllowed": true
  }'
```

### Read realm configuration
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev" | python3 -m json.tool
```

### Update realm (partial PUT)
```bash
curl -s -X PUT "$KC/admin/realms/altana-dev" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accessTokenLifespan": 300, "accessCodeLifespan": 300}'
```

### Export full realm
```bash
# Via export endpoint (includes users, clients, roles)
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/partial-export?exportClients=true&exportGroupsAndRoles=true" \
  | python3 -m json.tool > altana-dev-export.json
```

### Delete realm
```bash
curl -s -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev"
```

---

## USERS

### List users
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users" | python3 -m json.tool

# With filters
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users?search=john&max=10"
```

### Create user
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "new-user",
    "email": "new@company.com",
    "firstName": "John",
    "lastName": "Smith",
    "enabled": true,
    "emailVerified": true,
    "attributes": {
      "department": ["engineering"],
      "company": ["altana"]
    }
  }'
```

### Reset password
```bash
USER_ID="user-uuid"

curl -s -X PUT "$KC/admin/realms/altana-dev/users/$USER_ID/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type": "password", "value": "new-password", "temporary": false}'
```

### Get user by username
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users?username=admin-user&exact=true" \
  | python3 -c "import sys,json; users=json.load(sys.stdin); print(users[0]['id'] if users else 'not found')"
```

### Assign roles to user
```bash
# First get the role objects
ROLE=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/roles/ROLE_ANALYST" | python3 -m json.tool)

# Assign
curl -s -X POST "$KC/admin/realms/altana-dev/users/$USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "[$ROLE]"
```

### View active sessions for a user
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users/$USER_ID/sessions" | python3 -m json.tool
```

### Force logout a user (invalidates all sessions)
```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/users/$USER_ID/logout"
```

---

## CLIENTS

### List clients
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/clients" \
  | python3 -c "import sys,json; [print(c['clientId'], '|', c['id']) for c in json.load(sys.stdin)]"
```

### Get internal client ID by clientId
```bash
CLIENT_UUID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/clients?clientId=altana-web" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
```

### Create confidential client (backend)
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "my-api",
    "publicClient": false,
    "secret": "my-secret",
    "standardFlowEnabled": false,
    "serviceAccountsEnabled": true,
    "directAccessGrantsEnabled": false
  }'
```

### Create public client with PKCE (SPA)
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "my-spa",
    "publicClient": true,
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": false,
    "redirectUris": ["http://localhost:5173/*"],
    "webOrigins": ["http://localhost:5173"],
    "attributes": {"pkce.code.challenge.method": "S256"}
  }'
```

### Add scope to a client
```bash
# Get scope ID
SCOPE_ID=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/client-scopes" \
  | python3 -c "import sys,json; print(next(s['id'] for s in json.load(sys.stdin) if s['name']=='roles'))")

# Add as default scope
curl -s -X PUT \
  "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID/default-client-scopes/$SCOPE_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Rotate client secret
```bash
curl -s -X POST \
  "$KC/admin/realms/altana-dev/clients/$CLIENT_UUID/client-secret" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool
```

---

## IDENTITY PROVIDERS (B2B)

### List registered IDPs
```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC/admin/realms/altana-dev/identity-provider/instances" \
  | python3 -c "import sys,json; [print(i['alias'], i['providerId']) for i in json.load(sys.stdin)]"
```

### Register an external OIDC IDP
```bash
curl -s -X POST "$KC/admin/realms/altana-dev/identity-provider/instances" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "company-idp",
    "displayName": "Login with Company",
    "providerId": "oidc",
    "enabled": true,
    "trustEmail": true,
    "config": {
      "issuer": "https://idp.company.com/realms/company",
      "authorizationUrl": "https://idp.company.com/realms/company/protocol/openid-connect/auth",
      "tokenUrl": "https://idp.company.com/realms/company/protocol/openid-connect/token",
      "jwksUrl": "https://idp.company.com/realms/company/protocol/openid-connect/certs",
      "clientId": "altana-broker",
      "clientSecret": "secret",
      "defaultScope": "openid profile email",
      "validateSignature": "true",
      "useJwksUrl": "true",
      "syncMode": "FORCE"
    }
  }'
```

### Add role mapper to an IDP
```bash
# All users from this IDP automatically receive ROLE_ANALYST
curl -s -X POST \
  "$KC/admin/realms/altana-dev/identity-provider/instances/company-idp/mappers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "assign analyst role",
    "identityProviderMapper": "oidc-role-idp-mapper",
    "identityProviderAlias": "company-idp",
    "config": {"syncMode": "INHERIT", "role": "ROLE_ANALYST"}
  }'
```

---

## TOKENS

### Introspection (verify if a token is valid)
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token/introspect" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "supply-chain-backend:CHANGE-ME-IN-PRODUCTION" \
  -d "token=$ACCESS_TOKEN" | python3 -m json.tool
# "active": true  → valid
# "active": false → expired or invalid
```

### Client Credentials (service-to-service)
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=supply-chain-backend&client_secret=SECRET"
```

### Revoke refresh token
```bash
curl -s -X POST "$KC/realms/altana-dev/protocol/openid-connect/revoke" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=altana-web&token=$REFRESH_TOKEN"
```

---

## Useful environment variables for scripting

```bash
KC="http://localhost:8080"
REALM="altana-dev"

ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# From here all calls use:
# -H "Authorization: Bearer $ADMIN_TOKEN"
# Base URL: $KC/admin/realms/$REALM/...
```
