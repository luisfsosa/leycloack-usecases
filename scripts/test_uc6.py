#!/usr/bin/env python3
"""
UC6 — Test del flujo de invitación B2B Inverso

Uso:
    python scripts/test_uc6.py
    python scripts/test_uc6.py --user admin@altana.com --password admin

Prereqs:
    - Keycloak corriendo en localhost:8080
    - FastAPI corriendo en localhost:8081
    - Usuario con ROLE_ADMIN en realm altana-dev
"""

import argparse
import json
import sys
import urllib.request
import urllib.parse
import urllib.error
from pathlib import Path

KC_URL  = "http://localhost:8080"
REALM   = "altana-dev"
API_URL = "http://localhost:8081"


def post(url, data=None, json_body=None, token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if json_body is not None:
        body = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    else:
        body = urllib.parse.urlencode(data).encode() if data else None
        headers["Content-Type"] = "application/x-www-form-urlencoded"

    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        err = json.loads(e.read())
        print(f"\n❌ HTTP {e.code} — {url}")
        print(json.dumps(err, indent=2, ensure_ascii=False))
        sys.exit(1)


def get(url, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        err = json.loads(e.read())
        print(f"\n❌ HTTP {e.code} — {url}")
        print(json.dumps(err, indent=2, ensure_ascii=False))
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--user",     default="admin-user")
    parser.add_argument("--password", default="Test1234!")
    args = parser.parse_args()

    print("═" * 55)
    print("  UC6 — Supply Chain Partner Portal")
    print("  Test: B2B Inverso — flujo de invitación")
    print("═" * 55)

    # ── Paso 1: Token del admin (altana-web — cliente público con DAG) ──
    print(f"\n▶ Paso 1: Obtener token de admin ({args.user})...")
    resp = post(
        f"{KC_URL}/realms/{REALM}/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id":  "altana-web",
            "username":   args.user,
            "password":   args.password,
        },
    )
    admin_token = resp.get("access_token", "")
    if not admin_token:
        print("❌ No se obtuvo access_token. Verifica usuario y contraseña.")
        sys.exit(1)
    print(f"   ✓ Token obtenido ({len(admin_token)} chars)")

    # ── Paso 2: Crear invitación ──────────────────────────────
    print("\n▶ Paso 2: Crear invitación para proveedor tier-2 de Toyota...")
    invite = post(
        f"{API_URL}/invitations",
        json_body={
            "tenant_id":     "toyota",
            "supplier_tier": 2,
            "invited_email": "supplier@vn-parts.com",
            "ttl_days":      7,
        },
        token=admin_token,
    )
    invite_token = invite["invitation_token"]
    invite_link  = invite["invite_link"]
    print("   ✓ Invitación creada")
    print(f"   tenant_id:     {invite['tenant_id']}")
    print(f"   supplier_tier: {invite['supplier_tier']}")
    print(f"   invited_email: {invite['invited_email']}")

    # ── Paso 3: Validar la invitación (endpoint público) ──────
    print("\n▶ Paso 3: Validar la invitación (endpoint público)...")
    details = get(f"{API_URL}/invitations/{invite_token}")
    print("   Detalles:")
    print(json.dumps(details, indent=5, ensure_ascii=False))

    # ── Resultado ─────────────────────────────────────────────
    print("\n" + "═" * 55)
    print("  INVITE LINK (compartir con el proveedor):\n")
    print(f"  {invite_link}\n")
    print("═" * 55)
    print("\nPróximos pasos (manual en browser):")
    print("  1. Abrir el invite link en el browser")
    print("  2. Click 'Crear cuenta' → Keycloak registration form")
    print("  3. Completar registro con supplier@vn-parts.com")
    print("  4. Callback → onboarding → /supplier/dashboard")

    # Guardar token para uso posterior
    token_file = Path(__file__).parent / "last_invite_token.txt"
    token_file.write_text(invite_token)
    print(f"\n  ℹ Token guardado en {token_file}")


if __name__ == "__main__":
    main()
