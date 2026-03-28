# Guía de pruebas B2B — Identity Brokering

> Esta guía explica cómo reproducir el flujo B2B completo desde cero usando
> los scripts del proyecto. Úsala cada vez que quieras testear el patrón
> B2B de toyota-corp → altana-dev → FastAPI.

---

## Prerequisitos — verificar antes de empezar

```powershell
# 1. Keycloak corriendo
curl http://localhost:8080/realms/altana-dev/.well-known/openid-configuration
# debe responder JSON con "issuer": "http://localhost:8080/realms/altana-dev"

# 2. FastAPI corriendo (en otro terminal)
# cd python-app\src
# uvicorn altana.main:app --reload --port 8003

# 3. Verificar que toyota-corp IDP existe en altana-dev
# (si no existe, ver sección "Setup desde cero" al final de esta guía)
```

Si Keycloak no está corriendo:
```powershell
cd docker
docker compose up -d
```

---

## Flujo de prueba — paso a paso

### Terminal 1 — Servidor de captura del callback

Déjalo corriendo durante toda la prueba. Captura el `code` automáticamente
cuando Keycloak redirige de vuelta después del login.

```powershell
python scripts\capture_callback.py
```

Verás:
```
Servidor de captura escuchando en http://localhost:3000
Esperando callback de Keycloak...
```

---

### Terminal 2 — Generar URL de autorización PKCE

```powershell
python scripts\generate_b2b_url.py
```

El script imprime tres secciones:

```
======================================================================
PKCE VALUES (guárdalos para el exchange):
  code_verifier  : abc123...    ← ANÓTALO, lo necesitas para el exchange
  code_challenge : xyz789...
  state          : qrs456...

======================================================================
AUTHORIZATION URL (copia y pega en el browser):

http://localhost:8080/realms/altana-dev/...&kc_idp_hint=toyota-corp

======================================================================
EXCHANGE COMMAND (referencia — usar exchange_code.py en Windows):
  -d "code_verifier=abc123..."
```

> **IMPORTANTE:** El `code_verifier` es de un solo uso y expira con el code
> (300 segundos). Si el login tarda más, regenera con el mismo script.

---

### Browser — Login B2B

1. Copia el URL completo de la sección `AUTHORIZATION URL`
2. Pégalo en el browser (**ventana incógnito** recomendado para evitar SSO)
3. Keycloak redirige directo a toyota-corp (gracias a `kc_idp_hint`)
4. Login con: `john.doe` / `toyota123`
5. El browser redirige a `localhost:3000/callback?code=XXX&state=YYY`

**El Terminal 1 imprime automáticamente:**
```
======================================================================
CALLBACK CAPTURADO
  code  : e81da983-ee62-...     ← cópialo
  state : qrs456...
======================================================================
```

---

### Terminal 2 — Exchange code → tokens

```powershell
python scripts\exchange_code.py
```

El script pregunta interactivamente:

```
TOKEN EXCHANGE — Authorization Code → Tokens
============================================================

Pega el code capturado:
> e81da983-ee62-...      ← pega el code del Terminal 1

Pega el code_verifier (del script generate_b2b_url):
> abc123...              ← pega el verifier de antes
```

**Salida esperada:**
```
======================================================================
TOKENS OBTENIDOS
======================================================================
  token_type   : Bearer
  expires_in   : 300s
  access_token : eyJhbGci...

======================================================================
ACCESS TOKEN PAYLOAD
======================================================================
{
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "iss": "http://localhost:8080/realms/altana-dev",
  "realm_access": {
    "roles": [
      "default-roles-altana-dev",
      "offline_access",
      "ROLE_ANALYST",            ← debe estar aquí
      "uma_authorization"
    ]
  }
}

======================================================================
RESUMEN
======================================================================
  Usuario : john.doe
  Email   : john.doe@toyota.com
  Issuer  : http://localhost:8080/realms/altana-dev
  Roles   : ['default-roles-altana-dev', 'offline_access', 'ROLE_ANALYST', ...]

[token guardado en scripts/.last_access_token]
```

---

### Terminal 2 — Probar el token en FastAPI

