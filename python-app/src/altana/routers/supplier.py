"""
UC6 — Supplier Router

CONCEPT: Scoped data access
In a multi-tenant portal, a supplier sees only THEIR purchase orders.
The scope comes from the JWT: sub (user ID) and tenant_id (buying company).

In production:
  - tenant_id would be a custom claim in the Keycloak token
    (via Protocol Mapper that reads the user attribute)
  - Orders would come from PostgreSQL: WHERE supplier_id = :sub AND tenant_id = :tenant

INTERVIEW: "How do you implement multi-tenancy in an API?"
→ Option 1: claim in JWT → the API filters by tenant_id from the token (secure, simple).
→ Option 2: URL path (/tenants/{id}/orders) → validate that the token has access to that tenant.
→ NEVER trust a query/body parameter for the tenant — the token is the authority.
"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from altana.auth.dependencies import TokenData, get_current_user

router = APIRouter(prefix="/supplier", tags=["supplier"])


# ─── Mock data ───────────────────────────────────────────────────────────────

_MOCK_ORDERS = [
    {"po_number": "PO-2025-001", "item": "Brake pads A45",       "qty": 500,  "status": "pending"},
    {"po_number": "PO-2025-002", "item": "Steel bolts M8x30",    "qty": 2000, "status": "shipped"},
    {"po_number": "PO-2025-003", "item": "Rubber gaskets 75mm",  "qty": 1200, "status": "delivered"},
]

_MOCK_CERTIFICATIONS = [
    {"cert_id": "ISO-9001", "valid_until": "2026-12-31", "status": "active"},
    {"cert_id": "IATF-16949", "valid_until": "2025-06-30", "status": "expiring_soon"},
]


# ─── Schemas ─────────────────────────────────────────────────────────────────

class UploadRequest(BaseModel):
    document_type: str   # e.g. "certificate_of_origin", "quality_report"
    filename: str
    content_preview: str  # demo: plain text; production: presigned S3 URL


# ─── Endpoints ───────────────────────────────────────────────────────────────

@router.get("/dashboard")
async def supplier_dashboard(user: TokenData = Depends(get_current_user)) -> dict:
    """
    Supplier dashboard: purchase orders and certifications.

    The tenant_id comes from the JWT 'tenant_id' claim.
    In this mock demo it may not yet be present in the token;
    we fall back to "altana" if the custom claim is absent.

    In a real implementation the Keycloak Protocol Mapper would inject
    tenant_id into the token after onboarding completes.
    """
    tenant = user.tenant_id or "altana"

    return {
        "welcome":         f"Supplier Portal — {tenant.upper()}",
        "supplier":        user.username,
        "tenant_id":       tenant,
        "purchase_orders": _MOCK_ORDERS,
        "certifications":  _MOCK_CERTIFICATIONS,
        "pending_uploads": 1,
    }


@router.post("/upload")
async def upload_document(
    body: UploadRequest,
    user: TokenData = Depends(get_current_user),
) -> dict:
    """
    Simulates a supplier document upload.

    In production this endpoint would:
    1. Validate that the document type is one of the expected types
    2. Generate a presigned S3 URL for direct upload
    3. Record the upload in PostgreSQL with status 'pending_review'
    4. Fire an event in the supply chain system

    INTERVIEW: "How do you handle large file uploads in FastAPI?"
    → For large files: presigned S3 URL (the client uploads directly to S3,
      bypassing your API). For small files: multipart/form-data with
      FastAPI's UploadFile.
    """
    return {
        "status":          "accepted",
        "document_type":   body.document_type,
        "filename":        body.filename,
        "uploaded_by":     user.username,
        "review_status":   "pending",
        "message":         "Document received. It will be reviewed by the compliance team.",
    }
