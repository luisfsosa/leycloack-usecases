package com.altana.keycloak.integration;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.*;

import java.io.File;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Altana Keycloak Extension.
 *
 * These tests verify that:
 * 1. The JAR loads correctly in Keycloak (ServiceLoader registers all providers)
 * 2. All providers appear in the Keycloak startup logs
 * 3. The TenantIdMapper actually adds the tenant_id claim to a real JWT
 *
 * We use dasniko/testcontainers-keycloak — the community-standard library
 * for integration tests with a real Keycloak in Docker.
 *
 * Prerequisite: the JAR must be built before running these tests.
 * The 'integrationTest' Gradle task has 'dependsOn jar' for this reason.
 *
 * How to run: ./gradlew integrationTest
 * (Requires Docker — Rancher Desktop, Docker Desktop, etc.)
 *
 * INTERVIEW: "How do you verify that an SPI loads in Keycloak?"
 * → Testcontainers spins up a real Keycloak in Docker with the JAR mounted.
 *   Keycloak prints "KC-SERVICES0047: <id> ... is registered" in the logs for
 *   each provider discovered by ServiceLoader. We assert on those messages
 *   and optionally make a real token request to verify the mapper works end-to-end.
 */
@Tag("integration")
class KeycloakExtensionIT {

    /*
     * KeycloakContainer: Testcontainers wrapper for Keycloak.
     * withProviderLibsFrom() mounts the JAR into /opt/keycloak/providers/
     * before Keycloak starts — ServiceLoader discovers it on startup.
     *
     * static + @BeforeAll: a single container shared by all tests in this class.
     * Keycloak takes ~15-30s to start — avoid restarting it for every test.
     */
    static KeycloakContainer keycloak;

    @BeforeAll
    static void startKeycloak() {
        keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.5.6")
                .withProviderLibsFrom(List.of(
                        new File("build/libs/altana-keycloak-extension-1.0.0.jar")
                ));
        keycloak.start();
    }

    // ── Provider registration via startup logs ─────────────────────────────────

    /**
     * Verifies that the Event Listener registered by inspecting startup logs.
     *
     * Keycloak prints during startup:
     *   "KC-SERVICES0047: altana-security-listener (...) is registered."
     * If this message is absent, the JAR did not load or the META-INF/services
     * file does not point to the correct class.
     *
     * Testcontainers captures all container output — getLogs() returns all
     * stdout+stderr from startup to the point of the call.
     */
    @Test
    void eventListener_appearsInStartupLogs() {
        String logs = keycloak.getLogs();
        assertThat(logs)
                .as("altana-security-listener should be registered by ServiceLoader")
                .contains("altana-security-listener");
    }

    /**
     * Verifies that the Authenticator registered in the startup logs.
     */
    @Test
    void authenticator_appearsInStartupLogs() {
        String logs = keycloak.getLogs();
        assertThat(logs)
                .as("altana-otp-authenticator should be registered by ServiceLoader")
                .contains("altana-otp-authenticator");
    }

    /**
     * Verifies that the Protocol Mapper registered in the startup logs.
     */
    @Test
    void protocolMapper_appearsInStartupLogs() {
        String logs = keycloak.getLogs();
        assertThat(logs)
                .as("altana-tenant-id-mapper should be registered by ServiceLoader")
                .contains("altana-tenant-id-mapper");
    }

    // ── TenantIdMapper end-to-end ──────────────────────────────────────────────

