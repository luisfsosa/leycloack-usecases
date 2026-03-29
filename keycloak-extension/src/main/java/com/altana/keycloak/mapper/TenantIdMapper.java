package com.altana.keycloak.mapper;

/*
 * ============================================================
 * CONCEPTO: Keycloak Protocol Mapper SPI
 * ============================================================
 *
 * Un Protocol Mapper es código Java que Keycloak ejecuta
 * en el momento de EMITIR un token JWT.
 *
 * Flujo completo:
 *   Usuario autentica → Keycloak arma el JWT
 *   → Para cada mapper configurado en el client:
 *       mapper.setClaim(token, ...)   ← aquí entra nuestro código
 *   → Firma el JWT y lo devuelve
 *
 * ¿Para qué sirve vs mapper via UI?
 *   UI mapper (user-attribute):  lee un atributo de usuario → claim
 *   SPI mapper:                  lógica custom — transformaciones,
 *                                lookups externos, enriquecimiento, etc.
 *
 * ENTREVISTA: ¿Qué es el SPI de Keycloak?
 * → Service Provider Interface — mecanismo de extensión de Keycloak.
 *   Defines una clase Java que implementa una interfaz de Keycloak,
 *   la empaquetas en un JAR y la colocas en /opt/keycloak/providers/.
 *   Keycloak la descubre automáticamente via java.util.ServiceLoader.
 *
 * ============================================================
 * JERARQUÍA DE CLASES
 * ============================================================
 *
 * AbstractOIDCProtocolMapper
 *   └── TenantIdMapper
 *         ├── implements OIDCAccessTokenMapper  → agrega claim al access_token
 *         └── implements OIDCIDTokenMapper      → agrega claim al id_token
 *
 * Nota Keycloak 26: OIDCUserInfoMapper fue eliminado. La inclusión en /userinfo
 * se controla via el checkbox "Add to userinfo" que OIDCAttributeMapperHelper
 * agrega automáticamente en addIncludeInTokensConfig().
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
     * PROVIDER_ID: identificador único del mapper en Keycloak.
     * Aparece en la lista de tipos al agregar un mapper en la UI.
     * Debe ser único en todo el servidor — usar prefijo del proyecto.
     */
    public static final String PROVIDER_ID = "altana-tenant-id-mapper";

    /*
     * configProperties: lista de campos configurables que aparecen
     * en la UI de Keycloak cuando configuras este mapper en un client.
     *
     * OIDCAttributeMapperHelper agrega los campos estándar:
     *   - "Token Claim Name" → nombre del claim en el JWT (ej: "tenant_id")
     *   - "Add to access token" → checkbox
     *   - "Add to ID token" → checkbox
     *   - "Add to userinfo" → checkbox
     */
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, TenantIdMapper.class);
    }

    // ─── Metadata del mapper (aparece en la UI de Keycloak) ───────────────────

    @Override
    public String getId() {
        return PROVIDER_ID;  // identificador interno único
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;  // constante = "Token mapper"
    }

    @Override
    public String getDisplayType() {
        return "Altana Tenant ID Mapper";  // nombre en la UI
    }

    @Override
    public String getHelpText() {
        return "Lee el atributo 'tenant_id' del usuario y lo agrega al JWT. " +
               "Configura 'Token Claim Name' como 'tenant_id'.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    // ─── Lógica principal: aquí construimos el claim ──────────────────────────

    /*
     * setClaim() es llamado por Keycloak una vez por token emitido.
     *
     * Parámetros clave:
     *   token          → el JWT en construcción (podemos modificarlo)
     *   mappingModel   → la configuración del mapper (nombre del claim, etc.)
     *   userSession    → la sesión del usuario → acceso a User, atributos, etc.
     *   keycloakSession→ acceso a todos los servicios de Keycloak (DB, etc.)
     *   clientSessionCtx → contexto del client que pidió el token
     *
     * ENTREVISTA: ¿Cómo accedes a los datos del usuario dentro de un mapper?
     * → userSession.getUser() devuelve el UserModel.
     *   getFirstAttribute("nombre") lee atributos custom del usuario.
     *   getEmail(), getUsername(), etc. para campos estándar.
     */
    @Override
    protected void setClaim(IDToken token,
                            ProtocolMapperModel mappingModel,
                            UserSessionModel userSession,
                            KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        // Leer el atributo "tenant_id" del usuario en Keycloak.
        // Este atributo se configura en la UI: Users → {user} → Attributes
        String tenantId = userSession.getUser().getFirstAttribute("tenant_id");

        if (tenantId == null || tenantId.isBlank()) {
            // Sin tenant_id no agregamos el claim al token.
            // Si fuera obligatorio podríamos lanzar una excepción aquí
            // para bloquear la emisión del token.
            return;
        }

        /*
         * OIDCAttributeMapperHelper.mapClaim() hace el trabajo sucio:
         * - Lee el nombre del claim desde mappingModel ("Token Claim Name")
         * - Soporta notación con puntos: "org.tenant" → claim anidado
         * - Agrega el valor al IDToken (que luego se serializa a JWT)
         *
         * Esto respeta la configuración que el admin hizo en la UI.
         */
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, tenantId);
    }
}