El script `exchange_code.py` guarda el token en `scripts/.last_access_token`.
Usarlo para llamar a los endpoints protegidos:

```powershell
# Leer el token guardado
$token = Get-Content scripts\.last_access_token

# Endpoint público (sin auth)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/health `
  | Select-Object -ExpandProperty Content

# Endpoint autenticado — cualquier usuario con token válido
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/me `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content

# Endpoint con ROLE_ANALYST (john.doe debería poder)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/shipments `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content

# Endpoint con ROLE_ADMIN (john.doe NO puede — esperar 403)
Invoke-WebRequest -Uri http://localhost:8003/supply-chain/suppliers/test-id `
  -Method Delete `
  -Headers @{Authorization="Bearer $token"} `
  | Select-Object -ExpandProperty Content
```

**Resultados esperados:**

| Endpoint | john.doe (ROLE_ANALYST) |
|----------|------------------------|
| `GET /health` | ✅ 200 — público |
| `GET /me` | ✅ 200 — autenticado |
| `GET /shipments` | ✅ 200 — tiene ROLE_ANALYST |
| `DELETE /suppliers/{id}` | ❌ 403 — requiere ROLE_ADMIN |

---

## Troubleshooting — problemas comunes

### "State mismatch" o token expirado

El code de autorización dura 300 segundos. Si pasó mucho tiempo:
```powershell
# Regenerar todo desde el paso 2
python scripts\generate_b2b_url.py
```

### "ROLE_ANALYST no aparece en el token"

Significa que el mapper no corrió porque john.doe ya existía en altana-dev
(IMPORT solo corre al crear el usuario):

```powershell
python scripts\reset_b2b_user.py
```

Luego volver al paso del browser y hacer login de nuevo.

### "target is null" en los logs de Keycloak

El mapper tiene un tipo incorrecto. Verificar:
```powershell
python scripts\verify_b2b_setup.py
```

### "connection refused" en localhost:3000

El servidor de captura no está corriendo. Abrir Terminal 1 y ejecutar:
```powershell
python scripts\capture_callback.py
```

### john.doe no puede hacer login (credenciales incorrectas)

Las credenciales en toyota-corp son `john.doe` / `toyota123`.
Si el realm fue recreado, el password puede haberse perdido:
```powershell
python scripts\reset_toyota_user.py
```

---

## Scripts de soporte

| Script | Propósito |
|--------|-----------|
| `scripts\generate_b2b_url.py` | Genera PKCE + URL de autorización con kc_idp_hint |
| `scripts\capture_callback.py` | Servidor HTTP en puerto 3000 que captura el code |
| `scripts\exchange_code.py` | Intercambia code + verifier por tokens, muestra payload |
| `scripts\reset_b2b_user.py` | Borra john.doe de altana-dev para forzar re-import |
| `scripts\verify_b2b_setup.py` | Verifica que toda la config B2B esté correcta |

---

## Setup desde cero (si los realms no existen)

Si perdiste los datos de Keycloak (ej: recreaste los contenedores sin volumen
persistente), ejecutar en orden:

```powershell
# 1. Crear toyota-corp realm + usuario john.doe
python scripts\setup_toyota_idp.py

# 2. Verificar la config
python scripts\verify_b2b_setup.py

# 3. Proceder con el flujo normal desde el paso 1
```

---

## Referencia rápida — datos del entorno B2B

```
Keycloak:         http://localhost:8080
Admin:            admin / admin

Realm broker:     altana-dev
  Cliente SPA:    altana-web (público, PKCE)
  IDP registrado: toyota-corp (alias)
  Mapper:         oidc-hardcoded-role-idp-mapper → ROLE_ANALYST

Realm IDP ext:    toyota-corp (simula empresa cliente)
  Cliente broker: altana-broker (secret: altana-broker-secret)
  Usuario prueba: john.doe / toyota123

FastAPI:          http://localhost:8003
  /supply-chain/health     → público
  /supply-chain/me         → cualquier token válido
  /supply-chain/shipments  → ROLE_ANALYST o ROLE_ADMIN
  /supply-chain/suppliers  → DELETE requiere ROLE_ADMIN
```
