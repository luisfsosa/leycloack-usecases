# UC2 — Keycloak SPI: Event Listener

## What you learn here

How to react to Keycloak events (login, logout, errors, registrations) by
implementing the EventListener SPI. This is the standard way to add audit logs,
security alerts, and business automation (webhooks, notifications) to any
Keycloak deployment.

---

## How events work in Keycloak

```
User action (login, logout, register...)
    │
    └─► Keycloak processes it
             │
             └─► For each EventListenerProvider registered in the realm:
                      listener.onEvent(event)     ← user events
                      listener.onEvent(adminEvent) ← admin operations
```

Two types of events:

| Type | Interface method | Examples |
|------|-----------------|---------|
| **User event** | `onEvent(Event event)` | LOGIN, LOGOUT, LOGIN_ERROR, REGISTER, UPDATE_PASSWORD |
| **Admin event** | `onEvent(AdminEvent event, boolean includeRepresentation)` | Create/Delete user, client, realm, IDP config |

---

## Three behaviors in one listener

### 1. Security Audit Log

Every LOGIN, LOGOUT, LOGIN_ERROR, and REGISTER is written as structured JSON
to stdout. B2B logins include the source IDP.

```
[ALTANA-AUDIT] {"event":"LOGIN","severity":"INFO","timestamp":"2026-03-29T03:57:12Z",
                "realm":"altana-dev","client":"altana-web","ip":"192.168.1.10",
                "user":"john.doe","idp":"toyota-corp"}
```

In production, stdout is collected by the container runtime and forwarded to
log aggregators (ELK Stack, Datadog, Splunk, AWS CloudWatch, etc.).

### 2. Brute Force Detection

Counts consecutive LOGIN_ERROR events per user/IP. At threshold (3 failures)
emits a HIGH-severity alert.

```
[ALTANA-AUDIT] {"event":"LOGIN_ERROR","severity":"HIGH",...,"failures":"3",...}
[ALTANA-SECURITY] *** BRUTE FORCE DETECTED *** user=analyst-user failures=3 ip=...
```

The counter resets on successful LOGIN.

### 3. B2B First-Login Webhook

When a federated user appears for the first time (REGISTER event with
`identity_provider` in the details), fires a webhook to provision the user
in the tenant admin system.

```
[ALTANA-B2B-WEBHOOK] New federated user — provisioning notification
  → POST http://tenant-admin-service/webhooks/new-b2b-user
  → {"user":"john.doe","email":"john@toyota.com","idp":"toyota-corp",...}
```

> **INTERVIEW:** How do you detect the first login of a federated B2B user?
> → Listen for REGISTER events that include `identity_provider` in the event
>   details. Keycloak fires REGISTER the first time a brokered user authenticates
>   and a local shadow account is created. Subsequent logins only fire LOGIN.

---

## Project structure

```
keycloak-extension/src/main/
├── java/com/altana/keycloak/listener/
│   ├── AltanaSecurityListener.java         ← business logic
│   └── AltanaSecurityListenerFactory.java  ← singleton factory, holds shared state
└── resources/META-INF/services/
    └── org.keycloak.events.EventListenerProviderFactory
```

---

## AltanaSecurityListenerFactory.java — key concepts

```java
public class AltanaSecurityListenerFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "altana-security-listener";

    // Brute force counter lives on the FACTORY (singleton), not the listener.
    // The listener is created per-session (per request), so state would be lost.
    final ConcurrentHashMap<String, Integer> failureCount = new ConcurrentHashMap<>();

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new AltanaSecurityListener(session, failureCount);
    }
}
```

> **INTERVIEW:** Why is the brute-force counter on the factory and not the listener?
> → The factory is a singleton — one instance for the server's lifetime.
>   The listener is created per `KeycloakSession` (per request thread).
>   State on the listener would reset with every new request.
>   In a clustered deployment, replace `ConcurrentHashMap` with Infinispan
>   distributed cache so all nodes share the same counter.

