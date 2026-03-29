package com.altana.keycloak.authenticator;

/*
 * CONCEPTO: AuthenticatorFactory — el par obligatorio de todo Authenticator
 *
 * En el SPI de Keycloak, cada provider tiene dos clases:
 *   - La implementación:  AltanaOtpAuthenticator   (hace el trabajo)
 *   - La factory:        AltanaOtpAuthenticatorFactory (crea instancias + metadata)
 *
 * La Factory es lo que Keycloak registra y muestra en la UI de Admin.
 * Cuando el admin abre "Authentication → Flows → Add step", ve el
 * getDisplayType() de la factory.
 *
 * ENTREVISTA: ¿Por qué Keycloak usa el patrón Factory para los SPIs?
 * → Separa la configuración y el ciclo de vida (factory) de la lógica
 *   de negocio (authenticator). La factory es un singleton; el authenticator
 *   puede ser stateless (también singleton) o stateful (instancia por request).
 *   También permite que Keycloak descubra providers via ServiceLoader antes
 *   de crear instancias.
 */

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class AltanaOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "altana-otp-authenticator";

    /*
     * El Authenticator es stateless (no guarda estado en campos de instancia —
     * todo el estado va en AuthenticationSession via auth notes).
     * Por eso podemos usar un singleton seguro para todos los requests.
     */
    private static final AltanaOtpAuthenticator INSTANCE = new AltanaOtpAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Altana OTP (Email / SMS)";  // lo que ve el admin en la UI
    }

    @Override
    public String getHelpText() {
        return "Envia un OTP de 6 digitos por Email (real via SMTP) o SMS (simulado). " +
               "El usuario elige el canal preferido en cada login.";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";  // agrupa el authenticator en la categoría OTP de la UI
    }

    @Override
    public boolean isConfigurable() {
        return false;
        /*
         * Si fuera true, el admin podría configurar propiedades al agregar el paso.
         * Por ejemplo: max intentos, longitud del OTP, proveedor de SMS.
         * Para hacerlo configurable: retornar true + implementar getConfigProperties()
         */
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
        /*
         * Si fuera true, el usuario podría configurar este factor desde su perfil.
         * Ejemplo: "enrollar" un número de teléfono para SMS.
         * Lo dejamos false — asumimos que phone_number ya está en el perfil.
         */
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        /*
         * Define qué opciones tiene el admin al agregar este paso al flujo:
         * REQUIRED  = siempre se ejecuta (obligatorio)
         * ALTERNATIVE = se ejecuta si ningún otro paso alternativo fue suficiente
         * CONDITIONAL = se ejecuta solo si se cumple una condición
         * DISABLED  = existe pero no se ejecuta
         */
        return new AuthenticationExecutionModel.Requirement[] {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        /*
         * Si isConfigurable() fuera true, aquí declararías los campos:
         *
         * return List.of(
         *     new ProviderConfigProperty("sms_provider", "SMS Provider",
         *         "twilio | aws_sns | log_only",
         *         ProviderConfigProperty.STRING_TYPE, "log_only"),
         *     new ProviderConfigProperty("otp_length", "OTP Length",
         *         "Number of digits (4-8)",
         *         ProviderConfigProperty.STRING_TYPE, "6")
         * );
         */
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return INSTANCE;  // stateless → mismo objeto para todos
    }

    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}
}
