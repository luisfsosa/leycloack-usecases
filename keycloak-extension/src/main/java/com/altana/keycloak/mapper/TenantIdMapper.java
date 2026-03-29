package com.altana.keycloak.mapper;

/*
 * ============================================================
 * CONCEPT: Keycloak Protocol Mapper SPI
 * ============================================================
 *
 * A Protocol Mapper is Java code that Keycloak executes at the
 * moment of ISSUING a JWT token.
 *
 * Full flow:
 *   User authenticates → Keycloak builds the JWT
 *   → For each mapper configured on the client:
 *       mapper.setClaim(token, ...)   ← our code runs here
 *   → Signs the JWT and returns it
 *
 * What does this offer over a UI-configured mapper?
 *   UI mapper (user-attribute):  reads a user attribute → claim
 *   SPI mapper:                  custom logic — transformations,
 *                                external lookups, enrichment, etc.
 *
 * INTERVIEW: What is the Keycloak SPI?
 * → Service Provider Interface — Keycloak's extension mechanism.
 *   You define a Java class implementing a Keycloak interface,
 *   package it as a JAR, and place it in /opt/keycloak/providers/.
 *   Keycloak discovers it automatically via java.util.ServiceLoader.
 *
 * ============================================================
 * CLASS HIERARCHY
 * ============================================================
 *
 * AbstractOIDCProtocolMapper
 *   └── TenantIdMapper
 *         ├── implements OIDCAccessTokenMapper  → adds claim to access_token
 *         └── implements OIDCIDTokenMapper      → adds claim to id_token
 *
 * Note Keycloak 26: OIDCUserInfoMapper was removed. Inclusion in /userinfo
 * is controlled by the "Add to userinfo" checkbox that OIDCAttributeMapperHelper
 * adds automatically via addIncludeInTokensConfig().
 *
 * ============================================================
 */

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.List;

public class TenantIdMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    /*
     * PROVIDER_ID: unique identifier of this mapper in Keycloak.
     * Appears in the type list when adding a mapper in the Admin UI.
     * Must be unique across the entire server — use a project-specific prefix.
     */
    public static final String PROVIDER_ID = "altana-tenant-id-mapper";

    /*
     * configProperties: list of configurable fields shown in the Keycloak Admin UI
     * when the admin configures this mapper on a client.
     *
     * OIDCAttributeMapperHelper adds the standard fields:
     *   - "Token Claim Name" → name of the claim in the JWT (e.g. "tenant_id")
     *   - "Add to access token" → checkbox
     *   - "Add to ID token" → checkbox
     *   - "Add to userinfo" → checkbox
     */
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, TenantIdMapper.class);
    }

    // ─── Mapper metadata (shown in the Keycloak Admin UI) ────────────────────

    @Override
    public String getId() {
        return PROVIDER_ID;  // unique internal identifier
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;  // constant = "Token mapper"
    }

    @Override
    public String getDisplayType() {
        return "Altana Tenant ID Mapper";  // label shown in the UI
    }

    @Override
    public String getHelpText() {
        return "Reads the 'tenant_id' attribute from the user and adds it to the JWT. " +
               "Set 'Token Claim Name' to 'tenant_id'.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    // ─── Core logic: building the claim ──────────────────────────────────────

    /*
     * setClaim() is called by Keycloak once per token issued.
     *
     * Key parameters:
     *   token          → the JWT being built (we can modify it)
     *   mappingModel   → the mapper configuration (claim name, etc.)
     *   userSession    → the user session → access to User, attributes, etc.
     *   keycloakSession→ access to all Keycloak services (DB, etc.)
     *   clientSessionCtx → context of the client that requested the token
     *
     * INTERVIEW: How do you access user data inside a mapper?
     * → userSession.getUser() returns the UserModel.
     *   getFirstAttribute("name") reads custom user attributes.
     *   getEmail(), getUsername(), etc. for standard fields.
     */
    @Override
    protected void setClaim(IDToken token,
                            ProtocolMapperModel mappingModel,
                            UserSessionModel userSession,
                            KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        // Read the "tenant_id" attribute from the Keycloak user.
        // This attribute is configured in the UI: Users → {user} → Attributes
        String tenantId = userSession.getUser().getFirstAttribute("tenant_id");

        if (tenantId == null || tenantId.isBlank()) {
            // No tenant_id → do not add the claim to the token.
            // If it were mandatory, you could throw an exception here
            // to block token issuance entirely.
            return;
        }

        /*
         * OIDCAttributeMapperHelper.mapClaim() does the heavy lifting:
         * - Reads the claim name from mappingModel ("Token Claim Name")
         * - Supports dot notation: "org.tenant" → nested claim
         * - Adds the value to the IDToken (later serialized to JWT)
         *
         * This honours whatever configuration the admin made in the UI.
         */
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, tenantId);
    }
}