---

## AltanaSecurityListener.java — key concepts

### onEvent(Event) — dispatching by event type

```java
@Override
public void onEvent(Event event) {
    switch (event.getType()) {
        case LOGIN       -> handleLogin(event);
        case LOGOUT      -> handleLogout(event);
        case LOGIN_ERROR -> handleLoginError(event);
        case REGISTER    -> handleRegister(event);
        default          -> {} // other events not audited
    }
}
```

### Brute force key

```java
private String bruteForceKey(Event event) {
    if (event.getUserId() != null) return event.getUserId();
    // For unknown users (wrong username), track by IP:username
    String username = detail(event, "username");
    String ip       = event.getIpAddress() != null ? event.getIpAddress() : "unknown";
    return ip + ":" + (username != null ? username : "unknown");
}
```

Two scenarios:
- User exists but wrong password → `event.getUserId()` is set → key = userId
- Username doesn't exist → userId is null → key = "ip:username"

### Detecting B2B first-login inside REGISTER

```java
private void handleRegister(Event event) {
    String idp = detail(event, "identity_provider");  // null for local registrations

    auditLog("REGISTER", "INFO", event, ...);

    if (idp != null) {
        // This is a first-time federated user — fire the onboarding webhook
        fireB2BFirstLoginWebhook(username, email, idp, event.getRealmId());
    }
}
```

The `identity_provider` key in the event details map is set by Keycloak's
identity brokering layer when a user arrives via an external IdP.

### onEvent(AdminEvent) — admin operations

```java
@Override
public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
    if (adminEvent.getOperationType() == OperationType.DELETE) {
        // Log: who deleted what resource and when
    }
}
```

`AdminEvent` fields:
- `getOperationType()`: CREATE, UPDATE, DELETE, ACTION
- `getResourceType()`: USER, CLIENT, REALM, IDENTITY_PROVIDER, ...
- `getResourcePath()`: the resource path, e.g. `users/abc-123`
- `getRepresentation()`: the JSON body of the operation (if `includeRepresentation=true`)

---

## Enabling the listener in Keycloak

### Step 1 — Verify that the JAR is loaded

```bash
docker logs altana-keycloak 2>&1 | grep -i "altana-security"
# Expected: KC-SERVICES0047: altana-security-listener (com.altana.keycloak.listener.AltanaSecurityListenerFactory) ...
```

If it does not appear: verify that the JAR is in `docker/keycloak/providers/` and recreate the container.

---

### Step 2 — Enable the listener in the realm (Admin UI)

