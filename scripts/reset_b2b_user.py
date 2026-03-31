#!/usr/bin/env python3
"""
Deletes john.doe from altana-dev to force a clean re-import.

Use when: the token does not include ROLE_ANALYST because the user already
existed and the IMPORT mapper did not run again.

After running this script: log in via B2B again with generate_b2b_url.py
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
    print("john.doe does not exist in altana-dev — nothing to do")
else:
    uid = users[0]["id"]
    urllib.request.urlopen(urllib.request.Request(
        f"{KC}/admin/realms/altana-dev/users/{uid}",
        headers=auth, method="DELETE"
    ))
    print(f"john.doe deleted from altana-dev (id: {uid})")
    print("Now log in via B2B again — the mapper will assign ROLE_ANALYST when the user is created.")
