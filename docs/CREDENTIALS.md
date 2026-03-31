# Local environment credentials

> For local development only. NEVER use these credentials in production.

---

## Keycloak Admin

| URL | Username | Password |
|-----|----------|----------|
| http://localhost:8080 | `admin` | `admin` |

---

## Realm: altana-dev — Test users

| Username | Password | Roles | tenant_id | user_type | Notes |
|----------|----------|-------|-----------|-----------|-------|
| `admin-user` | `Test1234!` | `ROLE_ADMIN` | — | — | Full access |
| `analyst-user` | `Analyst123!` | `ROLE_ANALYST` | toyota | employee | Has phone_number for SMS OTP |
| `john.doe` | `Test1234!` | `ROLE_ANALYST` | toyota | employee | Federated from toyota-corp |
| `jane.consumer` | `Test1234!` | `ROLE_VIEWER` | toyota | customer | B2B2C end consumer |

---

## Realm: toyota-corp (simulated external IDP)

| Username | Password | Notes |
|----------|----------|-------|
| `john.doe` | `Test1234!` | Federated to altana-dev via OIDC broker. Password in **toyota-corp**, not in altana-dev. |
| `jane.consumer` | `Test1234!` | Federated to altana-dev via OIDC broker. Password in **toyota-corp**, not in altana-dev. |

---

## Clients (OAuth2)

| Client ID | Type | Secret | Usage |
|-----------|------|--------|-------|
| `altana-web` | Public (PKCE) | — | React app, user login |
| `supply-chain-backend` | Confidential | `CHANGE-ME-IN-PRODUCTION` | Spring Boot / FastAPI service account |
| `python-fastapi` | Confidential | see .env | FastAPI resource server |

---

## Local services

| Service | URL | Notes |
|---------|-----|-------|
| Keycloak | http://localhost:8080 | admin / admin |
| Spring Boot | http://localhost:8081 | Java Resource Server |
| FastAPI | http://localhost:8081 | Python Resource Server (shares port with Spring, never run simultaneously) |
| React | http://localhost:5173 | Frontend PKCE |
| MailHog UI | http://localhost:8025 | OTP test emails |
| PostgreSQL | localhost:5432 | keycloak / keycloak_dev_pass |

---

## Commands to start everything

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