    /**
     * End-to-end test for TenantIdMapper:
     * 1. Creates an ephemeral test realm
     * 2. Creates a user with attribute tenant_id=toyota
     * 3. Creates a public client with the mapper configured
     * 4. Requests a token via Resource Owner Password (test-only grant)
     * 5. Decodes the JWT and verifies tenant_id=toyota is present in the payload
     *
     * This test verifies the real integration — not just that the Java code compiles,
     * but that Keycloak actually invokes setClaim() when issuing the token and the
     * claim appears in the final JWT.
     *
     * INTERVIEW: "Why use Resource Owner Password in the integration test?"
     * → It is the only grant that works without a browser in automated tests.
     *   In production it is disabled. In integration tests it is acceptable because
     *   Keycloak is ephemeral (Testcontainers) and is destroyed when the test ends.
     */
    @Test
    void tenantIdMapper_addsClaimToJwt() {
        final String REALM    = "it-realm";
        final String CLIENT   = "it-client";
        final String USERNAME = "supplier@test.com";
        final String PASSWORD = "Test1234!";
        final String TENANT   = "toyota";

        try (Keycloak adminClient = adminClient()) {

            // ── 1. Create realm ────────────────────────────────────────────────
            RealmRepresentation realm = new RealmRepresentation();
            realm.setRealm(REALM);
            realm.setEnabled(true);
            adminClient.realms().create(realm);

            // ── 2. Create user with tenant_id attribute ────────────────────────
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(PASSWORD);
            cred.setTemporary(false);

            UserRepresentation user = new UserRepresentation();
            user.setUsername(USERNAME);
            user.setEmail(USERNAME);
            user.setEnabled(true);
            user.setAttributes(Map.of("tenant_id", List.of(TENANT)));
            user.setCredentials(List.of(cred));
            adminClient.realm(REALM).users().create(user);

            // ── 3. Create public client with TenantIdMapper ────────────────────
            /*
             * ProtocolMapperRepresentation defines the mapper Keycloak will run
             * when issuing the token.
             * - protocolMapper = PROVIDER_ID of our TenantIdMapper
             * - claim.name     = name of the claim in the JWT
             */
            ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
            mapper.setName("tenant-id");
            mapper.setProtocol("openid-connect");
            mapper.setProtocolMapper("altana-tenant-id-mapper");
            mapper.setConfig(Map.of(
                    "claim.name",          "tenant_id",
                    "access.token.claim",  "true",
                    "id.token.claim",      "true"
            ));

            ClientRepresentation client = new ClientRepresentation();
            client.setClientId(CLIENT);
            client.setPublicClient(true);
            client.setDirectAccessGrantsEnabled(true);  // for tests only
            client.setEnabled(true);
            client.setProtocolMappers(List.of(mapper));
            adminClient.realm(REALM).clients().create(client);

            // ── 4. Obtain access token via Resource Owner Password ─────────────
            try (Keycloak userClient = KeycloakBuilder.builder()
                    .serverUrl(keycloak.getAuthServerUrl())
                    .realm(REALM)
                    .clientId(CLIENT)
                    .username(USERNAME)
                    .password(PASSWORD)
                    .grantType("password")
                    .build()) {

                String accessToken = userClient.tokenManager().getAccessTokenString();

                // ── 5. Decode JWT payload (no signature verification) ──────────
                /*
                 * JWT = header.payload.signature — all three parts are Base64url encoded.
                 * In tests we decode only for inspection.
                 * The signature was already verified by Keycloak when it issued the token (RS256).
                 */
                String payloadB64 = accessToken.split("\\.")[1]
                        .replace('-', '+')
                        .replace('_', '/');
                // Add padding to reach a multiple of 4
                while (payloadB64.length() % 4 != 0) payloadB64 += "=";
                String payloadJson = new String(Base64.getDecoder().decode(payloadB64));

                assertThat(payloadJson)
                        .as("JWT payload should contain tenant_id claim added by TenantIdMapper")
                        .contains("\"tenant_id\":\"toyota\"");
            }

            // Cleanup: remove the ephemeral realm
            adminClient.realm(REALM).remove();
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Admin client authenticated against the master realm of the ephemeral container.
     *
     * KeycloakContainer exposes credentials via getAdminUsername() / getAdminPassword().
     * Always connects to the "master" realm — that is where the Keycloak admin account
     * lives (not to be confused with application realms).
     */
    private Keycloak adminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(keycloak.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .username(keycloak.getAdminUsername())
                .password(keycloak.getAdminPassword())
                .grantType("password")
                .build();
    }
}
