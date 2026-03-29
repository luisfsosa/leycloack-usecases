"""
UC6 — Invitation Router

CONCEPT: Invitations as signed JWTs
An invitation token is a JWT signed with HS256 (symmetric key) that carries
invitation context. It is NOT a Keycloak token — we sign it ourselves.

Why JWT for invitations?
→ Self-contained: no database needed to validate the invitation.
  The token carries its own expiry (exp), tenant and tier.
  Only the secret is needed to verify the signature.

INTERVIEW: "When would you use HS256 vs RS256?"
→ HS256 (symmetric): when only ONE party signs AND verifies (this service).
  Simple, fast. Drawback: you cannot distribute a public key.
→ RS256 (asymmetric): when multiple services need to verify
  (Keycloak → many microservices). Only the issuer holds the private key;
  verifiers only need the public key (JWKS endpoint).
"""

import time
from typing import Any

import jwt as pyjwt
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

from altana.auth.dependencies import TokenData, get_current_user, require_role
from altana.config import settings

router = APIRouter(prefix="/invitations", tags=["invitations"])

# ─── In-memory store (demo) ──────────────────────────────────────────────────
# In production: PostgreSQL table with columns (token_jti, used_at, accepted_by)
_used_invitations: dict[str, dict] = {}


# ─── Schemas ─────────────────────────────────────────────────────────────────

class CreateInvitationRequest(BaseModel):
    tenant_id: str          # e.g. "toyota"
    supplier_tier: int      # 2 or 3
    invited_email: str      # supplier's email address
    ttl_days: int = 7       # validity in days


class InvitationDetails(BaseModel):
    tenant_id: str
    supplier_tier: int
    invited_email: str
    invited_by: str
    expires_at: int         # UTC Unix timestamp


class CompleteResponse(BaseModel):
    message: str
    user: str
    tenant_id: str
    supplier_tier: int
    roles_granted: list[str]


# ─── Helpers ─────────────────────────────────────────────────────────────────

def _sign_invitation(payload: dict) -> str:
    """Signs the invitation token with HS256 using INVITATION_SECRET."""
    return pyjwt.encode(payload, settings.invitation_secret, algorithm="HS256")


def _decode_invitation(token: str) -> dict[str, Any]:
    """
    Verifies the signature and expiry of an invitation token.
    Raises HTTPException 400 if the token is invalid or expired.
    """
    try:
        return pyjwt.decode(
            token,
            settings.invitation_secret,
            algorithms=["HS256"],
        )
    except pyjwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="The invitation has expired.",
        )
    except pyjwt.InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid invitation token: {exc}",
        )


# ─── Endpoints ───────────────────────────────────────────────────────────────

@router.post("", status_code=status.HTTP_201_CREATED)
async def create_invitation(
    body: CreateInvitationRequest,
    admin: TokenData = Depends(require_role("ROLE_ADMIN")),
) -> dict:
    """
    Creates a JWT invitation for a supplier.
    Requires the ROLE_ADMIN role (e.g. procurement@toyota.com).

    The token carries 'exp' — Keycloak does NOT intervene here.
    We are the issuer of this specific token.
    """
    now = int(time.time())
    exp = now + body.ttl_days * 86400   # 86400 = seconds in a day

    payload = {
        "tenant_id":     body.tenant_id,
        "supplier_tier": body.supplier_tier,
        "invited_email": body.invited_email,
        "invited_by":    admin.email or admin.username,
        "exp":           exp,
        "iat":           now,
    }

    token = _sign_invitation(payload)
    invite_link = f"http://localhost:5173/accept-invite?token={token}"

    return {
        "invitation_token": token,
        "invite_link":      invite_link,
        "expires_at":       exp,
        "invited_email":    body.invited_email,
        "tenant_id":        body.tenant_id,
        "supplier_tier":    body.supplier_tier,
    }


@router.get("/{token}", response_model=InvitationDetails)
async def get_invitation(token: str) -> InvitationDetails:
    """
    Decodes and validates an invitation. PUBLIC endpoint.
    React calls this to show the invitation details before the supplier
    decides whether to create an account or log in with an existing one.

    INTERVIEW: "Why is it safe to expose this endpoint without authentication?"
    → It only reveals information already in the token (which the user already has).
      It modifies no state. The signature guarantees the token was not tampered with.
      The exp field guarantees it is not an old token being reused.
    """
    payload = _decode_invitation(token)

    return InvitationDetails(
        tenant_id=payload["tenant_id"],
        supplier_tier=payload["supplier_tier"],
        invited_email=payload["invited_email"],
        invited_by=payload["invited_by"],
        expires_at=payload["exp"],
    )


@router.post("/{token}/complete", response_model=CompleteResponse)
async def complete_invitation(
    token: str,
    user: TokenData = Depends(get_current_user),
) -> CompleteResponse:
    """
    Marks the invitation as used and returns the enriched user profile.
    Requires the supplier to already be authenticated in Keycloak.

    This is the "bridge" between the invitation (our JWT) and the user's
    identity (Keycloak token). When this endpoint is called:
    1. We verify the invitation (signature + exp + not yet used)
    2. We verify the user is authenticated (Keycloak token)
    3. We associate tenant_id with the user (in production: write to DB)

    INTERVIEW: "How do you prevent a supplier from using the same invitation twice?"
    → We store the token fingerprint (email + iat) in the database with a used_at
      timestamp. Before completing, we check it is not already in the used set.
      This demo uses an in-memory dict.
    """
    payload = _decode_invitation(token)

    # Check that the invitation has not been used before
    token_key = f"{payload['invited_email']}:{payload['iat']}"
    if token_key in _used_invitations:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This invitation has already been used.",
        )

    # Mark as used
    _used_invitations[token_key] = {
        "accepted_by": user.sub,
        "accepted_at": int(time.time()),
    }

    # In production: write to DB and assign roles via Keycloak Admin API.
    # We return the enriched context so React can redirect to the correct portal.
    return CompleteResponse(
        message="Onboarding complete. Welcome to the supplier portal.",
        user=user.username,
        tenant_id=payload["tenant_id"],
        supplier_tier=payload["supplier_tier"],
        roles_granted=["supplier:read", "supplier:upload"],
    )
