package com.altana.keycloak.listener;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * CONCEPT: EventListenerProviderFactory
 *
 * The Factory is the singleton registered via ServiceLoader.
 * It is created once when Keycloak starts and lives until shutdown.
 *
 * The Factory holds shared state (the brute-force counter) that must
 * persist across multiple requests/sessions. The Listener itself is
 * created per-session (per request), so it cannot hold shared state.
 *
 * Pattern:
 *   Factory (singleton, shared state)  →  create()  →  Listener (per session)
 *
 * INTERVIEW: "Why does the factory hold the brute-force counter and not the listener?"
 * → The factory is a singleton — one instance for the lifetime of the server.
 *   The listener is created per KeycloakSession (per request). If the counter
 *   lived on the listener, it would reset on every request.
 *   In a clustered deployment you would use Infinispan distributed cache instead
 *   of a local ConcurrentHashMap so all nodes share the same counter.
 */
public class AltanaSecurityListenerFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "altana-security-listener";

    /**
     * Brute force counter: key → consecutive failure count.
     * Key: userId if known, or "ip:username" for unauthenticated attempts.
     *
     * ConcurrentHashMap because multiple request threads can hit this
     * simultaneously — thread safety is required.
     *
     * NOTE (production): replace with Infinispan/distributed cache for HA clusters.
     */
    final ConcurrentHashMap<String, Integer> failureCount = new ConcurrentHashMap<>();

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new AltanaSecurityListener(session, failureCount);
    }

    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}

    @Override
    public String getId() { return PROVIDER_ID; }
}
