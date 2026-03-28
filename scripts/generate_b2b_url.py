#!/usr/bin/env python3
"""
Genera URL de autorización B2B con PKCE completo.
Incluye code_challenge_method=S256 y kc_idp_hint para B2B Identity Brokering.

Uso:
  python scripts/generate_b2b_url.py

Luego inicia el servidor de captura:
  python scripts/capture_callback.py
"""

import base64
import hashlib
import os
import urllib.parse

# ── PKCE ──────────────────────────────────────────────────────────────────────
verifier_bytes = os.urandom(32)
code_verifier  = base64.urlsafe_b64encode(verifier_bytes).rstrip(b'=').decode()

digest         = hashlib.sha256(code_verifier.encode()).digest()
code_challenge = base64.urlsafe_b64encode(digest).rstrip(b'=').decode()

state          = base64.urlsafe_b64encode(os.urandom(16)).rstrip(b'=').decode()

# ── Parámetros OAuth2 ─────────────────────────────────────────────────────────
KC           = "http://localhost:8080"
REALM        = "altana-dev"
CLIENT_ID    = "altana-web"
REDIRECT_URI = "http://localhost:3000/callback"

params = {
    "response_type":         "code",
    "client_id":             CLIENT_ID,
    "redirect_uri":          REDIRECT_URI,
    "scope":                 "openid profile email",
    "state":                 state,
    "code_challenge":        code_challenge,
    "code_challenge_method": "S256",       # ← el que faltaba
    "kc_idp_hint":           "toyota-corp", # ← skip IDP selection screen
}

auth_url = (
    f"{KC}/realms/{REALM}/protocol/openid-connect/auth"
    "?" + urllib.parse.urlencode(params)
)

# ── Salida ────────────────────────────────────────────────────────────────────
print("=" * 70)
print("PKCE VALUES (guárdalos para el exchange):")
print(f"  code_verifier  : {code_verifier}")
print(f"  code_challenge : {code_challenge}")
print(f"  state          : {state}")
print()
print("=" * 70)
print("AUTHORIZATION URL (copia y pega en el browser):")
print()
print(auth_url)
print()
print("=" * 70)
print("EXCHANGE COMMAND (después de capturar el code):")
print()
print("CODE=<pega el code aquí>")
print(f"""
curl -s -X POST \\
  "{KC}/realms/{REALM}/protocol/openid-connect/token" \\
  -H "Content-Type: application/x-www-form-urlencoded" \\
  -d "grant_type=authorization_code" \\
  -d "client_id={CLIENT_ID}" \\
  -d "redirect_uri={REDIRECT_URI}" \\
  -d "code=$CODE" \\
  -d "code_verifier={code_verifier}" \\
  | python3 -m json.tool
""")
