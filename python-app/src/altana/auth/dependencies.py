"""
CONCEPT: FastAPI Dependencies

In FastAPI, dependencies are functions injected into endpoints.
They are the right place for authentication and authorization logic.

Advantages:
- Reusable across multiple endpoints
- Testable independently
- Declarative: the endpoint says WHAT it needs, not HOW to get it

INTERVIEW: "How do you implement RBAC in FastAPI?"
→ With dependencies that extract roles from the JWT and raise 403 if the
  required role is absent.
"""

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from dataclasses import dataclass

from altana.auth.keycloak import decode_token

# Extracts the Bearer token from the Authorization header
bearer_scheme = HTTPBearer()


@dataclass
class TokenData:
    """Authenticated user data extracted from the JWT."""
    sub: str           # unique user ID in Keycloak
    username: str      # preferred_username
    email: str | None
    roles: list[str]   # realm roles
    tenant_id: str | None  # B2B2C: originating company (e.g. "toyota")
    user_type: str | None  # B2B2C: "employee" | "customer" | None
    raw: dict          # full payload for anything else you need


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme),
) -> TokenData:
    """
    Dependency: extracts and validates the token from the Authorization header.
    Use on any endpoint that requires authentication.

    Usage:
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
    Dependency factory: creates a dependency that requires at least one of the given roles.

    CONCEPT: dependency factory — a function that returns a dependency.
    Allows parameterising the authorisation logic.

    Usage:
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
                detail=f"One of these roles is required: {list(roles)}. "
                       f"User roles: {user.roles}",
            )
        return user

    return _check_role
