#!/usr/bin/env python3
"""
Verifies that the entire B2B configuration is correct in Keycloak.

Checks:
  - Realm toyota-corp exists
  - User john.doe exists in toyota-corp
  - Client altana-broker exists in toyota-corp
  - IDP toyota-corp is registered in altana-dev
  - IDP mappers have the correct types (oidc-hardcoded-role-idp-mapper)
  - ROLE_ANALYST exists in altana-dev
"""
import urllib.request, urllib.parse, json, sys

KC = "http://localhost:8080"
OK  = "[OK]"
ERR = "[ERROR]"
WARN = "[WARN]"

data = urllib.parse.urlencode({
    "client_id": "admin-cli", "grant_type": "password",
    "username": "admin", "password": "admin"
}).encode()
with urllib.request.urlopen(urllib.request.Request(
    f"{KC}/realms/master/protocol/openid-connect/token", data=data,
    headers={"Content-Type": "application/x-www-form-urlencoded"}
)) as r:
    token = json.loads(r.read())["access_token"]

auth = {"Authorization": f"Bearer {token}"}
errors = []

def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=auth)) as r:
            return json.loads(r.read()), None
    except urllib.error.HTTPError as e:
        return None, e.code

print("=" * 60)
print("B2B SETUP VERIFICATION")
print("=" * 60)

# 1. Realm toyota-corp
realms, err = get(f"{KC}/admin/realms/toyota-corp")
if err:
    print(f"{ERR} Realm toyota-corp does not exist (HTTP {err})")
    errors.append("toyota-corp realm missing")
else:
    enabled = realms.get("enabled", False)
    print(f"{OK}  Realm toyota-corp exists (enabled={enabled})")

# 2. User john.doe in toyota-corp
users, err = get(f"{KC}/admin/realms/toyota-corp/users?username=john.doe&exact=true")
if err or not users:
    print(f"{ERR} User john.doe does not exist in toyota-corp")
    errors.append("john.doe missing in toyota-corp")
else:
    u = users[0]
    print(f"{OK}  User john.doe in toyota-corp (enabled={u['enabled']})")

# 3. Client altana-broker in toyota-corp
clients, err = get(f"{KC}/admin/realms/toyota-corp/clients?clientId=altana-broker")
if err or not clients:
    print(f"{ERR} Client altana-broker does not exist in toyota-corp")
    errors.append("altana-broker client missing")
else:
    print(f"{OK}  Client altana-broker in toyota-corp")

# 4. IDP toyota-corp in altana-dev
idp, err = get(f"{KC}/admin/realms/altana-dev/identity-provider/instances/toyota-corp")
if err:
    print(f"{ERR} IDP toyota-corp is not registered in altana-dev (HTTP {err})")
    errors.append("toyota-corp IDP not registered in altana-dev")
else:
    enabled = idp.get("enabled", False)
    sync = idp.get("config", {}).get("syncMode", "?")
    print(f"{OK}  IDP toyota-corp registered in altana-dev (enabled={enabled}, syncMode={sync})")

# 5. IDP mappers
mappers, err = get(f"{KC}/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers")
if err or mappers is None:
    print(f"{ERR} Could not retrieve IDP mappers")
    errors.append("cannot read IDP mappers")
else:
    print(f"\n  Mappers ({len(mappers)}):")
    has_role_mapper = False
    for m in mappers:
        mapper_type = m["identityProviderMapper"]
        config = m.get("config", {})

        # Verify correct type
        if mapper_type == "hardcoded-role-idp-mapper":
            print(f"  {ERR} Mapper '{m['name']}': INCORRECT type ({mapper_type})")
            print(f"       Must be: oidc-hardcoded-role-idp-mapper")
            errors.append(f"wrong mapper type: {mapper_type}")
        elif mapper_type == "oidc-hardcoded-role-idp-mapper":
            role_val = config.get("role", "")
            # Verify it is a name, not a UUID
            if len(role_val) == 36 and role_val.count("-") == 4:
                print(f"  {ERR} Mapper '{m['name']}': role is a UUID ({role_val[:8]}...)")
                print(f"       Must be the role name, e.g.: 'ROLE_ANALYST'")
                errors.append("mapper role is UUID instead of name")
            else:
                print(f"  {OK}  Mapper '{m['name']}': {mapper_type} → role={role_val}")
                has_role_mapper = True
        else:
            print(f"  {OK}  Mapper '{m['name']}': {mapper_type}")

    if not has_role_mapper:
        print(f"  {WARN} No hardcoded role mapper found (ROLE_ANALYST will not be assigned)")

# 6. ROLE_ANALYST in altana-dev
roles, err = get(f"{KC}/admin/realms/altana-dev/roles/ROLE_ANALYST")
if err:
    print(f"\n{ERR} ROLE_ANALYST does not exist in altana-dev")
    errors.append("ROLE_ANALYST missing in altana-dev")
else:
    print(f"\n{OK}  ROLE_ANALYST exists in altana-dev (id={roles['id'][:8]}...)")

# 7. john.doe in altana-dev (optional)
users_dev, _ = get(f"{KC}/admin/realms/altana-dev/users?username=john.doe&exact=true")
if not users_dev:
    print(f"{OK}  john.doe does not exist in altana-dev → will be created cleanly on next login")
else:
    uid = users_dev[0]["id"]
    # Check their roles
    role_mappings, _ = get(f"{KC}/admin/realms/altana-dev/users/{uid}/role-mappings/realm")
    if role_mappings:
        role_names = [r["name"] for r in role_mappings]
        if "ROLE_ANALYST" in role_names:
            print(f"{OK}  john.doe in altana-dev has ROLE_ANALYST assigned")
        else:
            print(f"{WARN} john.doe in altana-dev does NOT have ROLE_ANALYST")
            print(f"       Current roles: {role_names}")
            print(f"       Fix: run python scripts\\reset_b2b_user.py and log in again")

# Summary
print("\n" + "=" * 60)
if errors:
    print(f"RESULT: {len(errors)} problem(s) found")
    for e in errors:
        print(f"  - {e}")
    print("\nRun setup_toyota_idp.py to rebuild the configuration.")
    sys.exit(1)
else:
    print("RESULT: All OK — ready to test the B2B flow")
    print("\nNext steps:")
    print("  1. python scripts\\capture_callback.py   (Terminal 1)")
    print("  2. python scripts\\generate_b2b_url.py  (Terminal 2)")
    print("  3. Open the URL in an incognito browser")
    print("  4. Login: john.doe / toyota123")
    print("  5. python scripts\\exchange_code.py     (Terminal 2)")
