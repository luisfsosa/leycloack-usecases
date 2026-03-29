"""
CONCEPTO: FastAPI Dependencies

En FastAPI las dependencias son funciones que se inyectan en los endpoints.
Son el lugar correcto para poner logica de autenticacion/autorizacion.

Ventajas:
- Reutilizables en multiples endpoints
- Testeables independientemente
- Declarativas: el endpoint dice QUE necesita, no COMO obtenerlo

ENTREVISTA: "¿Como implementas RBAC en FastAPI?"
→ Con dependencies que extraen roles del JWT y lanzan 403 si no tiene el rol requerido.
"""

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from dataclasses import dataclass

from altana.auth.keycloak import decode_token

# Extrae el Bearer token del header Authorization
bearer_scheme = HTTPBearer()


@dataclass
class TokenData:
    """Datos del usuario autenticado extraidos del JWT."""
    sub: str           # ID unico del usuario en Keycloak
    username: str      # preferred_username
    email: str | None
    roles: list[str]   # realm roles
    tenant_id: str | None  # B2B2C: empresa de origen (ej: "toyota")
    user_type: str | None  # B2B2C: "employee" | "customer" | None
    raw: dict          # payload completo por si necesitas algo mas


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme),
) -> TokenData:
    """
    Dependency: extrae y valida el token del header Authorization.
    Usar en cualquier endpoint que requiera autenticacion.

    Uso:
        @app.get("/me")
        async def me(user: TokenData = Depends(get_current_user)):
            return {"username": user.username}
    """
    payload = await decode_token(credentials.credentials)

    return TokenData(
        sub=payload.get("sub", ""),
        username=payload.get("preferred_username", ""),
        email=payload.get("email"),
        roles=payload.get("realm_access", {}).get("roles", []),
        tenant_id=payload.get("tenant_id"),
        user_type=payload.get("user_type"),
        raw=payload,
    )


def require_role(*roles: str):
    """
    Dependency factory: crea una dependencia que requiere al menos uno de los roles.

    CONCEPTO: dependency factory — funcion que retorna una dependency.
    Permite parametrizar la logica de autorizacion.

    Uso:
        @app.get("/admin")
        async def admin(user = Depends(require_role("ROLE_ADMIN"))):
            ...

        @app.get("/reports")
        async def reports(user = Depends(require_role("ROLE_ADMIN", "ROLE_ANALYST"))):
            ...
    """
    async def _check_role(
        user: TokenData = Depends(get_current_user),
    ) -> TokenData:
        if not any(role in user.roles for role in roles):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Se requiere uno de estos roles: {list(roles)}. "
                       f"Roles del usuario: {user.roles}",
            )
        return user

    return _check_role
