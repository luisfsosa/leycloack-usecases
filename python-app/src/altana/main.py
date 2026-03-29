from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
import jwt as pyjwt
import httpx

from altana.routers import supply_chain, invitations, supplier
from altana.config import settings

app = FastAPI(title="Altana Supply Chain API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",   # React CRA / Next.js
        "http://localhost:5173",   # Vite dev server
        "http://localhost:5174",   # Vite segundo puerto (fallback)
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(supply_chain.router)
app.include_router(invitations.router)
app.include_router(supplier.router)


@app.get("/")
async def root():
    return {"service": "Altana Supply Chain API", "docs": "/docs"}


@app.get("/debug/token")
async def debug_token(request: Request):
    """Endpoint temporal de debug — REMOVER en produccion."""
    auth = request.headers.get("authorization", "")
    if not auth.startswith("Bearer "):
        return {"error": "no bearer token"}

    token = auth[7:]  # quitar "Bearer "

    header = pyjwt.get_unverified_header(token)
    jwks = httpx.get(settings.jwks_uri).json()
    kids_in_jwks = [k["kid"] for k in jwks.get("keys", [])]

    try:
        from jwt import PyJWKClient
        signing_key = PyJWKClient(settings.jwks_uri).get_signing_key_from_jwt(token)
        payload = pyjwt.decode(
            token, signing_key.key, algorithms=["RS256"],
            options={"verify_aud": False}
        )
        return {
            "token_kid": header.get("kid"),
            "jwks_kids": kids_in_jwks,
            "kid_found": header.get("kid") in kids_in_jwks,
            "decode_ok": True,
            "username": payload.get("preferred_username"),
            "roles": payload.get("realm_access", {}).get("roles", []),
            "token_len": len(token),
            "token_last_chars": repr(token[-10:]),
        }
    except Exception as e:
        return {
            "token_kid": header.get("kid"),
            "jwks_kids": kids_in_jwks,
            "kid_found": header.get("kid") in kids_in_jwks,
            "decode_ok": False,
            "error": str(e),
            "token_len": len(token),
            "token_last_chars": repr(token[-10:]),
        }
