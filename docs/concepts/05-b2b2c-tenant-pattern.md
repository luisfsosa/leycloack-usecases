# Patrón B2B2C — Multi-Tenant con Keycloak y FastAPI

> **Escenario:** Toyota (empresa cliente de Altana) tiene dos tipos de usuarios:
> - **Empleados internos** (`user_type=employee`) que analizan datos de supply chain
> - **Clientes finales** (`user_type=customer`) que solo ven sus propios envíos
>
> Ambos se autentican contra el IdP de Toyota, pero tienen acceso diferente en Altana.

---

## ¿Qué es B2B2C?

```
Altana (SaaS)
  └── Toyota (cliente B2B)
        ├── john.doe       → empleado interno → ROLE_ANALYST → ve todo
        └── jane.consumer  → cliente final    → ROLE_VIEWER  → ve solo su tenant
```

**B2B** = Toyota se autentica contra Altana via Identity Brokering
**B2B2C** = Los usuarios finales de Toyota (consumidores) también usan Altana, pero con acceso restringido al tenant de Toyota

---

## Arquitectura del flujo

```
jane.consumer            Keycloak altana-dev         Toyota IDP (toyota-corp)
      │                         │                           │
      │── login "Toyota" ──────►│                           │
      │                         │── redirect ──────────────►│
      │◄── redirect to Toyota ──│                           │
      │                                                     │
      │── credenciales ─────────────────────────────────────►│
      │◄── code ────────────────────────────────────────────│
      │                                                     │
      │── code ────────────────►│                           │
      │                         │── exchange code ─────────►│
      │                         │◄── id_token               │
      │                         │    (sub, email,           │
      │                         │     user_type=customer)   │
      │                         │                           │
      │                         │ [IDP Mappers ejecutados]  │
      │                         │  sync user_type=customer  │
      │                         │  → assign ROLE_VIEWER     │
      │                         │  → set tenant_id=toyota   │
      │                         │                           │
      │                         │ [Protocol Mappers]        │
      │                         │  → añade user_type al JWT │
      │                         │  → añade tenant_id al JWT │
      │                         │                           │
      │◄── JWT de Altana ───────│                           │
      │    roles: [ROLE_VIEWER] │                           │
      │    tenant_id: toyota    │                           │
      │    user_type: customer  │                           │
```

---

## Configuración en Keycloak

### 1. Toyota IDP (realm `toyota-corp`) — User Profile

Keycloak 24+ requiere declarar atributos custom en User Profile antes de usarlos.
Sin esta declaración, el atributo se ignora silenciosamente.

```
Admin → toyota-corp → Realm Settings → User Profile
→ Añadir atributo: user_type (String, no requerido)
```

```json
// PUT /admin/realms/toyota-corp/users/profile
{
  "attributes": [
    { "name": "username", ... },
    { "name": "email", ... },
    {
      "name": "user_type",
      "displayName": "User Type",
      "validations": {},
      "permissions": { "view": ["admin", "user"], "edit": ["admin"] }
    }
  ]
}
```

### 2. Protocol Mapper en `altana-broker` client (toyota-corp)

Para que `user_type` aparezca en el token que toyota-corp emite hacia altana-dev:

```
toyota-corp → Clients → altana-broker → Client Scopes → Mappers
→ Add mapper: user_type claim
  Type: User Attribute
  User Attribute: user_type
  Token Claim Name: user_type
  Add to access token: ON
```

### 3. IDP Mappers en altana-dev (para toyota-corp IDP)

Cinco mappers procesan el token de toyota-corp cuando un usuario llega:

| Mapper | Tipo | Qué hace |
|--------|------|----------|
| `email mapper` | `oidc-user-attribute-idp-mapper` | Copia email del IDP al usuario local |
| `sync user_type attribute` | `oidc-user-attribute-idp-mapper` | Copia claim `user_type` como atributo |
| `employee gets ANALYST` | `oidc-advanced-role-idp-mapper` | Si `user_type=employee` → asigna ROLE_ANALYST |
| `customer gets VIEWER` | `oidc-advanced-role-idp-mapper` | Si `user_type=customer` → asigna ROLE_VIEWER |
| `tenant_id toyota` | `hardcoded-attribute-idp-mapper` | Siempre asigna `tenant_id=toyota` |

**Configuración crítica — `syncMode: FORCE`**

```json
// mapper employee gets ANALYST
{
  "identityProviderMapper": "oidc-advanced-role-idp-mapper",
  "config": {
    "claims": "[{\"key\": \"user_type\", \"value\": \"employee\"}]",
    "role": "ROLE_ANALYST",
    "syncMode": "FORCE"
  }
}
```

> **IMPORT vs FORCE:**
> - `IMPORT`: el mapper solo corre en la primera vez que el usuario llega (nuevo usuario)
> - `FORCE`: el mapper corre en cada broker login, actualizando atributos y roles
>
> Usar `FORCE` es más seguro: si un usuario cambia de `employee` a `customer`,
> sus roles se actualizan en el siguiente login.

### 4. Protocol Mappers en `altana-web` client (altana-dev)

Para que `tenant_id` y `user_type` lleguen al JWT final que recibe FastAPI:

```
altana-dev → Clients → altana-web → Client Scopes → Mappers
→ user_type claim:  User Attribute "user_type"  → claim "user_type"
→ tenant_id claim:  User Attribute "tenant_id"  → claim "tenant_id"
```

---

## El JWT resultante

