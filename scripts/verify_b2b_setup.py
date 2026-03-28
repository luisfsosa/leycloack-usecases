#!/usr/bin/env python3
"""
Verifica que toda la configuración B2B esté correcta en Keycloak.

Chequea:
  - Realm toyota-corp existe
  - Usuario john.doe existe en toyota-corp
  - Cliente altana-broker existe en toyota-corp
  - IDP toyota-corp está registrado en altana-dev
  - Mappers del IDP tienen los tipos correctos (oidc-hardcoded-role-idp-mapper)
  - ROLE_ANALYST existe en altana-dev
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
    print(f"{ERR} Realm toyota-corp no existe (HTTP {err})")
    errors.append("toyota-corp realm missing")
else:
    enabled = realms.get("enabled", False)
    print(f"{OK}  Realm toyota-corp existe (enabled={enabled})")

# 2. Usuario john.doe en toyota-corp
users, err = get(f"{KC}/admin/realms/toyota-corp/users?username=john.doe&exact=true")
if err or not users:
    print(f"{ERR} Usuario john.doe no existe en toyota-corp")
    errors.append("john.doe missing in toyota-corp")
else:
    u = users[0]
    print(f"{OK}  Usuario john.doe en toyota-corp (enabled={u['enabled']})")

# 3. Cliente altana-broker en toyota-corp
clients, err = get(f"{KC}/admin/realms/toyota-corp/clients?clientId=altana-broker")
if err or not clients:
    print(f"{ERR} Cliente altana-broker no existe en toyota-corp")
    errors.append("altana-broker client missing")
else:
    print(f"{OK}  Cliente altana-broker en toyota-corp")

# 4. IDP toyota-corp en altana-dev
idp, err = get(f"{KC}/admin/realms/altana-dev/identity-provider/instances/toyota-corp")
if err:
    print(f"{ERR} IDP toyota-corp no está registrado en altana-dev (HTTP {err})")
    errors.append("toyota-corp IDP not registered in altana-dev")
else:
    enabled = idp.get("enabled", False)
    sync = idp.get("config", {}).get("syncMode", "?")
    print(f"{OK}  IDP toyota-corp registrado en altana-dev (enabled={enabled}, syncMode={sync})")

# 5. Mappers del IDP
mappers, err = get(f"{KC}/admin/realms/altana-dev/identity-provider/instances/toyota-corp/mappers")
if err or mappers is None:
    print(f"{ERR} No se pudo obtener mappers del IDP")
    errors.append("cannot read IDP mappers")
else:
    print(f"\n  Mappers ({len(mappers)}):")
    has_role_mapper = False
    for m in mappers:
        mapper_type = m["identityProviderMapper"]
        config = m.get("config", {})

        # Verificar tipo correcto
        if mapper_type == "hardcoded-role-idp-mapper":
            print(f"  {ERR} Mapper '{m['name']}': tipo INCORRECTO ({mapper_type})")
            print(f"       Debe ser: oidc-hardcoded-role-idp-mapper")
            errors.append(f"wrong mapper type: {mapper_type}")
        elif mapper_type == "oidc-hardcoded-role-idp-mapper":
            role_val = config.get("role", "")
            # Verificar que es nombre, no UUID
            if len(role_val) == 36 and role_val.count("-") == 4:
                print(f"  {ERR} Mapper '{m['name']}': role es UUID ({role_val[:8]}...)")
                print(f"       Debe ser el nombre del rol, ej: 'ROLE_ANALYST'")
                errors.append("mapper role is UUID instead of name")
            else:
                print(f"  {OK}  Mapper '{m['name']}': {mapper_type} → role={role_val}")
                has_role_mapper = True
        else:
            print(f"  {OK}  Mapper '{m['name']}': {mapper_type}")

    if not has_role_mapper:
        print(f"  {WARN} No hay mapper de rol hardcodeado (ROLE_ANALYST no se asignará)")

# 6. ROLE_ANALYST en altana-dev
roles, err = get(f"{KC}/admin/realms/altana-dev/roles/ROLE_ANALYST")
if err:
    print(f"\n{ERR} ROLE_ANALYST no existe en altana-dev")
    errors.append("ROLE_ANALYST missing in altana-dev")
else:
    print(f"\n{OK}  ROLE_ANALYST existe en altana-dev (id={roles['id'][:8]}...)")

# 7. john.doe en altana-dev (opcional)
users_dev, _ = get(f"{KC}/admin/realms/altana-dev/users?username=john.doe&exact=true")
if not users_dev:
    print(f"{OK}  john.doe no existe en altana-dev → se creará limpiamente en el próximo login")
else:
    uid = users_dev[0]["id"]
    # Ver sus roles
    role_mappings, _ = get(f"{KC}/admin/realms/altana-dev/users/{uid}/role-mappings/realm")
    if role_mappings:
        role_names = [r["name"] for r in role_mappings]
        if "ROLE_ANALYST" in role_names:
            print(f"{OK}  john.doe en altana-dev tiene ROLE_ANALYST asignado")
        else:
            print(f"{WARN} john.doe en altana-dev NO tiene ROLE_ANALYST")
            print(f"       Roles actuales: {role_names}")
            print(f"       Solución: python scripts\\reset_b2b_user.py y hacer login de nuevo")

# Resumen
print("\n" + "=" * 60)
if errors:
    print(f"RESULTADO: {len(errors)} problema(s) encontrado(s)")
    for e in errors:
        print(f"  - {e}")
    print("\nEjecuta setup_toyota_idp.py para reconstruir la config.")
    sys.exit(1)
else:
    print("RESULTADO: Todo OK — listo para probar el flujo B2B")
    print("\nSiguientes pasos:")
    print("  1. python scripts\\capture_callback.py   (Terminal 1)")
    print("  2. python scripts\\generate_b2b_url.py  (Terminal 2)")
    print("  3. Abrir el URL en browser incógnito")
    print("  4. Login: john.doe / toyota123")
    print("  5. python scripts\\exchange_code.py     (Terminal 2)")
