package com.altana.keycloak.listener;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UC2 — Keycloak SPI: Event Listener
 *
 * Three behaviors in a single listener:
 *
 *  1. SECURITY AUDIT LOG
 *     Every LOGIN, LOGOUT, LOGIN_ERROR, REGISTER is written to stdout as
 *     structured JSON. Ready for log aggregators (ELK, Datadog, Splunk).
 *     B2B logins include the source IDP in the audit line.
 *
 *  2. BRUTE FORCE DETECTION
 *     Counts consecutive LOGIN_ERROR events per user/IP.
 *     At the threshold (3 failures) emits a HIGH-severity alert.
 *     The counter resets on successful LOGIN.
 *
 *  3. B2B FIRST-LOGIN WEBHOOK
 *     REGISTER events that include an identity_provider detail are
 *     first-time federated logins (a Toyota user appearing in Altana
 *     for the first time). A simulated webhook fires to the tenant
 *     admin service so it can provision the user, send a welcome email, etc.
 *
 * CONCEPT: onEvent(Event) vs onEvent(AdminEvent)
 *   Event      → user-facing actions: login, logout, register, password change
 *   AdminEvent → admin operations: create/update/delete users, clients, realms
 *
 * INTERVIEW: "How do you add an audit trail to Keycloak?"
 * → Implement EventListenerProvider. onEvent(Event) covers user actions,
 *   onEvent(AdminEvent) covers admin operations. Register the factory via
 *   META-INF/services, then enable it in Realm Settings → Events → Event listeners.
 */
public class AltanaSecurityListener implements EventListenerProvider {

    private static final int BRUTE_FORCE_THRESHOLD = 3;

    private final KeycloakSession session;
    private final ConcurrentHashMap<String, Integer> failureCount;

