#!/usr/bin/env python3
"""
Borra john.doe de altana-dev para forzar re-import limpio.

Usar cuando: el token no trae ROLE_ANALYST porque el usuario ya existía
y el mapper IMPORT no volvió a ejecutar.

Después de ejecutar este script: hacer login B2B de nuevo con generate_b2b_url.py
"""
import urllib.request, urllib.parse, json

KC = "http://localhost:8080"

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

with urllib.request.urlopen(urllib.request.Request(
    f"{KC}/admin/realms/altana-dev/users?username=john.doe&exact=true",
    headers=auth
)) as r:
    users = json.loads(r.read())

if not users:
    print("john.doe no existe en altana-dev — nada que hacer")
else:
    uid = users[0]["id"]
    urllib.request.urlopen(urllib.request.Request(
        f"{KC}/admin/realms/altana-dev/users/{uid}",
        headers=auth, method="DELETE"
    ))
    print(f"john.doe borrado de altana-dev (id: {uid})")
    print("Ahora haz login B2B de nuevo — el mapper asignará ROLE_ANALYST al crear el usuario.")
