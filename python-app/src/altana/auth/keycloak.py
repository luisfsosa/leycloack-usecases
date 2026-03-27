import httpx
from jose import jwt, JWTError, ExpiredSignatureError
from fastapi import HTTPException, status

from altana.config import settings


async def decode_token(token: str) -> dict:
    """Valida y decodifica un JWT de Keycloak."""
    try:
        # Fetch JWKS fresco (luego agregaremos cache)
        async with httpx.AsyncClient() as client:
            response = await client.get(settings.jwks_uri)
            response.raise_for_status()
            jwks = response.json()

        payload = jwt.decode(
            token,
            jwks,
            algorithms=["RS256"],
            options={"verify_aud": False, "verify_iss": False},
        )

        # Validar issuer manualmente
        token_iss = payload.get("iss", "")
        if token_iss != settings.issuer:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=f"Issuer invalido: {token_iss}",
            )

        return payload

    except HTTPException:
        raise
    except ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token expirado",
            headers={"WWW-Authenticate": "Bearer error=invalid_token"},
        )
    except JWTError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Token invalido: {str(e)}",
            headers={"WWW-Authenticate": "Bearer"},
        )
