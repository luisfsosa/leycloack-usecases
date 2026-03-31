#!/usr/bin/env python3
"""
UC6 — Test of the Reverse B2B invitation flow

Usage:
    python scripts/test_uc6.py
    python scripts/test_uc6.py --user admin@altana.com --password admin

Prerequisites:
    - Keycloak running on localhost:8080
    - FastAPI running on localhost:8081
    - User with ROLE_ADMIN in realm altana-dev
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
    print("  Test: Reverse B2B — invitation flow")
    print("═" * 55)

    # ── Step 1: Admin token (altana-web — public client with DAG) ──
    print(f"\n▶ Step 1: Get admin token ({args.user})...")
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
        print("❌ No access_token received. Check username and password.")
        sys.exit(1)
    print(f"   ✓ Token obtained ({len(admin_token)} chars)")

    # ── Step 2: Create invitation ──────────────────────────────────
    print("\n▶ Step 2: Create invitation for Toyota tier-2 supplier...")
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
    print("   ✓ Invitation created")
    print(f"   tenant_id:     {invite['tenant_id']}")
    print(f"   supplier_tier: {invite['supplier_tier']}")
    print(f"   invited_email: {invite['invited_email']}")

    # ── Step 3: Validate the invitation (public endpoint) ─────────
    print("\n▶ Step 3: Validate invitation (public endpoint)...")
    details = get(f"{API_URL}/invitations/{invite_token}")
    print("   Details:")
    print(json.dumps(details, indent=5, ensure_ascii=False))

    # ── Result ────────────────────────────────────────────────────
    print("\n" + "═" * 55)
    print("  INVITE LINK (share with the supplier):\n")
    print(f"  {invite_link}\n")
    print("═" * 55)
    print("\nNext steps (manual in browser):")
    print("  1. Open the invite link in the browser")
    print("  2. Click 'Create account' → Keycloak registration form")
    print("  3. Complete registration with supplier@vn-parts.com")
    print("  4. Callback → onboarding → /supplier/dashboard")

    # Save token for later use
    token_file = Path(__file__).parent / "last_invite_token.txt"
    token_file.write_text(invite_token)
    print(f"\n  ℹ Token saved to {token_file}")


if __name__ == "__main__":
    main()
