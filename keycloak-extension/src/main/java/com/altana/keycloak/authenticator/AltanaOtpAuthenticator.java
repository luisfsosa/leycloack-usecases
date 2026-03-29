package com.altana.keycloak.authenticator;

/*
 * ============================================================
 * CONCEPTO: Custom Authenticator SPI
 * ============================================================
 *
 * Un Authenticator es un PASO dentro de un flujo de autenticación.
 * Keycloak tiene flujos (Browser Flow, Direct Grant Flow, etc.)
 * y cada paso del flujo es un Authenticator.
 *
 * Flujo estándar "Browser" de Keycloak:
 *   1. Cookie check
 *   2. Kerberos (skip si no aplica)
 *   3. Username + Password   ← paso estándar
 *   4. [NUESTRO PASO] OTP por Email o SMS   ← lo que vamos a agregar
 *   5. Success → emite el token
 *
 * Ciclo de vida del Authenticator:
 *
 *   authenticate(context) → se llama cuando el flujo llega a este paso
 *       └─ Generalmente muestra un formulario con context.challenge(response)
 *
 *   action(context) → se llama cuando el usuario envía el formulario
 *       └─ Procesa la respuesta:
 *           context.success()  → paso superado, flujo continúa
 *           context.challenge() → vuelve a mostrar el formulario (con error)
 *           context.failure()  → autenticación fallida, flujo termina
 *
 * MÁQUINA DE ESTADOS (usamos auth notes para guardar estado entre requests):
 *
 *   ESTADO 1: otp_method == null
 *       → mostrar formulario de selección de método
 *
 *   ESTADO 2: otp_method != null, otp_code != null
 *       → OTP ya enviado, mostrar formulario de ingreso de código
 *
 * Auth Notes: datos almacenados en la sesión de autenticación (no en el token).
 * Son efímeros — existen solo durante el flujo de login.
 *
 * ENTREVISTA: ¿Dónde guardas estado temporal durante un flujo de autenticación?
 * → En AuthenticationSession via setAuthNote() / getAuthNote().
 *   Son específicos del flujo, no van al token, y expiran con la sesión.
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

    // ─── Claves para auth notes (estado del flujo) ────────────────────────────

    static final String NOTE_OTP_METHOD = "altana_otp_method";   // "email" | "sms"
    static final String NOTE_OTP_CODE   = "altana_otp_code";     // "348291"
    static final String NOTE_OTP_EXPIRY = "altana_otp_expiry";   // unix timestamp

    private static final int OTP_EXPIRY_SECONDS = 300;  // 5 minutos
    private static final SecureRandom RANDOM = new SecureRandom();

    // ─── authenticate() — primera vez que Keycloak llega a este paso ──────────

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        String otpMethod = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

        if (otpMethod == null) {
            // Estado 1: el usuario aún no eligió método → mostrar selección
            showMethodSelectionForm(context, null);
        } else {
            // Estado 2: OTP ya fue enviado (ej: usuario recargó la página)
            showOtpEntryForm(context, otpMethod, null);
        }
    }

    // ─── action() — el usuario envió un formulario ────────────────────────────

    @Override
    public void action(AuthenticationFlowContext context) {
        String formAction = getFormParam(context, "form_action");
        String otpMethod  = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

        // El usuario clickeó "Cambiar método" desde el form de ingreso de código
        if ("change_method".equals(formAction)) {
            clearOtpNotes(context);
            showMethodSelectionForm(context, null);
            return;
        }

        // El usuario quiere reenviar el código
        if ("resend".equals(formAction)) {
            resendOtp(context, otpMethod);
            return;
        }

        if (otpMethod == null) {
            // Procesando el formulario de SELECCIÓN DE MÉTODO
            processMethodSelection(context);
        } else {
            // Procesando el formulario de INGRESO DE CÓDIGO
            processOtpEntry(context, otpMethod);
        }
    }

    // ─── Lógica: selección de método ──────────────────────────────────────────

    private void processMethodSelection(AuthenticationFlowContext context) {
        String selectedMethod = getFormParam(context, "otp_method");

        if (!"email".equals(selectedMethod) && !"sms".equals(selectedMethod)) {
            showMethodSelectionForm(context, "Debes seleccionar un método");
            return;
        }

        // Validar que el usuario tenga el dato necesario
        if ("email".equals(selectedMethod) && context.getUser().getEmail() == null) {
            showMethodSelectionForm(context, "Tu cuenta no tiene email registrado");
            return;
        }
        if ("sms".equals(selectedMethod) &&
                context.getUser().getFirstAttribute("phone_number") == null) {
            showMethodSelectionForm(context, "Tu cuenta no tiene número de teléfono registrado");
            return;
        }

        String otpCode = generateOtp();
        long   expiry  = Instant.now().getEpochSecond() + OTP_EXPIRY_SECONDS;

        // Guardar estado en la sesión de autenticación
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_METHOD, selectedMethod);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_CODE,   otpCode);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_EXPIRY, String.valueOf(expiry));

        // Enviar OTP
        try {
            if ("email".equals(selectedMethod)) {
                sendOtpByEmail(context, otpCode);
            } else {
                sendOtpBySms(context, otpCode);
            }
        } catch (Exception e) {
            // Si falla el envío, limpiar estado y mostrar error
            clearOtpNotes(context);
            showMethodSelectionForm(context, "Error al enviar el código. Intenta de nuevo.");
            return;
        }

        showOtpEntryForm(context, selectedMethod, null);
    }

    // ─── Lógica: validación del código ────────────────────────────────────────

    private void processOtpEntry(AuthenticationFlowContext context, String otpMethod) {
        String enteredOtp  = getFormParam(context, "otp_code");
        String storedOtp   = context.getAuthenticationSession().getAuthNote(NOTE_OTP_CODE);
        String expiryStr   = context.getAuthenticationSession().getAuthNote(NOTE_OTP_EXPIRY);

        // Validar expiración
        if (expiryStr == null || Instant.now().getEpochSecond() > Long.parseLong(expiryStr)) {
            clearOtpNotes(context);
            showMethodSelectionForm(context, "El codigo expiro (5 min). Solicita uno nuevo.");
            return;
        }

        // Validar código
        if (storedOtp != null && storedOtp.equals(enteredOtp)) {
            /*
             * ENTREVISTA: ¿Qué hace context.success()?
             * → Le indica al flujo de autenticación que este paso fue superado.
             *   Keycloak avanza al siguiente paso del flujo, o si era el último,
             *   emite el token y redirige al cliente.
             */
            context.success();
        } else {
            /*
             * context.failure() vs context.challenge():
             *   failure()   → autenticación terminada, muestra página de error
             *   challenge() → vuelve a mostrar un formulario (el usuario puede reintentar)
             */
            showOtpEntryForm(context, otpMethod, "Codigo incorrecto. Intenta de nuevo.");
        }
    }

    // ─── Reenviar código ──────────────────────────────────────────────────────

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
            showMethodSelectionForm(context, "Error al reenviar el codigo.");
        }
    }

    // ─── Envío de OTP por Email (real via SMTP → MailHog en dev) ─────────────

    private void sendOtpByEmail(AuthenticationFlowContext context, String otpCode) throws Exception {
        /*
         * CONCEPTO: EmailSenderProvider es el SPI de email de Keycloak.
         * En dev usamos MailHog (puerto 1025). En prod: SendGrid, SES, etc.
         * La config SMTP viene del realm (Realm Settings → Email tab).
         *
         * ENTREVISTA: ¿Cómo envías emails desde una extensión de Keycloak?
         * → Via EmailSenderProvider, que usa la config SMTP del realm.
         *   Así el admin puede cambiar el servidor de email sin tocar el código.
         */
        String subject = "[Altana] Tu codigo de verificacion";
        String html = String.format("""
            <html><body style='font-family:sans-serif; max-width:500px; margin:auto'>
              <h2 style='color:#1a73e8'>Verificacion en dos pasos</h2>
              <p>Hola <strong>%s</strong>,</p>
              <p>Tu codigo de verificacion es:</p>
              <div style='font-size:36px; font-weight:bold; letter-spacing:8px;
                          background:#f0f4ff; padding:20px; text-align:center;
                          border-radius:8px; margin:20px 0'>%s</div>
              <p style='color:#666'>Expira en 5 minutos. No lo compartas con nadie.</p>
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
            null,   // texto plano (opcional)
            html
        );

        System.out.printf("[ALTANA-EMAIL] OTP %s enviado a %s%n",
            otpCode, context.getUser().getEmail());
    }

    // ─── Envío de OTP por SMS (simulado) ──────────────────────────────────────

    private void sendOtpBySms(AuthenticationFlowContext context, String otpCode) {
        /*
         * CONCEPTO: SMS simulado — en producción aquí va:
         *   - Twilio: twilio.com/docs/sms
         *   - AWS SNS: sdk PublishRequest
         *   - Vonage / Infobip / etc.
         *
         * Lo importante es que la interfaz del authenticator no cambia.
         * Solo cambias la implementación de este método.
         *
         * ENTREVISTA: ¿Cómo estructurarías la integración con Twilio?
         * → Creando un SmsService (interfaz) con una implementación TwilioSmsService.
         *   Las credenciales (account_sid, auth_token) van en las config properties
         *   del authenticator (getConfigProperties()), no hardcodeadas.
         *   El admin las configura en la UI de Keycloak al agregar el paso al flujo.
         */
        String phone = context.getUser().getFirstAttribute("phone_number");

        // Simular envío — en Docker esto aparece en: docker logs altana-keycloak
        System.out.printf(
            "[ALTANA-SMS] === SIMULACION SMS ===%n" +
            "  Para:    %s%n" +
            "  Usuario: %s%n" +
            "  Mensaje: Tu codigo Altana es: %s (expira en 5 min)%n" +
            "[ALTANA-SMS] =====================%n",
            phone, context.getUser().getUsername(), otpCode
        );
    }

    // ─── Formularios (FTL templates) ──────────────────────────────────────────

    private void showMethodSelectionForm(AuthenticationFlowContext context, String error) {
        /*
         * context.form() devuelve un LoginFormsProvider.
         * .setAttribute() → inyecta variables disponibles en el FTL
         * .setError()      → muestra mensaje de error en el formulario
         * .createForm()    → renderiza el template FTL del tema activo
         * context.challenge(response) → le dice al flujo "espera input del usuario"
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
        // 6 dígitos: [100000, 999999]
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

    // ─── Métodos requeridos por la interfaz ───────────────────────────────────

    @Override
    public boolean requiresUser() {
        // true = este paso requiere que el usuario ya esté identificado
        // (viene DESPUÉS del paso de username+password)
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // true = este authenticator aplica para todos los usuarios
        // Podrías retornar false para usuarios con 2FA ya configurado de otra forma
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Aquí podrías agregar required actions si el usuario necesita configurar algo
        // Por ejemplo: pedirle que registre su teléfono si no tiene phone_number
    }

    @Override
    public void close() {}
}
