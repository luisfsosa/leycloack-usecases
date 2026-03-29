"""
JWT validation with PyJWT >= 2.9

Why PyJWT instead of python-jose:
  - python-jose has had no active maintenance for years (last release 2023)
  - PyJWT is the reference library, actively maintained
  - PyJWKClient handles key selection by 'kid' automatically
  - Built-in JWKS caching — no fetch on every request

PyJWKClient:
  - Instantiated ONCE when the module loads (cache_keys=True by default)
  - Automatically rotates keys when Keycloak rotates them (JWKS refresh)
  - Selects the correct key using the 'kid' header from the token
"""
import jwt
from jwt import PyJWKClient, ExpiredSignatureError, InvalidTokenError
from fastapi import HTTPException, status

from altana.config import settings

# Single instance — automatic JWKS caching
_jwks_client = PyJWKClient(settings.jwks_uri, cache_keys=True)


async def decode_token(token: str) -> dict:
    """Validates and decodes a JWT issued by Keycloak."""
    try:
        # Retrieves the correct public key using the kid from the token header
        signing_key = _jwks_client.get_signing_key_from_jwt(token)

        payload = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            options={
                "verify_aud": False,  # audience validated manually if needed
            },
            issuer=settings.issuer,   # PyJWT validates iss automatically
        )
        return payload

    except ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token expired",
            headers={"WWW-Authenticate": "Bearer error=invalid_token"},
        )
    except InvalidTokenError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid token: {str(e)}",
            headers={"WWW-Authenticate": "Bearer"},
        )
