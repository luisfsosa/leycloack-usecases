"""
Validación de JWT con PyJWT >= 2.9

Por qué PyJWT en vez de python-jose:
  - python-jose lleva años sin mantenimiento activo (último release 2023)
  - PyJWT es la librería de referencia, mantenida activamente
  - PyJWKClient maneja la selección de key por 'kid' automáticamente
  - Cache de JWKS incluido — no fetch en cada request

PyJWKClient:
  - Se instancia UNA vez al arrancar el módulo (cache_keys=True por defecto)
  - Rota automáticamente las keys cuando Keycloak las rota (JWKS refresh)
  - Selecciona la clave correcta usando el header 'kid' del token
"""
import jwt
from jwt import PyJWKClient, ExpiredSignatureError, InvalidTokenError
from fastapi import HTTPException, status

from altana.config import settings

# Una sola instancia — cache automático de JWKS
_jwks_client = PyJWKClient(settings.jwks_uri, cache_keys=True)


async def decode_token(token: str) -> dict:
    """Valida y decodifica un JWT emitido por Keycloak."""
    try:
        # Obtiene la clave pública correcta usando el kid del header
        signing_key = _jwks_client.get_signing_key_from_jwt(token)

        payload = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            options={
                "verify_aud": False,  # audience validado manualmente si se necesita
            },
            issuer=settings.issuer,   # PyJWT valida iss automáticamente
        )
        return payload

    except ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token expirado",
            headers={"WWW-Authenticate": "Bearer error=invalid_token"},
        )
    except InvalidTokenError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Token invalido: {str(e)}",
            headers={"WWW-Authenticate": "Bearer"},
        )