1. **Realm Settings** → **Events** tab
2. Under **Event listeners**: click the field and select `altana-security-listener`
   - Keep `jboss-logging` as well (Keycloak's default listener)
   - The list should read: `jboss-logging`, `altana-security-listener`
3. Click **Save**

---

### Step 3 — Enable event persistence in the UI (optional but recommended in dev)

On the same **Events** tab:

**User events:**
1. Enable **Save events**: ON
2. Under **Saved types**: select the events to persist in the DB:
   - `LOGIN`, `LOGOUT`, `LOGIN_ERROR`, `REGISTER`, `UPDATE_PASSWORD`
3. **Expiration**: `1` Day (for dev; adjust per retention policy in prod)
4. Click **Save**

**Admin events:**
1. Enable **Save admin events**: ON
2. Enable **Include representation** (saves the full JSON body of the operation)
3. Click **Save**

> **Note:** "Save events" stores them in the Keycloak database and makes them visible in the UI.
> The `altana-security-listener` writes to stdout regardless of this toggle.
> They are two independent mechanisms — you can have one without the other.

---

### Step 4 — View saved events in the UI

**User events:**
1. Side menu → **Events**
2. **User events** tab
3. Filter by type, user, IP, or date
4. Each row shows: timestamp, type, user, client, IP, details

**Admin events:**
1. Side menu → **Events**
2. **Admin events** tab
3. Each row shows: timestamp, operation (CREATE/UPDATE/DELETE), resource type, who did it

---

### Via Admin API (scripted, CI/CD friendly)

```bash
curl -s -X PUT "$KC/admin/realms/altana-dev" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eventsListeners": ["jboss-logging", "altana-security-listener"],
    "eventsEnabled": true,
    "adminEventsEnabled": true,
    "adminEventsDetailsEnabled": true
  }'
```

---

## Verifying it works

```bash
# Watch the logs in real time
docker logs -f altana-keycloak 2>&1 | grep ALTANA

# Test audit log — successful login
curl -s -X POST "http://localhost:8080/realms/altana-dev/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=altana-web&username=analyst-user&password=Analyst123!"

# Test brute force — 3 wrong passwords trigger the alert
for i in 1 2 3; do
  curl -s -X POST "http://localhost:8080/realms/altana-dev/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=altana-web&username=analyst-user&password=WRONG" -o /dev/null
done
```

Expected output:
```
[ALTANA-AUDIT] {"event":"LOGIN","severity":"INFO",...,"user":"analyst-user","idp":"local"}
[ALTANA-AUDIT] {"event":"LOGIN_ERROR","severity":"WARN",...,"failures":"1",...}
[ALTANA-AUDIT] {"event":"LOGIN_ERROR","severity":"WARN",...,"failures":"2",...}
[ALTANA-AUDIT] {"event":"LOGIN_ERROR","severity":"HIGH",...,"failures":"3",...}
[ALTANA-SECURITY] *** BRUTE FORCE DETECTED *** user=analyst-user failures=3 ip=...
```

---

## Production considerations

### Log aggregation

In production, `System.out.printf` → Docker/Kubernetes captures stdout →
forwarded to your log aggregator:

```
docker logs → Fluent Bit → Elasticsearch → Kibana dashboard
```

A SIEM (Security Information and Event Management) can trigger alerts from
`severity=HIGH` lines in real time.

### Webhook reliability

The `fireB2BFirstLoginWebhook` method is called synchronously in the login
request thread. If the webhook target is slow or down, it blocks Keycloak.

**Production pattern:** write to an outbox table or a message queue (Kafka,
RabbitMQ) instead of calling the external service directly. A separate worker
processes the queue and handles retries.

### Distributed brute force counter

`ConcurrentHashMap` is node-local. In a Keycloak cluster:
- Node A counts 2 failures for user X
- Node B counts 2 more failures for user X
- Neither reaches the threshold

Replace with Infinispan distributed cache:
```java
Cache<String, Integer> cache = session
    .getProvider(InfinispanConnectionProvider.class)
    .getCache("brute-force-counters");
```

---

## Interview questions

**Q: What is the Keycloak Event Listener SPI?**
> An extension point that lets you react to user and admin events.
> Implement `EventListenerProvider` for the logic and `EventListenerProviderFactory`
> for the singleton. Register via META-INF/services and enable in Realm Settings → Events.

**Q: What is the difference between Event and AdminEvent?**
> `Event` covers user-facing actions: login, logout, register, password change.
> `AdminEvent` covers administrative operations via the Admin UI or Admin API:
> creating/deleting users, clients, realms, IDP config, etc.

**Q: How do you add an audit log to all Keycloak logins?**
> Implement `EventListenerProvider.onEvent(Event)`, check for EventType.LOGIN,
> and write a structured log line. The factory registers via ServiceLoader.
> Enable the listener in Realm Settings or via Admin API.

**Q: What are the limitations of storing state in a Keycloak SPI extension?**
> The `EventListenerProvider` is per-session (stateless). Shared state must go
> on the factory (singleton), but that's node-local. For clusters, use the
> Infinispan cache or an external store (Redis, DB). For async side effects
> (webhooks, emails), use a message queue to avoid blocking the login request.
