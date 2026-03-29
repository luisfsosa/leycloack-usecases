package com.altana.keycloak.authenticator;

/*
 * CONCEPT: AuthenticatorFactory — the required companion of every Authenticator
 *
 * In the Keycloak SPI, every provider has two classes:
 *   - The implementation:  AltanaOtpAuthenticator   (does the work)
 *   - The factory:        AltanaOtpAuthenticatorFactory (creates instances + metadata)
 *
 * The Factory is what Keycloak registers and displays in the Admin UI.
 * When an admin opens "Authentication → Flows → Add step", they see the
 * getDisplayType() value from the factory.
 *
 * INTERVIEW: Why does Keycloak use the Factory pattern for SPIs?
 * → It separates configuration and lifecycle (factory) from business logic
 *   (authenticator). The factory is a singleton; the authenticator can be
 *   stateless (also singleton) or stateful (instance per request).
 *   It also allows Keycloak to discover providers via ServiceLoader before
 *   creating any instances.
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
     * The Authenticator is stateless (it holds no state in instance fields —
     * all state goes into AuthenticationSession via auth notes).
     * Therefore we can safely share a single instance across all requests.
     */
    private static final AltanaOtpAuthenticator INSTANCE = new AltanaOtpAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Altana OTP (Email / SMS)";  // label the admin sees in the UI
    }

    @Override
    public String getHelpText() {
        return "Sends a 6-digit OTP via Email (real SMTP) or SMS (simulated). " +
               "The user chooses the preferred channel on each login.";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";  // groups this authenticator under the OTP category in the UI
    }

    @Override
    public boolean isConfigurable() {
        return false;
        /*
         * If true, the admin could configure properties when adding the step.
         * For example: max retries, OTP length, SMS provider.
         * To make it configurable: return true + implement getConfigProperties()
         */
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
        /*
         * If true, the user could configure this factor from their profile.
         * Example: enrol a phone number for SMS.
         * Kept false — we assume phone_number is already set on the profile.
         */
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        /*
         * Defines which options the admin has when adding this step to a flow:
         * REQUIRED    = always executed (mandatory)
         * ALTERNATIVE = executed if no other alternative step was sufficient
         * CONDITIONAL = executed only when a condition is met
         * DISABLED    = present but not executed
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
         * If isConfigurable() were true, you would declare fields here:
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
        return INSTANCE;  // stateless → same object for everyone
    }

    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() {}
}
