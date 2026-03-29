# Credenciales del ambiente local

> Solo para desarrollo local. NUNCA usar estas credenciales en producción.

---

## Keycloak Admin

| URL | Usuario | Password |
|-----|---------|----------|
| http://localhost:8080 | `admin` | `admin` |

---

## Realm: altana-dev — Usuarios de prueba

| Username | Password | Roles | tenant_id | user_type | Notas |
|----------|----------|-------|-----------|-----------|-------|
| `admin-user` | `Test1234!` | `ROLE_ADMIN` | — | — | Acceso total |
| `analyst-user` | `Analyst123!` | `ROLE_ANALYST` | toyota | employee | Tiene phone_number para SMS OTP |
| `john.doe` | `Test1234!` | `ROLE_ANALYST` | toyota | employee | Federado desde toyota-corp |
| `jane.consumer` | `Test1234!` | `ROLE_VIEWER` | toyota | customer | Usuario B2B2C consumidor final |

---

## Realm: toyota-corp (IDP externo simulado)

| Username | Password | Notas |
|----------|----------|-------|
| `john.doe` | `Test1234!` | Se federa a altana-dev via OIDC broker. Password en **toyota-corp**, no en altana-dev. |
| `jane.consumer` | `Test1234!` | Se federa a altana-dev via OIDC broker. Password en **toyota-corp**, no en altana-dev. |

---

## Clients (OAuth2)

| Client ID | Tipo | Secret | Uso |
|-----------|------|--------|-----|
| `altana-web` | Público (PKCE) | — | React app, login de usuarios |
| `supply-chain-backend` | Confidencial | `CHANGE-ME-IN-PRODUCTION` | Spring Boot / FastAPI service account |
| `python-fastapi` | Confidencial | ver .env | FastAPI resource server |

---

## Servicios locales

| Servicio | URL | Notas |
|----------|-----|-------|
| Keycloak | http://localhost:8080 | admin / admin |
| Spring Boot | http://localhost:8081 | Resource Server Java |
| FastAPI | http://localhost:8081 | Resource Server Python (comparte puerto con Spring, nunca simultáneos) |
| React | http://localhost:5173 | Frontend PKCE |
| MailHog UI | http://localhost:8025 | Emails OTP de prueba |
| PostgreSQL | localhost:5432 | keycloak / keycloak_dev_pass |

---

## Comandos para levantar todo

```bash
# 1. Keycloak + PostgreSQL + MailHog
cd docker && docker compose up -d

# 2. Spring Boot
cd spring-app
JAVA_HOME="/c/Users/Felipe_Sosa/.jdks/openjdk-26" ./gradlew bootRun

# 3. FastAPI
cd python-app
source venv/Scripts/activate   # Windows
python -m uvicorn altana.main:app --port 8002 --reload --app-dir src

# 4. React
cd react-app
npm run dev
```
