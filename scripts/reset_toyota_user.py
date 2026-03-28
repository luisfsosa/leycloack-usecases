#!/usr/bin/env python3
"""
Resetea el password de john.doe en toyota-corp a 'toyota123'.
Usar si el realm fue recreado o el password fue cambiado.
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
auth_ct = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

with urllib.request.urlopen(urllib.request.Request(
    f"{KC}/admin/realms/toyota-corp/users?username=john.doe&exact=true",
    headers=auth
)) as r:
    users = json.loads(r.read())

if not users:
    print("john.doe no existe en toyota-corp — ejecuta setup_toyota_idp.py primero")
else:
    uid = users[0]["id"]
    urllib.request.urlopen(urllib.request.Request(
        f"{KC}/admin/realms/toyota-corp/users/{uid}/reset-password",
        data=json.dumps({"type": "password", "value": "toyota123", "temporary": False}).encode(),
        headers=auth_ct, method="PUT"
    ))
    print("Password de john.doe reseteado a: toyota123")
