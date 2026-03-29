package com.altana.keycloak.authenticator;

/*
 * ============================================================
 * CONCEPT: Custom Authenticator SPI
 * ============================================================
 *
 * An Authenticator is a STEP inside an authentication flow.
 * Keycloak has flows (Browser Flow, Direct Grant Flow, etc.)
 * and each step in the flow is an Authenticator.
 *
 * Standard Keycloak "Browser" flow:
 *   1. Cookie check
 *   2. Kerberos (skip if not applicable)
 *   3. Username + Password   ← standard step
 *   4. [OUR STEP] OTP via Email or SMS   ← what we add
 *   5. Success → issues the token
 *
 * Authenticator lifecycle:
 *
 *   authenticate(context) → called when the flow reaches this step
 *       └─ Typically shows a form with context.challenge(response)
 *
 *   action(context) → called when the user submits the form
 *       └─ Processes the response:
 *           context.success()  → step passed, flow continues
 *           context.challenge() → re-show the form (with error)
 *           context.failure()  → authentication failed, flow ends
 *
 * STATE MACHINE (we use auth notes to persist state between requests):
 *
 *   STATE 1: otp_method == null
 *       → show method selection form
 *
 *   STATE 2: otp_method != null, otp_code != null
 *       → OTP already sent, show code entry form
 *
 * Auth Notes: data stored in the authentication session (not in the token).
 * They are ephemeral — they exist only for the duration of the login flow.
 *
 * INTERVIEW: Where do you store temporary state during an authentication flow?
 * → In AuthenticationSession via setAuthNote() / getAuthNote().
 *   They are flow-scoped, do not end up in the token, and expire with the session.
 *
 * ============================================================
 */

import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.security.SecureRandom;
import java.time.Instant;

public class AltanaOtpAuthenticator implements Authenticator {

    // ─── Auth note keys (flow state) ─────────────────────────────────────────

    static final String NOTE_OTP_METHOD = "altana_otp_method";   // "email" | "sms"
    static final String NOTE_OTP_CODE   = "altana_otp_code";     // "348291"
    static final String NOTE_OTP_EXPIRY = "altana_otp_expiry";   // unix timestamp

    private static final int OTP_EXPIRY_SECONDS = 300;  // 5 minutes
    private static final SecureRandom RANDOM = new SecureRandom();

    // ─── authenticate() — first time Keycloak reaches this step ──────────────

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        String otpMethod = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

