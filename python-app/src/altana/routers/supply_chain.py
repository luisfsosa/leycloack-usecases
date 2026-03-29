"""
CONCEPTO: Resource Server

Este router representa la API protegida de supply chain.
No sabe nada de Keycloak ni de como se emitio el token.
Solo recibe el usuario ya validado via dependency injection.

Patrones demostrados:
- Endpoint publico (sin auth)
- Endpoint autenticado (cualquier usuario logueado)
- Endpoint con rol especifico (RBAC)
- Endpoint que usa datos del token (usuario actual)
"""

from fastapi import APIRouter, Depends
from altana.auth.dependencies import TokenData, get_current_user, require_role

router = APIRouter(prefix="/supply-chain", tags=["Supply Chain"])


# ─── ENDPOINT PUBLICO ────────────────────────────────────────────────────────

@router.get("/health")
async def health():
    """Sin autenticacion — para load balancers y health checks."""
    return {"status": "ok", "service": "supply-chain-api"}


# ─── ENDPOINT AUTENTICADO (cualquier usuario) ────────────────────────────────

@router.get("/suppliers")
async def list_suppliers(user: TokenData = Depends(get_current_user)):
    """
    Requiere token valido. Cualquier usuario autenticado puede ver proveedores.

    CONCEPTO: el token llega en el header:
      Authorization: Bearer eyJhbGci...
    FastAPI lo extrae, get_current_user lo valida y lo deserializa.
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
    """Devuelve los datos del usuario actual extraidos del JWT."""
    return {
        "sub":       user.sub,
        "username":  user.username,
        "email":     user.email,
        "roles":     user.roles,
        "tenant_id": user.tenant_id,   # B2B2C: "toyota" | None
        "user_type": user.user_type,   # B2B2C: "employee" | "customer" | None
    }


# ─── ENDPOINTS CON RBAC ──────────────────────────────────────────────────────

@router.get(
    "/shipments",
    dependencies=[Depends(require_role("ROLE_ANALYST", "ROLE_ADMIN"))]
)
async def list_shipments(user: TokenData = Depends(get_current_user)):
    """
    Solo ROLE_ANALYST o ROLE_ADMIN.

    CONCEPTO: dos formas de usar require_role:
    1. En 'dependencies=[]' — solo verifica, no inyecta el user
    2. Como parametro — verifica Y da acceso al user en el handler
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
    """Solo ROLE_ADMIN puede eliminar proveedores."""
    return {
        "deleted_by": user.username,
        "supplier_id": supplier_id,
        "message": f"Proveedor {supplier_id} eliminado"
    }


@router.post("/compliance/flag")
async def flag_compliance(
    supplier_id: int,
    reason: str,
    user: TokenData = Depends(require_role("ROLE_ANALYST", "ROLE_ADMIN")),
):
    """
    Forma alternativa: require_role como dependency directa en el parametro.
    El user aqui ya fue validado con el rol correcto.
    """
    return {
        "flagged_by": user.username,
        "supplier_id": supplier_id,
        "reason": reason,
        "status": "flagged"
    }


# ─── ENDPOINTS B2B2C ─────────────────────────────────────────────────────────

@router.get("/my-shipments")
async def my_shipments(user: TokenData = Depends(require_role("ROLE_VIEWER", "ROLE_ANALYST", "ROLE_ADMIN"))):
    """
    B2B2C: endpoint para consumidores finales (ROLE_VIEWER) y empleados.

    CONCEPTO: filtrado por tenant_id
    - ROLE_VIEWER (consumidor): ve solo los envios de su empresa (tenant_id)
    - ROLE_ANALYST/ADMIN: ve todos los envios

    En produccion: tenant_id se usa para filtrar en base de datos.
    Aqui simulamos el filtrado con datos en memoria.
    """
    # Dataset simulado con tenant_id
    all_shipments = [
        {"id": "SH-001", "origin": "Shanghai",  "destination": "LA", "status": "in_transit", "tenant_id": "toyota"},
        {"id": "SH-002", "origin": "Hamburg",   "destination": "NY", "status": "delivered",  "tenant_id": "toyota"},
        {"id": "SH-003", "origin": "Tokyo",     "destination": "LA", "status": "pending",    "tenant_id": "ford"},
        {"id": "SH-004", "origin": "Osaka",     "destination": "NY", "status": "in_transit", "tenant_id": "toyota"},
    ]

    # ROLE_VIEWER: solo ve su tenant
    if "ROLE_VIEWER" in user.roles and "ROLE_ANALYST" not in user.roles and "ROLE_ADMIN" not in user.roles:
        tenant = user.tenant_id or ""
        shipments = [s for s in all_shipments if s["tenant_id"] == tenant]
        return {
            "requested_by": user.username,
            "tenant_id":    tenant,
            "filter":       f"tenant={tenant}",
            "shipments":    shipments,
        }

    # ROLE_ANALYST / ROLE_ADMIN: ve todo
    return {
        "requested_by": user.username,
        "filter":       "none (full access)",
        "shipments":    all_shipments,
    }
