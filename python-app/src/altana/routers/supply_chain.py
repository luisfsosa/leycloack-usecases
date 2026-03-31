"""
CONCEPT: Resource Server

This router represents the protected supply chain API.
It knows nothing about Keycloak or how the token was issued.
It only receives the already-validated user via dependency injection.

Patterns demonstrated:
- Public endpoint (no auth)
- Authenticated endpoint (any logged-in user)
- Role-specific endpoint (RBAC)
- Endpoint that uses token data (current user)
"""

from fastapi import APIRouter, Depends
from altana.auth.dependencies import TokenData, get_current_user, require_role

router = APIRouter(prefix="/supply-chain", tags=["Supply Chain"])


# ─── PUBLIC ENDPOINT ──────────────────────────────────────────────────────────

@router.get("/health")
async def health():
    """No authentication — for load balancers and health checks."""
    return {"status": "ok", "service": "supply-chain-api"}


# ─── AUTHENTICATED ENDPOINT (any user) ───────────────────────────────────────

@router.get("/suppliers")
async def list_suppliers(user: TokenData = Depends(get_current_user)):
    """
    Requires a valid token. Any authenticated user can list suppliers.

    CONCEPT: the token arrives in the header:
      Authorization: Bearer eyJhbGci...
    FastAPI extracts it, get_current_user validates and deserializes it.
    """
    return {
        "requested_by": user.username,
        "suppliers": [
            {"id": 1, "name": "Acme Corp", "country": "US"},
            {"id": 2, "name": "Global Parts", "country": "DE"},
        ]
    }


@router.get("/me")
async def my_profile(user: TokenData = Depends(get_current_user)):
    """Returns the current user's data extracted from the JWT."""
    return {
        "sub":       user.sub,
        "username":  user.username,
        "email":     user.email,
        "roles":     user.roles,
        "tenant_id": user.tenant_id,   # B2B2C: "toyota" | None
        "user_type": user.user_type,   # B2B2C: "employee" | "customer" | None
    }


# ─── RBAC ENDPOINTS ───────────────────────────────────────────────────────────

@router.get(
    "/shipments",
    dependencies=[Depends(require_role("ROLE_ANALYST", "ROLE_ADMIN"))]
)
async def list_shipments(user: TokenData = Depends(get_current_user)):
    """
    ROLE_ANALYST or ROLE_ADMIN only.

    CONCEPT: two ways to use require_role:
    1. In 'dependencies=[]' — only verifies, does not inject the user
    2. As a parameter — verifies AND gives access to the user in the handler
    """
    return {
        "requested_by": user.username,
        "shipments": [
            {"id": "SH-001", "origin": "Shanghai", "destination": "LA", "status": "in_transit"},
            {"id": "SH-002", "origin": "Hamburg",  "destination": "NY", "status": "delivered"},
        ]
    }


@router.delete(
    "/suppliers/{supplier_id}",
    dependencies=[Depends(require_role("ROLE_ADMIN"))]
)
async def delete_supplier(supplier_id: int, user: TokenData = Depends(get_current_user)):
    """Only ROLE_ADMIN can delete suppliers."""
    return {
        "deleted_by": user.username,
        "supplier_id": supplier_id,
        "message": f"Supplier {supplier_id} deleted"
    }


@router.post("/compliance/flag")
async def flag_compliance(
    supplier_id: int,
    reason: str,
    user: TokenData = Depends(require_role("ROLE_ANALYST", "ROLE_ADMIN")),
):
    """
    Alternative form: require_role as a direct dependency on the parameter.
    The user here has already been validated with the correct role.
    """
    return {
        "flagged_by": user.username,
        "supplier_id": supplier_id,
        "reason": reason,
        "status": "flagged"
    }


# ─── B2B2C ENDPOINTS ──────────────────────────────────────────────────────────

@router.get("/my-shipments")
async def my_shipments(user: TokenData = Depends(require_role("ROLE_VIEWER", "ROLE_ANALYST", "ROLE_ADMIN"))):
    """
    B2B2C: endpoint for end consumers (ROLE_VIEWER) and employees.

    CONCEPT: filtering by tenant_id
    - ROLE_VIEWER (consumer): sees only shipments for their company (tenant_id)
    - ROLE_ANALYST/ADMIN: sees all shipments

    In production: tenant_id is used to filter in the database.
    Here we simulate the filtering with in-memory data.
    """
    # Simulated dataset with tenant_id
    all_shipments = [
        {"id": "SH-001", "origin": "Shanghai",  "destination": "LA", "status": "in_transit", "tenant_id": "toyota"},
        {"id": "SH-002", "origin": "Hamburg",   "destination": "NY", "status": "delivered",  "tenant_id": "toyota"},
        {"id": "SH-003", "origin": "Tokyo",     "destination": "LA", "status": "pending",    "tenant_id": "ford"},
        {"id": "SH-004", "origin": "Osaka",     "destination": "NY", "status": "in_transit", "tenant_id": "toyota"},
    ]

    # ROLE_VIEWER: sees only their tenant
    if "ROLE_VIEWER" in user.roles and "ROLE_ANALYST" not in user.roles and "ROLE_ADMIN" not in user.roles:
        tenant = user.tenant_id or ""
        shipments = [s for s in all_shipments if s["tenant_id"] == tenant]
        return {
            "requested_by": user.username,
            "tenant_id":    tenant,
            "filter":       f"tenant={tenant}",
            "shipments":    shipments,
        }

    # ROLE_ANALYST / ROLE_ADMIN: sees everything
    return {
        "requested_by": user.username,
        "filter":       "none (full access)",
        "shipments":    all_shipments,
    }
