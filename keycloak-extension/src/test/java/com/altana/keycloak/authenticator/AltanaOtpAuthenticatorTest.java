package com.altana.keycloak.authenticator;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.altana.keycloak.authenticator.AltanaOtpAuthenticator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AltanaOtpAuthenticator.
 *
 * Testing an Authenticator means testing a state machine. The auth notes
 * (stored in AuthenticationSession) represent the state.
 * We simulate that state with a HashMap and Mockito's doAnswer.
 *
 * Key pattern: we use a real HashMap as the backing store for auth notes,
 * so we can inspect state after each call without capturing arguments one by one.
 *
 * INTERVIEW: "How do you test an Authenticator without a real Keycloak?"
 * → Mock AuthenticationFlowContext and AuthenticationSession.
 *   Simulate auth notes with a local Map. Verify the outputs:
 *   context.success(), context.challenge(), context.failure().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AltanaOtpAuthenticatorTest {

    @Mock AuthenticationFlowContext context;
    @Mock AuthenticationSessionModel authSession;
    @Mock UserModel       user;
    @Mock RealmModel      realm;
    @Mock KeycloakSession session;
    @Mock LoginFormsProvider formsProvider;
    @Mock HttpRequest     httpRequest;
    @Mock EmailSenderProvider emailSender;
    @Mock Response        challengeResponse;

    // Simulates auth notes with a real Map — much clearer than capturing args
    Map<String, String> authNotes = new HashMap<>();

    // HTTP session form parameters
    MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();

    AltanaOtpAuthenticator authenticator;

    @BeforeEach
    void setUp() throws Exception {
        authenticator = new AltanaOtpAuthenticator();

        // ── Auth notes: real backing store ────────────────────────────────────
        doAnswer(inv -> authNotes.put(inv.getArgument(0), inv.getArgument(1)))
            .when(authSession).setAuthNote(anyString(), anyString());
        when(authSession.getAuthNote(anyString()))
            .thenAnswer(inv -> authNotes.get((String) inv.getArgument(0)));
        doAnswer(inv -> authNotes.remove(inv.getArgument(0)))
            .when(authSession).removeAuthNote(anyString());

        // ── Context basics ────────────────────────────────────────────────────
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.getUser()).thenReturn(user);
        when(context.getRealm()).thenReturn(realm);
        when(context.getSession()).thenReturn(session);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(httpRequest.getDecodedFormParameters()).thenReturn(formParams);

        // ── Form: setter chain returns the same mock ───────────────────────────
        when(context.form()).thenReturn(formsProvider);
        when(formsProvider.setAttribute(anyString(), any())).thenReturn(formsProvider);
        when(formsProvider.setError(anyString())).thenReturn(formsProvider);
        when(formsProvider.setError(anyString(), any())).thenReturn(formsProvider);
        when(formsProvider.createForm(anyString())).thenReturn(challengeResponse);

        // ── Test user ──────────────────────────────────────────────────────────
        when(user.getEmail()).thenReturn("analyst@altana.dev");
        when(user.getUsername()).thenReturn("analyst-user");
        when(user.getFirstAttribute("phone_number")).thenReturn("+34600123456");

        // ── Email sender ───────────────────────────────────────────────────────
        when(session.getProvider(EmailSenderProvider.class)).thenReturn(emailSender);
        when(realm.getSmtpConfig()).thenReturn(Map.of());
        // Overload: send(Map, UserModel, String, String, String) — must be explicit
        // because send(Map, String, String, String, String) also exists
        doNothing().when(emailSender).send(any(), any(UserModel.class), anyString(), any(), anyString());
    }

    // ── authenticate() ────────────────────────────────────────────────────────

    @Test
    void authenticate_noState_showsMethodSelectionForm() {
        // No auth notes → first time reaching this step
        authenticator.authenticate(context);

        verify(formsProvider).createForm("altana-select-otp-method.ftl");
        verify(context).challenge(challengeResponse);
        verify(context, never()).success();
    }

    @Test
    void authenticate_withOtpMethodAlreadySet_showsOtpEntryForm() {
        // If the user refreshes the page at the OTP step,
        // authenticate() is called again. It must show the code entry form,
        // not the selection form, to avoid resetting the flow.
        authNotes.put(NOTE_OTP_METHOD, "email");

        authenticator.authenticate(context);

        verify(formsProvider).createForm("altana-enter-otp.ftl");
        verify(context).challenge(challengeResponse);
    }

    // ── action() — method selection ───────────────────────────────────────────

    @Test
    void action_selectSms_setsAuthNotesAndShowsOtpForm() {
        formParams.putSingle("otp_method", "sms");

        authenticator.action(context);

        // Auth notes must be set
        assertThat(authNotes).containsKey(NOTE_OTP_METHOD);
        assertThat(authNotes.get(NOTE_OTP_METHOD)).isEqualTo("sms");
        assertThat(authNotes).containsKey(NOTE_OTP_CODE);
        assertThat(authNotes.get(NOTE_OTP_CODE)).hasSize(6);       // 6-digit OTP
        assertThat(authNotes).containsKey(NOTE_OTP_EXPIRY);

        // Must show the code entry form
        verify(formsProvider).createForm("altana-enter-otp.ftl");
        verify(context).challenge(challengeResponse);
    }

    @Test
    void action_selectEmail_setsAuthNotesAndSendsEmail() throws Exception {
        formParams.putSingle("otp_method", "email");

        authenticator.action(context);

        assertThat(authNotes.get(NOTE_OTP_METHOD)).isEqualTo("email");
        // Verify that an email send was attempted
        verify(emailSender).send(any(), eq(user), anyString(), any(), anyString());
        verify(formsProvider).createForm("altana-enter-otp.ftl");
    }

    @Test
    void action_invalidMethod_showsErrorOnMethodSelection() {
        formParams.putSingle("otp_method", "carrier_pigeon");  // invalid method

        authenticator.action(context);

        // Must not set any auth notes
        assertThat(authNotes).isEmpty();
        // Must return to the selection form with an error
        verify(formsProvider).createForm("altana-select-otp-method.ftl");
    }

    @Test
    void action_emailMethodButUserHasNoEmail_showsError() {
        when(user.getEmail()).thenReturn(null);
        formParams.putSingle("otp_method", "email");

        authenticator.action(context);

        assertThat(authNotes).isEmpty();
        verify(formsProvider).createForm("altana-select-otp-method.ftl");
    }

    @Test
    void action_smsMethodButUserHasNoPhone_showsError() {
        when(user.getFirstAttribute("phone_number")).thenReturn(null);
        formParams.putSingle("otp_method", "sms");

        authenticator.action(context);

        assertThat(authNotes).isEmpty();
        verify(formsProvider).createForm("altana-select-otp-method.ftl");
    }

    // ── action() — code validation ────────────────────────────────────────────

    @Test
    void action_correctOtp_callsSuccess() {
        setupOtpState("sms", "482931", futureExpiry());
        formParams.putSingle("otp_code", "482931");

        authenticator.action(context);

        verify(context).success();
        verify(context, never()).challenge(any());
        verify(context, never()).failure(any());
    }

    @Test
    void action_wrongOtp_showsErrorAndDoesNotCallSuccess() {
        setupOtpState("sms", "482931", futureExpiry());
        formParams.putSingle("otp_code", "000000");  // wrong code

        authenticator.action(context);

        verify(context, never()).success();
        verify(formsProvider).createForm("altana-enter-otp.ftl");
        verify(context).challenge(challengeResponse);
    }

    @Test
    void action_expiredOtp_clearsStateAndShowsMethodSelection() {
        // Expired OTP → reset the state machine completely.
        // The user must choose a method again (they cannot retry with the old code).
        setupOtpState("sms", "482931", pastExpiry());
        formParams.putSingle("otp_code", "482931");  // code would be correct, but expired

        authenticator.action(context);

        // State must be cleared
        assertThat(authNotes).doesNotContainKey(NOTE_OTP_METHOD);
        assertThat(authNotes).doesNotContainKey(NOTE_OTP_CODE);
        assertThat(authNotes).doesNotContainKey(NOTE_OTP_EXPIRY);

        // Returns to the start of the flow
        verify(formsProvider).createForm("altana-select-otp-method.ftl");
        verify(context, never()).success();
    }

    // ── action() — secondary actions ─────────────────────────────────────────

    @Test
    void action_changeMethod_clearsStateAndShowsMethodSelection() {
        setupOtpState("sms", "482931", futureExpiry());
        formParams.putSingle("form_action", "change_method");

        authenticator.action(context);

        assertThat(authNotes).doesNotContainKey(NOTE_OTP_METHOD);
        verify(formsProvider).createForm("altana-select-otp-method.ftl");
    }

    @Test
    void action_resend_generatesNewCodeAndShowsOtpForm() {
        setupOtpState("sms", "111111", futureExpiry());
        formParams.putSingle("form_action", "resend");

        authenticator.action(context);

        // Code must have changed
        String newCode = authNotes.get(NOTE_OTP_CODE);
        assertThat(newCode).isNotEqualTo("111111");
        assertThat(newCode).hasSize(6);

        verify(formsProvider).createForm("altana-enter-otp.ftl");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setupOtpState(String method, String code, long expiry) {
        authNotes.put(NOTE_OTP_METHOD, method);
        authNotes.put(NOTE_OTP_CODE, code);
        authNotes.put(NOTE_OTP_EXPIRY, String.valueOf(expiry));
    }

    private long futureExpiry() {
        return Instant.now().getEpochSecond() + 300;  // 5 minutes in the future
    }

    private long pastExpiry() {
        return Instant.now().getEpochSecond() - 10;   // 10 seconds in the past
    }
}