    AltanaSecurityListener(KeycloakSession session, ConcurrentHashMap<String, Integer> failureCount) {
        this.session      = session;
        this.failureCount = failureCount;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User events
    // ─────────────────────────────────────────────────────────────────────────

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

    /**
     * LOGIN — successful authentication.
     * Resets the brute-force counter for this user.
     * Detects B2B logins via the identity_provider detail.
     */
    private void handleLogin(Event event) {
        failureCount.remove(bruteForceKey(event));  // reset counter on success

        String idp = detail(event, "identity_provider");
        auditLog("LOGIN", "INFO", event,
                "user", resolveUsername(event),
                "idp",  idp != null ? idp : "local");
    }

    /** LOGOUT — session terminated by the user. */
    private void handleLogout(Event event) {
        auditLog("LOGOUT", "INFO", event,
                "user", resolveUsername(event));
    }

    /**
     * LOGIN_ERROR — failed authentication attempt.
     *
     * Increments the counter per user/IP and emits a HIGH-severity alert
     * when the threshold is reached.
     *
     * INTERVIEW: "Where would you store the counter in a clustered Keycloak?"
     * → In the Infinispan distributed cache that Keycloak already uses internally.
     *   You get a reference via session.getProvider(InfinispanConnectionProvider.class).
     *   The local ConcurrentHashMap only works in a single-node deployment.
     */
    private void handleLoginError(Event event) {
        String username = detail(event, "username") != null
                ? detail(event, "username")
                : "unknown";
        String key      = bruteForceKey(event);
        int    failures = failureCount.merge(key, 1, Integer::sum);
        String severity = failures >= BRUTE_FORCE_THRESHOLD ? "HIGH" : "WARN";

        auditLog("LOGIN_ERROR", severity, event,
                "attempted_user", username,
                "failures",       String.valueOf(failures),
                "error",          event.getError() != null ? event.getError() : "unknown");

        if (failures >= BRUTE_FORCE_THRESHOLD) {
            System.out.printf(
                "[ALTANA-SECURITY] *** BRUTE FORCE DETECTED *** " +
                "user=%s failures=%d ip=%s%n",
                username, failures, event.getIpAddress());
        }
    }

    /**
     * REGISTER — new user account created.
     *
     * When identity_provider is present in the event details, this user
     * arrived via Identity Brokering for the first time (e.g. john.doe
     * from toyota-corp appearing in altana-dev for the first time).
     *
     * We fire a simulated webhook to the tenant admin service.
     * In production: use an HttpClient to POST to the actual endpoint.
     *
     * INTERVIEW: "How do you detect the first login of a federated B2B user?"
     * → Listen for REGISTER events that include identity_provider in the
     *   event details. Keycloak fires REGISTER the first time a brokered
     *   user authenticates and a local shadow account is created.
     *   Subsequent logins from the same user only fire LOGIN, not REGISTER.
     */
    private void handleRegister(Event event) {
        String username = resolveUsername(event);
        String email    = detail(event, "email");
        String idp      = detail(event, "identity_provider");

        auditLog("REGISTER", "INFO", event,
                "user",  username,
                "email", email != null ? email : "unknown",
                "idp",   idp   != null ? idp   : "local");

        if (idp != null) {
            fireB2BFirstLoginWebhook(username, email, idp, event.getRealmId());
        }
    }

    /**
     * Simulates the B2B first-login webhook.
     *
     * In production this would be an HTTP POST:
     *
     *   HttpClient.newHttpClient().send(
     *       HttpRequest.newBuilder()
     *           .uri(URI.create("http://tenant-admin-service/webhooks/new-b2b-user"))
     *           .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
     *           .header("Content-Type", "application/json")
     *           .build(),
     *       HttpResponse.BodyHandlers.ofString()
     *   );
     *
     * Credentials for the webhook endpoint should come from getConfigProperties()
     * on the factory, so the admin configures them in the Keycloak Admin UI.
     */
    private void fireB2BFirstLoginWebhook(String username, String email,
                                           String idp,      String realm) {
        System.out.printf(
            "[ALTANA-B2B-WEBHOOK] New federated user — provisioning notification%n" +
            "  → POST http://tenant-admin-service/webhooks/new-b2b-user%n" +
            "  → {\"user\":\"%s\",\"email\":\"%s\",\"idp\":\"%s\"," +
                "\"realm\":\"%s\",\"timestamp\":\"%s\"}%n",
            username,
            email != null ? email : "unknown",
            idp,
            realm,
            Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Admin events (Admin UI / Admin API operations)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AdminEvent — fired when an admin performs an operation via UI or API.
     *
     * We log DELETE operations for compliance (who deleted what and when).
     * You could extend this to also log CREATE (new client added) or
     * UPDATE (realm settings changed), etc.
     *
     * INTERVIEW: "What is the difference between Event and AdminEvent in Keycloak?"
     * → Event is fired by user-facing actions (login, register, password change).
     *   AdminEvent is fired by administrative operations (create/update/delete
     *   users, clients, realms, IDP config, etc.). They have different payloads:
     *   AdminEvent includes resourceType (USER, CLIENT, REALM...) and resourcePath.
     */
    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        if (adminEvent.getOperationType() == OperationType.DELETE) {
            System.out.printf(
                "[ALTANA-AUDIT] {\"event\":\"ADMIN_DELETE\",\"severity\":\"WARN\"," +
                "\"realm\":\"%s\",\"resource_type\":\"%s\"," +
                "\"resource_path\":\"%s\",\"timestamp\":\"%s\"}%n",
                adminEvent.getRealmId(),
                adminEvent.getResourceType()  != null ? adminEvent.getResourceType()  : "unknown",
                adminEvent.getResourcePath()  != null ? adminEvent.getResourcePath()  : "unknown",
                Instant.ofEpochMilli(adminEvent.getTime()));
        }
    }

    @Override
    public void close() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a structured JSON audit line to stdout.
     * kvPairs: alternating key, value pairs — null values are omitted.
     *
     * Example output:
     *   [ALTANA-AUDIT] {"event":"LOGIN","severity":"INFO","timestamp":"...","realm":"altana-dev",
     *                   "client":"altana-web","ip":"127.0.0.1","user":"analyst-user","idp":"local"}
     */
    private void auditLog(String eventName, String severity, Event event,
                          String... kvPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "[ALTANA-AUDIT] {\"event\":\"%s\",\"severity\":\"%s\"," +
            "\"timestamp\":\"%s\",\"realm\":\"%s\"," +
            "\"client\":\"%s\",\"ip\":\"%s\"",
            eventName,
            severity,
            Instant.ofEpochMilli(event.getTime()),
            event.getRealmId(),
            event.getClientId()  != null ? event.getClientId()  : "none",
            event.getIpAddress() != null ? event.getIpAddress() : "unknown"));

        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (kvPairs[i + 1] != null) {
                sb.append(String.format(",\"%s\":\"%s\"", kvPairs[i], kvPairs[i + 1]));
            }
        }

        sb.append("}");
        System.out.println(sb);
    }

    /**
     * Resolves the userId to a human-readable username.
     * Falls back to the raw userId if the user cannot be loaded.
     */
    private String resolveUsername(Event event) {
        if (event.getUserId() == null) return "anonymous";
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserModel  user  = session.users().getUserById(realm, event.getUserId());
            return user != null ? user.getUsername() : event.getUserId();
        } catch (Exception e) {
            return event.getUserId();
        }
    }

    /** Safe getter for the event details map. */
    private String detail(Event event, String key) {
        Map<String, String> details = event.getDetails();
        return details != null ? details.get(key) : null;
    }

    /**
     * Key for the brute-force counter.
     * If we have a userId (user exists and was identified), use it.
     * Otherwise use "ip:username" to track unauthenticated attempts.
     */
    private String bruteForceKey(Event event) {
        if (event.getUserId() != null) return event.getUserId();
        String username = detail(event, "username");
        String ip       = event.getIpAddress() != null ? event.getIpAddress() : "unknown";
        return ip + ":" + (username != null ? username : "unknown");
    }
}
