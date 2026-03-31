#!/usr/bin/env python3
"""
Full setup of the B2B Identity Brokering pattern from scratch.

Creates:
  - Realm toyota-corp with user john.doe
  - Client altana-broker in toyota-corp
  - IDP toyota-corp in altana-dev
  - Email mapper + oidc-hardcoded-role-idp-mapper → ROLE_ANALYST

Idempotent: if something already exists, it is skipped without error.
"""
import urllib.request, urllib.parse, json

KC = "http://localhost:8080"

def get_admin_token():
    data = urllib.parse.urlencode({
        "client_id": "admin-cli", "grant_type": "password",
        "username": "admin", "password": "admin"
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(
        f"{KC}/realms/master/protocol/openid-connect/token", data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"}
    )) as r:
        return json.loads(r.read())["access_token"]

def api(method, path, body=None, token=None):
    headers = {"Authorization": f"Bearer {token}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    else:
        data = None
    req = urllib.request.Request(f"{KC}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            content = r.read()
            return json.loads(content) if content else {}, r.status
    except urllib.error.HTTPError as e:
        return None, e.code

token = get_admin_token()
print("Admin token OK\n")

# ── 1. Realm toyota-corp ──────────────────────────────────────────────────────
_, status = api("GET", "/admin/realms/toyota-corp", token=token)
if status == 404:
    _, s = api("POST", "/admin/realms", body={
        "realm": "toyota-corp",
        "displayName": "Toyota Corporation IDP",
        "enabled": True,
        "sslRequired": "external",
        "accessTokenLifespan": 300
    }, token=token)
    print(f"[{s}] Realm toyota-corp created")
else:
    print("[--] Realm toyota-corp already exists")

# ── 2. User john.doe in toyota-corp ──────────────────────────────────────────
users, _ = api("GET", "/admin/realms/toyota-corp/users?username=john.doe&exact=true", token=token)
if not users:
    _, s = api("POST", "/admin/realms/toyota-corp/users", body={
        "username": "john.doe",
        "email": "john.doe@toyota.com",
        "firstName": "John",
        "lastName": "Doe",
        "enabled": True,
        "emailVerified": True,
    }, token=token)
    print(f"[{s}] User john.doe created in toyota-corp")

    users, _ = api("GET", "/admin/realms/toyota-corp/users?username=john.doe&exact=true", token=token)
    uid = users[0]["id"]
    _, s = api("PUT", f"/admin/realms/toyota-corp/users/{uid}/reset-password",
               body={"type": "password", "value": "toyota123", "temporary": False}, token=token)
    print(f"[{s}] Password toyota123 assigned to john.doe")
else:
    print("[--] User john.doe already exists in toyota-corp")

# ── 3. Client altana-broker in toyota-corp ────────────────────────────────────
clients, _ = api("GET", "/admin/realms/toyota-corp/clients?clientId=altana-broker", token=token)
if not clients:
    _, s = api("POST", "/admin/realms/toyota-corp/clients", body={
        "clientId": "altana-broker",
        "publicClient": False,
        "secret": "altana-broker-secret",
        "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False,
        "redirectUris": [f"{KC}/realms/altana-dev/broker/toyota-corp/endpoint"]
    }, token=token)
    print(f"[{s}] Client altana-broker created in toyota-corp")
else:
    print("[--] Client altana-broker already exists in toyota-corp")

# ── 4. IDP toyota-corp in altana-dev ─────────────────────────────────────────
_, status = api("GET", "/admin/realms/altana-dev/identity-provider/instances/toyota-corp", token=token)
if status == 404:
    _, s = api("POST", "/admin/realms/altana-dev/identity-provider/instances", body={
        "alias": "toyota-corp",
        "displayName": "Login with Toyota",
        "providerId": "oidc",
        "enabled": True,
        "trustEmail": True,
        "config": {
            "issuer": f"{KC}/realms/toyota-corp",
            "authorizationUrl": f"{KC}/realms/toyota-corp/protocol/openid-connect/auth",
            "tokenUrl": f"{KC}/realms/toyota-corp/protocol/openid-connect/token",
            "jwksUrl": f"{KC}/realms/toyota-corp/protocol/openid-connect/certs",
            "clientId": "altana-broker",
            "clientSecret": "altana-broker-secret",
            "defaultScope": "openid profile email",
            "validateSignature": "true",
            "useJwksUrl": "true",
            "syncMode": "IMPORT",
        }
    }, token=token)
    print(f"[{s}] IDP toyota-corp registered in altana-dev")
else:
    print("[--] IDP toyota-corp already exists in altana-dev")

# ── 5. Verify available mapper types ──────────────────────────────────────────
mapper_types, _ = api(
    "GET",
    "/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mapper-types",
    token=token
)
available_ids = list(mapper_types.keys()) if mapper_types else []

# ── 6. Mappers ────────────────────────────────────────────────────────────────
mappers, _ = api(
    "GET",
    "/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers",
    token=token
)
existing_types = [m["identityProviderMapper"] for m in (mappers or [])]

# Email mapper
if "oidc-user-attribute-idp-mapper" not in existing_types:
    _, s = api("POST",
               "/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers",
               body={
                   "name": "email mapper",
                   "identityProviderMapper": "oidc-user-attribute-idp-mapper",
                   "identityProviderAlias": "toyota-corp",
                   "config": {"syncMode": "INHERIT", "claim": "email", "user.attribute": "email"}
               }, token=token)
    print(f"[{s}] Email mapper created")
else:
    print("[--] Email mapper already exists")

# Role mapper — use oidc-hardcoded-role-idp-mapper (correct name in KC 26)
HARDCODED_ROLE_MAPPER = "oidc-hardcoded-role-idp-mapper"
if HARDCODED_ROLE_MAPPER not in available_ids:
    print(f"[WARN] {HARDCODED_ROLE_MAPPER} not available in this Keycloak version")
    print(f"       Available: {available_ids}")
elif HARDCODED_ROLE_MAPPER not in existing_types:
    # Delete any incorrect role mapper first
    for m in (mappers or []):
        if "role" in m["identityProviderMapper"].lower():
            api("DELETE",
                f"/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers/{m['id']}",
                token=token)
            print(f"[--] Incorrect mapper deleted: {m['identityProviderMapper']}")

    _, s = api("POST",
               "/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers",
               body={
                   "name": "toyota users get ANALYST role",
                   "identityProviderMapper": HARDCODED_ROLE_MAPPER,
                   "identityProviderAlias": "toyota-corp",
                   "config": {
                       "syncMode": "IMPORT",
                       "role": "ROLE_ANALYST"    # name, not UUID
                   }
               }, token=token)
    print(f"[{s}] Role mapper created: {HARDCODED_ROLE_MAPPER} → ROLE_ANALYST")
else:
    print("[--] Role mapper already exists")

print("\nB2B setup complete.")
print("Run: python scripts\\verify_b2b_setup.py to confirm.")
