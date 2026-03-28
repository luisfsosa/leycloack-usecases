#!/usr/bin/env python3
"""
Intercambia el authorization code por tokens.
Uso: python scripts/exchange_code.py

El script pide el code y el verifier interactivamente.
"""

import json
import urllib.request
import urllib.parse

KC           = "http://localhost:8080"
REALM        = "altana-dev"
CLIENT_ID    = "altana-web"
REDIRECT_URI = "http://localhost:3000/callback"

print("=" * 60)
print("TOKEN EXCHANGE — Authorization Code → Tokens")
print("=" * 60)
code     = input("\nPega el code capturado:\n> ").strip()
verifier = input("\nPega el code_verifier (del script generate_b2b_url):\n> ").strip()

data = urllib.parse.urlencode({
    "grant_type":   "authorization_code",
    "client_id":    CLIENT_ID,
    "redirect_uri": REDIRECT_URI,
    "code":         code,
    "code_verifier": verifier,
}).encode()

req = urllib.request.Request(
    f"{KC}/realms/{REALM}/protocol/openid-connect/token",
    data=data,
    headers={"Content-Type": "application/x-www-form-urlencoded"},
    method="POST",
)

try:
    with urllib.request.urlopen(req) as resp:
        tokens = json.loads(resp.read())
except urllib.error.HTTPError as e:
    error_body = json.loads(e.read())
    print(f"\n[ERROR {e.code}] {json.dumps(error_body, indent=2)}")
    raise SystemExit(1)

print("\n" + "=" * 60)
print("TOKENS OBTENIDOS")
print("=" * 60)
print(f"  token_type   : {tokens.get('token_type')}")
print(f"  expires_in   : {tokens.get('expires_in')}s")
print(f"  access_token : {tokens['access_token'][:40]}...")
if "refresh_token" in tokens:
    print(f"  refresh_token: {tokens['refresh_token'][:40]}...")

# Decodificar payload del access token (sin verificar firma)
import base64
payload_b64 = tokens["access_token"].split(".")[1]
payload_b64 += "=" * (4 - len(payload_b64) % 4)
payload = json.loads(base64.urlsafe_b64decode(payload_b64))

print("\n" + "=" * 60)
print("ACCESS TOKEN PAYLOAD")
print("=" * 60)
print(json.dumps(payload, indent=2))

print("\n" + "=" * 60)
print("RESUMEN")
print("=" * 60)
print(f"  Usuario : {payload.get('preferred_username')}")
print(f"  Email   : {payload.get('email')}")
print(f"  Issuer  : {payload.get('iss')}")
roles = payload.get("realm_access", {}).get("roles", [])
print(f"  Roles   : {roles}")

idp = payload.get("identity_provider")
if idp:
    print(f"  IDP origen: {idp}")

# Guardar access token para usarlo con curl / FastAPI
with open("scripts/.last_access_token", "w") as f:
    f.write(tokens["access_token"])
print("\n[token guardado en scripts/.last_access_token]")
print("Úsalo así:")
print("  $token = Get-Content scripts\\.last_access_token")
print("  Invoke-WebRequest -Uri http://localhost:8003/supply-chain/shipments -Headers @{Authorization=\"Bearer $token\"}")