        if (otpMethod == null) {
            // State 1: user has not yet chosen a method → show selection form
            showMethodSelectionForm(context, null);
        } else {
            // State 2: OTP already sent (e.g. user refreshed the page)
            showOtpEntryForm(context, otpMethod, null);
        }
    }

    // ─── action() — user submitted a form ────────────────────────────────────

    @Override
    public void action(AuthenticationFlowContext context) {
        String formAction = getFormParam(context, "form_action");
        String otpMethod  = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

        // User clicked "Change method" from the code entry form
        if ("change_method".equals(formAction)) {
            clearOtpNotes(context);
            showMethodSelectionForm(context, null);
            return;
        }

        // User wants to resend the code
        if ("resend".equals(formAction)) {
            resendOtp(context, otpMethod);
            return;
        }

        if (otpMethod == null) {
            // Processing the METHOD SELECTION form
            processMethodSelection(context);
        } else {
            // Processing the CODE ENTRY form
            processOtpEntry(context, otpMethod);
        }
    }

    // ─── Logic: method selection ──────────────────────────────────────────────

    private void processMethodSelection(AuthenticationFlowContext context) {
        String selectedMethod = getFormParam(context, "otp_method");

        if (!"email".equals(selectedMethod) && !"sms".equals(selectedMethod)) {
            showMethodSelectionForm(context, "You must select a method");
            return;
        }

        // Validate that the user has the required contact detail
        if ("email".equals(selectedMethod) && context.getUser().getEmail() == null) {
            showMethodSelectionForm(context, "Your account has no registered email");
            return;
        }
        if ("sms".equals(selectedMethod) &&
                context.getUser().getFirstAttribute("phone_number") == null) {
            showMethodSelectionForm(context, "Your account has no registered phone number");
            return;
        }

        String otpCode = generateOtp();
        long   expiry  = Instant.now().getEpochSecond() + OTP_EXPIRY_SECONDS;

        // Persist state in the authentication session
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_METHOD, selectedMethod);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_CODE,   otpCode);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_EXPIRY, String.valueOf(expiry));

        // Send OTP
        try {
            if ("email".equals(selectedMethod)) {
                sendOtpByEmail(context, otpCode);
            } else {
                sendOtpBySms(context, otpCode);
            }
        } catch (Exception e) {
            // If sending fails, clear state and show error
            clearOtpNotes(context);
            showMethodSelectionForm(context, "Failed to send the code. Please try again.");
            return;
        }

        showOtpEntryForm(context, selectedMethod, null);
    }

    // ─── Logic: code validation ───────────────────────────────────────────────

    private void processOtpEntry(AuthenticationFlowContext context, String otpMethod) {
        String enteredOtp  = getFormParam(context, "otp_code");
        String storedOtp   = context.getAuthenticationSession().getAuthNote(NOTE_OTP_CODE);
        String expiryStr   = context.getAuthenticationSession().getAuthNote(NOTE_OTP_EXPIRY);

        // Check expiry
        if (expiryStr == null || Instant.now().getEpochSecond() > Long.parseLong(expiryStr)) {
            clearOtpNotes(context);
            showMethodSelectionForm(context, "Code expired (5 min). Please request a new one.");
            return;
        }

        // Validate code
        if (storedOtp != null && storedOtp.equals(enteredOtp)) {
            /*
             * INTERVIEW: What does context.success() do?
             * → It tells the authentication flow that this step has passed.
             *   Keycloak advances to the next step in the flow, or if this was
             *   the last step, issues the token and redirects to the client.
             */
            context.success();
        } else {
            /*
             * context.failure() vs context.challenge():
             *   failure()   → authentication ended, shows an error page
             *   challenge() → re-shows a form (user can retry)
             */
            showOtpEntryForm(context, otpMethod, "Incorrect code. Please try again.");
        }
    }

    // ─── Resend code ──────────────────────────────────────────────────────────

    private void resendOtp(AuthenticationFlowContext context, String otpMethod) {
        String newCode = generateOtp();
        long   expiry  = Instant.now().getEpochSecond() + OTP_EXPIRY_SECONDS;

        context.getAuthenticationSession().setAuthNote(NOTE_OTP_CODE,   newCode);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_EXPIRY, String.valueOf(expiry));

        try {
            if ("email".equals(otpMethod)) {
                sendOtpByEmail(context, newCode);
            } else {
                sendOtpBySms(context, newCode);
            }
            showOtpEntryForm(context, otpMethod, null);
        } catch (Exception e) {
            clearOtpNotes(context);
            showMethodSelectionForm(context, "Failed to resend the code.");
        }
    }

    // ─── Send OTP by email (real via SMTP → MailHog in dev) ──────────────────

    private void sendOtpByEmail(AuthenticationFlowContext context, String otpCode) throws Exception {
        /*
         * CONCEPT: EmailSenderProvider is Keycloak's email SPI.
         * In dev we use MailHog (port 1025). In prod: SendGrid, SES, etc.
         * SMTP configuration comes from the realm (Realm Settings → Email tab).
         *
         * INTERVIEW: How do you send emails from a Keycloak extension?
         * → Via EmailSenderProvider, which uses the realm SMTP configuration.
         *   This lets the admin change the mail server without touching code.
         */
        String subject = "[Altana] Your verification code";
        String html = String.format("""
            <html><body style='font-family:sans-serif; max-width:500px; margin:auto'>
              <h2 style='color:#1a73e8'>Two-step verification</h2>
              <p>Hello <strong>%s</strong>,</p>
              <p>Your verification code is:</p>
              <div style='font-size:36px; font-weight:bold; letter-spacing:8px;
                          background:#f0f4ff; padding:20px; text-align:center;
                          border-radius:8px; margin:20px 0'>%s</div>
              <p style='color:#666'>Expires in 5 minutes. Do not share it with anyone.</p>
              <hr style='border:none; border-top:1px solid #eee'>
              <p style='color:#999; font-size:0.8em'>Altana Supply Chain Analytics</p>
            </body></html>
            """,
            context.getUser().getUsername(), otpCode
        );

        EmailSenderProvider emailSender = context.getSession().getProvider(EmailSenderProvider.class);
        emailSender.send(
            context.getRealm().getSmtpConfig(),
            context.getUser(),
            subject,
            null,   // plain text (optional)
            html
        );

        System.out.printf("[ALTANA-EMAIL] OTP %s sent to %s%n",
            otpCode, context.getUser().getEmail());
    }

    // ─── Send OTP by SMS (simulated) ──────────────────────────────────────────

    private void sendOtpBySms(AuthenticationFlowContext context, String otpCode) {
        /*
         * CONCEPT: Simulated SMS — in production this would be:
         *   - Twilio: twilio.com/docs/sms
         *   - AWS SNS: sdk PublishRequest
         *   - Vonage / Infobip / etc.
         *
         * The authenticator interface stays the same.
         * Only this method's implementation changes.
         *
         * INTERVIEW: How would you structure a Twilio integration?
         * → Create an SmsService interface with a TwilioSmsService implementation.
         *   Credentials (account_sid, auth_token) go in the authenticator's
         *   getConfigProperties(), not hardcoded.
         *   The admin configures them in the Keycloak UI when adding the step.
         */
        String phone = context.getUser().getFirstAttribute("phone_number");

        // Simulated send — visible in Docker via: docker logs altana-keycloak
        System.out.printf(
            "[ALTANA-SMS] === SMS SIMULATION ===%n" +
            "  To:      %s%n" +
            "  User:    %s%n" +
            "  Message: Your Altana code is: %s (expires in 5 min)%n" +
            "[ALTANA-SMS] =====================%n",
            phone, context.getUser().getUsername(), otpCode
        );
    }

    // ─── Forms (FTL templates) ────────────────────────────────────────────────

    private void showMethodSelectionForm(AuthenticationFlowContext context, String error) {
        /*
         * context.form() returns a LoginFormsProvider.
         * .setAttribute() → injects variables available in the FTL template
         * .setError()      → displays an error message in the form
         * .createForm()    → renders the FTL template from the active theme
         * context.challenge(response) → tells the flow "wait for user input"
         */
        var form = context.form();
        if (error != null) form = form.setError(error);
        Response challenge = form.createForm("altana-select-otp-method.ftl");
        context.challenge(challenge);
    }

    private void showOtpEntryForm(AuthenticationFlowContext context,
                                  String otpMethod, String error) {
        String maskedDest = getMaskedDestination(context.getUser(), otpMethod);
        var form = context.form()
            .setAttribute("otp_method", otpMethod)
            .setAttribute("destination", maskedDest);
        if (error != null) form = form.setError(error);
        Response challenge = form.createForm("altana-enter-otp.ftl");
        context.challenge(challenge);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String generateOtp() {
        // 6 digits: [100000, 999999]
        return String.valueOf(100000 + RANDOM.nextInt(900000));
    }

    private String getMaskedDestination(UserModel user, String method) {
        if ("email".equals(method)) {
            String email = user.getEmail();
            if (email == null) return "***";
            int at = email.indexOf('@');
            if (at <= 2) return email;
            return email.substring(0, 2) + "***" + email.substring(at);
        } else {
            String phone = user.getFirstAttribute("phone_number");
            if (phone == null || phone.length() < 4) return "***";
            return "***" + phone.substring(phone.length() - 4);
        }
    }

    private String getFormParam(AuthenticationFlowContext context, String param) {
        return context.getHttpRequest().getDecodedFormParameters().getFirst(param);
    }

    private void clearOtpNotes(AuthenticationFlowContext context) {
        context.getAuthenticationSession().removeAuthNote(NOTE_OTP_METHOD);
        context.getAuthenticationSession().removeAuthNote(NOTE_OTP_CODE);
        context.getAuthenticationSession().removeAuthNote(NOTE_OTP_EXPIRY);
    }

    // ─── Required interface methods ───────────────────────────────────────────

    @Override
    public boolean requiresUser() {
        // true = this step requires the user to already be identified
        // (it comes AFTER the username+password step)
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // true = this authenticator applies to all users
        // You could return false for users who already have another 2FA configured
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Here you could add required actions if the user needs to configure something.
        // Example: prompt them to register a phone number if they have no phone_number.
    }

    @Override
    public void close() {}
}
