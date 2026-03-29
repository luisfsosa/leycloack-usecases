# Patrones de Login UX para B2B y B2B2C en React

> **Pregunta central:** si tengo múltiples clientes enterprise (Toyota, Ford, BMW),
> ¿cómo sabe mi app React a qué Identity Provider mandar al usuario?

---

## Patrón 1: Lista de IDPs con logos

```
┌─────────────────────────────────┐
│  Iniciar sesión en Altana       │
│                                 │
│  [🔵 Continuar con Toyota]      │
│  [🟢 Continuar con Ford]        │
│  [🔴 Continuar con BMW]         │
│                                 │
│  ──── o ────                    │
│  [Usuario / Contraseña]         │
└─────────────────────────────────┘
```

React hardcodea los clientes conocidos. Cada botón arranca el PKCE flow
con `kc_idp_hint=toyota-corp` para ir directo al IDP sin pasar por el
login screen de Keycloak.

**Cuándo usarlo:** pocos clientes conocidos (< 10), SaaS con lista cerrada.

---

## Patrón 2: Email domain discovery

```
┌─────────────────────────────────┐
│  Tu email corporativo:          │
│  [john@toyota.com        ]      │
│  [Continuar →]                  │
└─────────────────────────────────┘
        ↓ React detecta @toyota.com
        ↓ mapea a kc_idp_hint=toyota-corp
        ↓ redirige directo al IDP de Toyota
```

React mantiene un mapa `dominio → IDP alias`. El usuario escribe su email,
React extrae el dominio y sabe a qué IDP mandar. Si el dominio no está
en el mapa, cae al login genérico de Keycloak.

**Cuándo usarlo:** muchos clientes, usuarios no técnicos, flujo estilo
Google Workspace o Microsoft 365.

---

## Patrón 3: Subdominio por cliente (white-label)

```
toyota.altana.com  →  kc_idp_hint=toyota-corp  →  Login Toyota
ford.altana.com    →  kc_idp_hint=ford-corp    →  Login Ford
app.altana.com     →  selector genérico
```

React lee `window.location.hostname` al arrancar. Si detecta un subdominio
conocido, arranca el PKCE flow ya con `kc_idp_hint` fijo. El usuario final
de Toyota nunca ve una pantalla de selección — va directo a su SSO.

**Cuándo usarlo:** clientes enterprise que quieren URL propia, portales
con branding del cliente (colores, logo del cliente en la app).

---

## B2B2C: ¿una app o varias?

### Opción A: Una sola app, UI condicional por `user_type`

El login es el mismo para todos. Después del callback, React lee los claims
del JWT (`user_type`, `roles`) y renderiza la UI correspondiente.

```
mismo login URL
    ↓
JWT con user_type=employee → Dashboard de analista
JWT con user_type=customer → Dashboard de cliente final
```

**Cuándo usarlo:** la diferencia entre empleado y cliente es solo de
permisos y vista, la app en sí es la misma.

### Opción B: Portal dedicado por tipo de usuario

```
app.altana.com          → empleados internos / analistas
toyota.altana.com       → portal de clientes finales de Toyota
  └── kc_idp_hint=toyota-corp hardcodeado
  └── branding de Toyota (logo, colores)
```

El portal de Toyota usa `kc_idp_hint` fijo. El usuario final ni sabe
que existe Keycloak detrás.

**Cuándo usarlo:** la experiencia es tan diferente que justifica una
app separada, o el cliente paga por white-label total.

---

## Resumen: ¿cuál patrón usar?

| Situación | Patrón |
|-----------|--------|
| Pocos clientes conocidos | Lista de botones con logos |
| Muchos clientes, usuarios no técnicos | Email domain discovery |
| Clientes enterprise con URL propia | Subdominio + `kc_idp_hint` fijo |
| B2B2C misma app, roles distintos | Una app, UI condicional por `user_type` |
| B2B2C portal dedicado por cliente | Subdominio separado + white-label |

---

## Pregunta de entrevista

**¿Cómo sabe tu app React a qué Identity Provider mandar al usuario?**

> Depende del contexto. Para un SaaS con lista cerrada de clientes mostramos
> botones por IDP usando `kc_idp_hint`. Para flujos self-service con muchos
> clientes usamos email domain discovery: el usuario escribe su email,
> extraemos el dominio y lo mapeamos al alias del IDP en Keycloak.
> Para clientes enterprise con white-label usamos subdominio propio —
> `toyota.altana.com` lleva `kc_idp_hint=toyota-corp` hardcodeado y el
> usuario nunca ve una pantalla de selección.
> En todos los casos Keycloak maneja el protocolo OAuth2/OIDC —
> React solo decide con qué `kc_idp_hint` arranca el PKCE flow.