### john.doe (employee)
```json
{
  "sub": "...",
  "preferred_username": "john.doe",
  "email": "john.doe@toyota.com",
  "realm_access": {
    "roles": ["ROLE_ANALYST", "default-roles-altana-dev"]
  },
  "tenant_id": "toyota",
  "user_type": "employee"
}
```

### jane.consumer (customer)
```json
{
  "sub": "...",
  "preferred_username": "jane.consumer",
  "email": "jane.consumer@toyota.com",
  "realm_access": {
    "roles": ["ROLE_VIEWER", "default-roles-altana-dev"]
  },
  "tenant_id": "toyota",
  "user_type": "customer"
}
```

---

## Implementación en FastAPI

### TokenData — los campos B2B2C

```python
@dataclass
class TokenData:
    sub: str
    username: str
    email: str | None
    roles: list[str]
    tenant_id: str | None  # "toyota" | "ford" | None
    user_type: str | None  # "employee" | "customer" | None
    raw: dict
```

### Filtrado por tenant en el endpoint

```python
@router.get("/my-shipments")
async def my_shipments(
    user: TokenData = Depends(require_role("ROLE_VIEWER", "ROLE_ANALYST", "ROLE_ADMIN"))
):
    all_shipments = [
        {"id": "SH-001", "tenant_id": "toyota", ...},
        {"id": "SH-002", "tenant_id": "toyota", ...},
        {"id": "SH-003", "tenant_id": "ford",   ...},
        {"id": "SH-004", "tenant_id": "toyota", ...},
    ]

    # ROLE_VIEWER solo ve su tenant
    if "ROLE_VIEWER" in user.roles \
       and "ROLE_ANALYST" not in user.roles \
       and "ROLE_ADMIN" not in user.roles:
        tenant = user.tenant_id or ""
        return {
            "filter": f"tenant={tenant}",
            "shipments": [s for s in all_shipments if s["tenant_id"] == tenant]
        }

    # ANALYST / ADMIN ven todo
    return {"filter": "none (full access)", "shipments": all_shipments}
```

**Principio clave:** el `tenant_id` viene del JWT — ya fue validado criptográficamente por FastAPI.
El backend no necesita consultar Keycloak para saber de qué empresa es el usuario.

---

## Resultados de la prueba

```
GET /supply-chain/my-shipments

john.doe   (ROLE_ANALYST)  → filter: "none (full access)" → 4 envíos (toyota + ford)
jane.consumer (ROLE_VIEWER) → filter: "tenant=toyota"      → 3 envíos (solo toyota)

GET /supply-chain/shipments  (solo ANALYST/ADMIN)

jane.consumer (ROLE_VIEWER) → 403 Forbidden
  "Se requiere uno de estos roles: ['ROLE_ANALYST', 'ROLE_ADMIN']"
```

---

## Lecciones aprendidas (errores reales en este proyecto)

### 1. Keycloak 24+ User Profile bloquea atributos no declarados

**Síntoma:** `user_type` se guardaba en el usuario de toyota-corp sin error,
pero al leer el usuario via API el atributo no aparecía.

**Causa:** Keycloak 24 introdujo User Profile. Los atributos no declarados
se ignoran silenciosamente al guardar via Admin API.

**Fix:** Declarar el atributo en `PUT /admin/realms/{realm}/users/profile`
antes de intentar usarlo.

### 2. syncMode IMPORT no re-ejecuta mappers en usuarios existentes

**Síntoma:** usuarios ya importados no recibían los nuevos atributos/roles
aunque los mappers estuvieran correctamente configurados.

**Causa:** `syncMode: IMPORT` solo corre en la primera vez que el usuario
llega via el broker (primer login). Si el usuario ya existe, el mapper no se ejecuta.

**Fix:** Cambiar a `syncMode: FORCE`. O bien, borrar el usuario federado
de altana-dev para forzar un re-import limpio.

### 3. oidc-advanced-role-idp-mapper — formato del config `claims`

El campo `claims` espera un JSON array serializado como string:

```json
// CORRECTO
"claims": "[{\"key\": \"user_type\", \"value\": \"employee\"}]"

// INCORRECTO (objeto directo)
"claims": {"key": "user_type", "value": "employee"}
```

### 4. Siempre verificar mapper types via API antes de crear mappers

```bash
GET /admin/realms/{realm}/identity-provider/instances/{alias}/mapper-types
```

Los nombres de tipos cambian entre versiones de Keycloak.
En Keycloak 26: `oidc-advanced-role-idp-mapper` (no `advanced-role-idp-mapper`).

---

## Patrón para producción

En producción, el filtrado por tenant se hace en la capa de base de datos:

```python
# En vez de filtrar en memoria:
shipments = db.query(Shipment).filter(
    Shipment.tenant_id == user.tenant_id  # tenant del JWT
).all()
```

El `tenant_id` del JWT es confiable porque:
1. El JWT fue firmado por Keycloak (verificación criptográfica)
2. El `tenant_id` fue asignado por un `hardcoded-attribute-idp-mapper` en Keycloak
   (no lo puede cambiar el usuario)
3. FastAPI valida la firma antes de extraer cualquier claim

**Pregunta de entrevista:** ¿Cómo evitas que un usuario de Toyota vea datos de Ford?
> El `tenant_id` viene en el JWT firmado por Keycloak. El Resource Server (FastAPI)
> valida la firma y extrae el tenant. Todas las queries a DB incluyen
> `WHERE tenant_id = :tenant_id` con el valor del token. El usuario nunca
> puede manipular su propio tenant_id porque eso requeriría falsificar el JWT.
