package com.altana.keycloak.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.IDToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TenantIdMapper.
 *
 * Testing a Protocol Mapper means verifying that:
 * 1. The business logic (read attribute → add claim) works correctly
 * 2. Edge cases (null attribute, blank attribute) do not add claims
 * 3. The mapper metadata is correct (getId, getDisplayType, etc.)
 *
 * We use a real IDToken (not mocked) to verify that the claim appears in
 * getOtherClaims(). This tests integration with OIDCAttributeMapperHelper.mapClaim()
 * without needing a real Keycloak server.
 *
 * INTERVIEW: "What does OIDCAttributeMapperHelper.mapClaim() do?"
 * → Reads the claim name from the mapper configuration (claim.name),
 *   converts the value to a JsonNode, and adds it to the token's otherClaims map.
 *   Supports dot-notation: "org.tenant" → nested claim {"org": {"tenant": "..."}}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantIdMapperTest {

    @Mock UserSessionModel      userSession;
    @Mock UserModel             user;
    @Mock KeycloakSession       keycloakSession;
    @Mock ClientSessionContext  clientSessionCtx;

    TenantIdMapper       mapper;
    IDToken              token;
    ProtocolMapperModel  mappingModel;

    @BeforeEach
    void setUp() {
        mapper = new TenantIdMapper();
        token  = new IDToken();

        // ProtocolMapperModel is a POJO — no mock needed
        // OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME = "claim.name"
        mappingModel = new ProtocolMapperModel();
        mappingModel.setConfig(Map.of(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "tenant_id"));

        when(userSession.getUser()).thenReturn(user);
    }

    // ── setClaim() ────────────────────────────────────────────────────────────

    @Test
    void setClaim_withTenantId_addsClaim() {
        when(user.getFirstAttribute("tenant_id")).thenReturn("toyota");

        mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

        // The claim must appear in the token's otherClaims map
        assertThat(token.getOtherClaims()).containsKey("tenant_id");
    }

    @Test
    void setClaim_tenantIdNull_doesNotAddClaim() {
        // When the user has no tenant_id attribute, we do NOT add the claim.
        // Design alternative: throw an exception to block token issuance
        // if tenant_id were mandatory.
        when(user.getFirstAttribute("tenant_id")).thenReturn(null);

        mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

        assertThat(token.getOtherClaims()).isEmpty();
    }

    @Test
    void setClaim_tenantIdBlank_doesNotAddClaim() {
        when(user.getFirstAttribute("tenant_id")).thenReturn("   ");

        mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

        assertThat(token.getOtherClaims()).isEmpty();
    }

    @Test
    void setClaim_customClaimName_usesMappingModelConfig() {
        // The claim name is defined by the admin in the UI (Token Claim Name).
        // The same mapper can be reused for different attributes.
        mappingModel.setConfig(Map.of(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "org_tenant"));
        when(user.getFirstAttribute("tenant_id")).thenReturn("bmw");

        mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

        assertThat(token.getOtherClaims()).containsKey("org_tenant");
        assertThat(token.getOtherClaims()).doesNotContainKey("tenant_id");
    }

    // ── Mapper metadata ───────────────────────────────────────────────────────

    @Test
    void metadata_providerId() {
        assertThat(mapper.getId()).isEqualTo("altana-tenant-id-mapper");
    }

    @Test
    void metadata_displayType() {
        assertThat(mapper.getDisplayType()).isEqualTo("Altana Tenant ID Mapper");
    }

    @Test
    void metadata_displayCategory() {
        // TOKEN_MAPPER_CATEGORY = "Token mapper" — groups mappers in the Keycloak UI
        assertThat(mapper.getDisplayCategory()).isEqualTo("Token mapper");
    }

    @Test
    void metadata_configPropertiesIncludeClaimName() {
        // The mapper must declare at least the "Token Claim Name" field
        // so the admin can configure it in the UI
        assertThat(mapper.getConfigProperties())
            .isNotEmpty()
            .anyMatch(p -> p.getName().equals(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME));
    }
}
