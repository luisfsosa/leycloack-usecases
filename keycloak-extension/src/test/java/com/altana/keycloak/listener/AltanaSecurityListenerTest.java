package com.altana.keycloak.listener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for AltanaSecurityListener.
 *
 * Testing strategy for Event Listeners:
 * - Event and AdminEvent are POJOs with setters → no mocking needed
 * - We mock KeycloakSession to control username resolution
 * - We capture System.out to verify the audit log format
 * - We verify the ConcurrentHashMap on the factory for brute-force logic
 *
 * INTERVIEW: "How do you test components that write to System.out?"
 * → System.setOut() redirects stdout to a ByteArrayOutputStream that we can
 *   inspect in assertions. Always restore in @AfterEach to avoid side effects
 *   on other tests.
 */
@ExtendWith(MockitoExtension.class)
class AltanaSecurityListenerTest {

    @Mock KeycloakSession session;
    @Mock RealmProvider   realmProvider;
    @Mock UserProvider    userProvider;
    @Mock RealmModel      realm;
    @Mock UserModel       user;

    ConcurrentHashMap<String, Integer> failureCount;
    AltanaSecurityListener             listener;

    // For capturing System.out
    ByteArrayOutputStream outputCapture;
    PrintStream           originalOut;

    @BeforeEach
    void setUp() {
        failureCount = new ConcurrentHashMap<>();
        listener     = new AltanaSecurityListener(session, failureCount);

        // Capture stdout to verify audit log output
        outputCapture = new ByteArrayOutputStream();
        originalOut   = System.out;
        System.setOut(new PrintStream(outputCapture));

        // Stubs for username resolution inside the listener
        lenient().when(session.realms()).thenReturn(realmProvider);
        lenient().when(session.users()).thenReturn(userProvider);
        lenient().when(realmProvider.getRealm("altana-dev")).thenReturn(realm);
        lenient().when(userProvider.getUserById(realm, "user-123")).thenReturn(user);
        lenient().when(user.getUsername()).thenReturn("analyst-user");
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);  // always restore — affects other tests if skipped
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────

    @Test
    void login_resetsFailureCounterAndLogsInfo() {
        // Pre-condition: there were 2 previous failed attempts
        failureCount.put("user-123", 2);

        listener.onEvent(loginEvent("user-123", "altana-web", "10.0.0.1"));

        // Counter must be reset on successful login
        assertThat(failureCount).doesNotContainKey("user-123");

        // Audit log must be written to stdout
        String output = outputCapture.toString();
        assertThat(output).contains("[ALTANA-AUDIT]");
        assertThat(output).contains("\"event\":\"LOGIN\"");
        assertThat(output).contains("\"severity\":\"INFO\"");
        assertThat(output).contains("\"idp\":\"local\"");
    }

    @Test
    void login_b2bEvent_includesIdpInAuditLog() {
        Event event = loginEvent("user-123", "altana-web", "10.0.0.1");
        event.setDetails(Map.of("identity_provider", "toyota-corp"));

        listener.onEvent(event);

        assertThat(outputCapture.toString())
            .contains("\"idp\":\"toyota-corp\"");
    }

    // ── LOGIN_ERROR / BRUTE FORCE ─────────────────────────────────────────────

    @Test
    void loginError_firstFailure_logsWarnAndIncrementsCounter() {
        listener.onEvent(loginErrorEvent("user-123", "analyst-user", "10.0.0.1"));

        assertThat(failureCount.get("user-123")).isEqualTo(1);

        String output = outputCapture.toString();
        assertThat(output).contains("\"severity\":\"WARN\"");
        assertThat(output).contains("\"failures\":\"1\"");
        // Brute force alert must NOT appear with only 1 failure
        assertThat(output).doesNotContain("[ALTANA-SECURITY]");
    }

    @Test
    void loginError_atThreshold_triggersBruteForceAlert() {
        // Threshold = 3. Three consecutive failures → HIGH severity alert.
        Event errorEvent = loginErrorEvent("user-123", "analyst-user", "10.0.0.1");

        listener.onEvent(errorEvent);
        listener.onEvent(errorEvent);
        listener.onEvent(errorEvent);

        assertThat(failureCount.get("user-123")).isEqualTo(3);

        String output = outputCapture.toString();
        assertThat(output).contains("\"severity\":\"HIGH\"");
        assertThat(output).contains("[ALTANA-SECURITY]");
        assertThat(output).contains("BRUTE FORCE DETECTED");
        assertThat(output).contains("failures=3");
    }

    @Test
    void loginAfterErrors_resetsCounter() {
        // Simulate 3 previous errors
        failureCount.put("user-123", 3);

        listener.onEvent(loginEvent("user-123", "altana-web", "10.0.0.1"));

        assertThat(failureCount).doesNotContainKey("user-123");
    }

    @Test
    void loginError_unknownUser_trackedByIpAndUsername() {
        // When userId is null (user does not exist), the key is "ip:username"
        Event event = loginErrorEvent(null, "nonexistent@evil.com", "1.2.3.4");

        listener.onEvent(event);

        // Counter must use the ip:username key, not userId
        assertThat(failureCount).containsKey("1.2.3.4:nonexistent@evil.com");
    }

    // ── REGISTER ─────────────────────────────────────────────────────────────

    @Test
    void register_localUser_logsAuditWithoutWebhook() {
        Event event = registerEvent("user-456", "supplier@vn-parts.com", null);

        listener.onEvent(event);

        String output = outputCapture.toString();
        assertThat(output).contains("\"event\":\"REGISTER\"");
        assertThat(output).contains("\"severity\":\"INFO\"");
        assertThat(output).doesNotContain("[ALTANA-B2B-WEBHOOK]");
    }

    @Test
    void register_federatedUser_logsAuditAndFiresWebhook() {
        // When identity_provider is in the event details, this is a federated
        // (B2B) user. The listener must fire the onboarding webhook to provision
        // the user in the tenant admin system.
        Event event = registerEvent("user-789", "john@toyota.com", "toyota-corp");

        listener.onEvent(event);

        String output = outputCapture.toString();
        assertThat(output).contains("\"event\":\"REGISTER\"");
        assertThat(output).contains("[ALTANA-B2B-WEBHOOK]");
        assertThat(output).contains("toyota-corp");
        assertThat(output).contains("john@toyota.com");
    }

    // ── ADMIN EVENT ───────────────────────────────────────────────────────────

    @Test
    void adminDeleteEvent_logsWarning() {
        AdminEvent event = adminDeleteEvent(ResourceType.USER, "users/abc-123");

        listener.onEvent(event, false);

        String output = outputCapture.toString();
        assertThat(output).contains("\"event\":\"ADMIN_DELETE\"");
        assertThat(output).contains("\"severity\":\"WARN\"");
        assertThat(output).contains("users/abc-123");
    }

    @Test
    void adminCreateEvent_notLogged() {
        // Only DELETE is logged — CREATE does not produce an audit line in this implementation
        AdminEvent event = new AdminEvent();
        event.setOperationType(OperationType.CREATE);
        event.setResourceType(ResourceType.USER);
        event.setRealmId("altana-dev");
        event.setTime(System.currentTimeMillis());

        listener.onEvent(event, false);

        assertThat(outputCapture.toString()).doesNotContain("[ALTANA-AUDIT]");
    }

    // ── Helpers for building test events ─────────────────────────────────────

    private Event loginEvent(String userId, String clientId, String ip) {
        Event e = new Event();
        e.setType(EventType.LOGIN);
        e.setUserId(userId);
        e.setClientId(clientId);
        e.setIpAddress(ip);
        e.setRealmId("altana-dev");
        e.setTime(System.currentTimeMillis());
        return e;
    }

    private Event loginErrorEvent(String userId, String username, String ip) {
        Map<String, String> details = new HashMap<>();
        if (username != null) details.put("username", username);

        Event e = new Event();
        e.setType(EventType.LOGIN_ERROR);
        e.setUserId(userId);
        e.setError("invalid_user_credentials");
        e.setIpAddress(ip);
        e.setRealmId("altana-dev");
        e.setTime(System.currentTimeMillis());
        e.setDetails(details);
        return e;
    }

    private Event registerEvent(String userId, String email, String idp) {
        Map<String, String> details = new HashMap<>();
        if (email != null) details.put("email", email);
        if (idp   != null) details.put("identity_provider", idp);

        Event e = new Event();
        e.setType(EventType.REGISTER);
        e.setUserId(userId);
        e.setRealmId("altana-dev");
        e.setTime(System.currentTimeMillis());
        e.setDetails(details);
        return e;
    }

    private AdminEvent adminDeleteEvent(ResourceType resourceType, String resourcePath) {
        AdminEvent e = new AdminEvent();
        e.setOperationType(OperationType.DELETE);
        e.setResourceType(resourceType);
        e.setResourcePath(resourcePath);
        e.setRealmId("altana-dev");
        e.setTime(System.currentTimeMillis());
        return e;
    }
}
