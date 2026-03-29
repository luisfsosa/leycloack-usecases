# UC2 — Keycloak SPI: Custom Authenticator (2FA OTP via Email / SMS)

## What you learn here

How to build a custom authentication step in Keycloak using the Authenticator SPI.
This implements a real-world 2FA flow where users choose between Email OTP (real SMTP)
and SMS OTP (simulated), covering state management, form rendering, and Kubernetes deployment.

---

## How authentication flows work in Keycloak

A **flow** is a sequence of steps. Each step is an **Authenticator**.

```
Browser Flow: "Browser with OTP"
│
├─ [ALTERNATIVE] Cookie                    → skip if valid session exists
├─ [ALTERNATIVE] Identity Provider         → "Login with Toyota" button
├─ [REQUIRED]    Username Password Form    → standard Keycloak step
└─ [REQUIRED]    Altana OTP (Email / SMS)  ← our custom step
```

Each Authenticator has two methods called by Keycloak:

| Method | When called | Purpose |
|--------|-------------|---------|
| `authenticate(context)` | Flow reaches this step | Show a form or auto-complete the step |
| `action(context)` | User submits a form | Process the input and decide next state |

And three possible outcomes:

| Outcome | Method | Meaning |
|---------|--------|---------|
| Show form | `context.challenge(response)` | Wait for user input (step stays active) |
| Pass | `context.success()` | Step completed, flow continues |
| Fail | `context.failure(error)` | Authentication denied, flow ends |

---

## State machine

State is stored in `AuthenticationSession` via auth notes — temporary key/value pairs
that live only during the authentication flow, never in the token.

```
STATE 1: otp_method == null
    authenticate() → show method selection form
    action()       → user picks email or SMS
                     → generate OTP, set auth notes, send OTP
                     → show OTP entry form (transition to STATE 2)

STATE 2: otp_method != null (email | sms)
    authenticate() → show OTP entry form (e.g. user refreshed the page)
    action()       → user submits OTP code
                     → validate: correct + not expired → context.success()
                     → validate: wrong code          → show form with error
                     → validate: expired             → clear state, back to STATE 1
```

Auth notes used:

| Key | Value | Purpose |
|-----|-------|---------|
| `altana_otp_method` | `"email"` or `"sms"` | Which channel the user chose |
| `altana_otp_code` | `"482931"` | The generated OTP |
| `altana_otp_expiry` | Unix timestamp | Expiry (5 minutes from send) |

> **Interview:** Where do you store temporary state during a Keycloak authentication flow?
> → In `AuthenticationSession` via `setAuthNote()` / `getAuthNote()`.
>   Auth notes are scoped to the current flow, never included in the token,
>   and expire when the session expires. Never use instance fields on the
>   Authenticator class — it is a singleton shared across all requests.

---

## Project structure

```
keycloak-extension/
└── src/main/
    ├── java/com/altana/keycloak/authenticator/
    │   ├── AltanaOtpAuthenticator.java         ← business logic
    │   └── AltanaOtpAuthenticatorFactory.java  ← metadata + singleton factory
    └── resources/META-INF/services/
        └── org.keycloak.authentication.AuthenticatorFactory

docker/keycloak/themes/altana/login/
    ├── theme.properties                        ← parent=keycloak
    ├── altana-select-otp-method.ftl            ← form 1: choose email or SMS
    └── altana-enter-otp.ftl                    ← form 2: enter the code
```

---

## AltanaOtpAuthenticator.java — key concepts

### authenticate() — entry point

```java
@Override
public void authenticate(AuthenticationFlowContext context) {
    String otpMethod = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

    if (otpMethod == null) {
        showMethodSelectionForm(context, null);   // STATE 1
    } else {
        showOtpEntryForm(context, otpMethod, null); // STATE 2 (page refresh)
    }
}
```

### action() — form submission handler

Keycloak calls `action()` every time the user submits a form while this step is active.
We use a hidden field `form_action` to distinguish between different form submissions:

```java
@Override
public void action(AuthenticationFlowContext context) {
    String formAction = getFormParam(context, "form_action");

    if ("change_method".equals(formAction)) { /* user wants to go back */ }
    if ("resend".equals(formAction))        { /* user wants a new code */ }

    String otpMethod = context.getAuthenticationSession().getAuthNote(NOTE_OTP_METHOD);

    if (otpMethod == null) {
        processMethodSelection(context);  // STATE 1: pick email or SMS
    } else {
        processOtpEntry(context, otpMethod);  // STATE 2: validate code
    }
}
```

### Email sending via EmailSenderProvider

```java
private void sendOtpByEmail(AuthenticationFlowContext context, String otpCode) throws Exception {
    EmailSenderProvider emailSender = context.getSession().getProvider(EmailSenderProvider.class);
    emailSender.send(
        context.getRealm().getSmtpConfig(),  // SMTP config from realm settings
        context.getUser(),
        "Your Altana verification code",
        null,
        "<html>...<strong>" + otpCode + "</strong>...</html>"
    );
}
```

The SMTP config comes from **Realm Settings → Email** in the Admin UI.
In local dev this points to MailHog (port 1025). In production: SendGrid, AWS SES, etc.

> **Interview:** How do you send emails from a Keycloak extension?
> → Via `EmailSenderProvider`, which uses the SMTP config set in the realm.
>   The admin configures the mail server in the UI — the code doesn't hardcode
>   any server details. To change from MailHog to SendGrid in production,
>   only the realm config changes, not the extension code.

### SMS simulation

```java
private void sendOtpBySms(AuthenticationFlowContext context, String otpCode) {
    // In production: Twilio, AWS SNS, Vonage, Infobip, etc.
    // Credentials go in getConfigProperties() → configured in Admin UI per flow step
    System.out.printf("[ALTANA-SMS] OTP %s → %s (user: %s)%n",
        otpCode, phone, context.getUser().getUsername());
}
```

To see simulated SMS: `docker logs altana-keycloak 2>&1 | grep ALTANA-SMS`

> **Interview:** How would you structure a real SMS integration?
> → Create an `SmsService` interface with a `TwilioSmsService` implementation.
>   Credentials (account_sid, auth_token, from_number) go in `getConfigProperties()`
>   on the factory — the admin configures them when adding the step to the flow.
>   This way different flows can use different SMS providers without code changes.

---

## AltanaOtpAuthenticatorFactory.java — key concepts

The Factory is what Keycloak registers and shows in the Admin UI.
It is a singleton that produces `Authenticator` instances.

```java
public class AltanaOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "altana-otp-authenticator";

    // Authenticator is stateless (all state in auth notes) → safe singleton
    private static final AltanaOtpAuthenticator INSTANCE = new AltanaOtpAuthenticator();

    @Override public String getId()          { return PROVIDER_ID; }
    @Override public String getDisplayType() { return "Altana OTP (Email / SMS)"; }
    @Override public String getReferenceCategory() { return "otp"; }
    @Override public boolean isConfigurable()      { return false; }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {
            REQUIRED, ALTERNATIVE, DISABLED
        };
    }

    @Override
    public Authenticator create(KeycloakSession session) { return INSTANCE; }
}
```

> **Interview:** Why does Keycloak use the Factory pattern for SPIs?
> → It separates lifecycle management (factory = singleton, owns config and metadata)
>   from business logic (authenticator = can be stateless singleton or stateful per-request).
>   The factory is also what `ServiceLoader` discovers before any instances are created,
>   allowing Keycloak to list available providers without instantiating them.

---

## Freemarker templates (FTL)

Templates live in a custom theme that extends the base Keycloak theme:

```
docker/keycloak/themes/altana/login/
├── theme.properties         → parent=keycloak
├── altana-select-otp-method.ftl
└── altana-enter-otp.ftl
```

`theme.properties`:
```properties
parent=keycloak   # inherit all base styles and templates
```

Rendering a template from the authenticator:
```java
Response challenge = context.form()
    .setAttribute("otp_method", selectedMethod)   // available as ${otp_method} in FTL
    .setAttribute("destination", maskedDest)       // available as ${destination} in FTL
    .setError("Wrong code. Try again.")            // shown via ${message.summary} in FTL
    .createForm("altana-enter-otp.ftl");
context.challenge(challenge);
```

FTL template structure:
```ftl
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=true; section>

    <#if section = "header">Page title</#if>

    <#if section = "info">
        We sent a code to ${destination}   <!-- injected via setAttribute -->
    </#if>

    <#if section = "form">
        <form action="${url.loginAction}" method="post">
            <!-- form_action hidden field lets action() distinguish between forms -->
            <input type="hidden" name="form_action" value="verify_otp" />
            <input name="otp_code" type="text" autocomplete="one-time-code" autofocus />
            <button type="submit">Verify</button>

            <!-- Secondary actions via named submit buttons -->
            <button type="submit" name="form_action" value="resend">Resend code</button>
            <button type="submit" name="form_action" value="change_method">Use another method</button>
        </form>
    </#if>

</@layout.registrationLayout>
```

> **Interview:** How do you pass data from the authenticator to the login page?
> → Via `context.form().setAttribute("key", value)` before calling `createForm()`.
>   The value is then available as `${key}` in the Freemarker template.

---

## Setting up the flow in Admin UI

1. **Authentication** → **Flows** → **Browser** → `⋮` → **Duplicate**
   Name it `Browser with OTP`

2. Inside the duplicated flow → **Add step** → search `Altana OTP (Email / SMS)` → **Add**

3. Set it as **REQUIRED** and drag it to after `Username Password Form`

4. **Authentication** → **Bindings** → **Browser Flow** → select `Browser with OTP`

5. **Realm Settings** → **Themes** → **Login theme** → `altana`

6. **Realm Settings** → **Email** → configure SMTP:
   - Host: `mailhog` (container name in Docker network)
   - Port: `1025`
   - From: `keycloak@altana.dev`

---

## Testing the full flow

```bash
# Open in browser (with valid PKCE params)
http://localhost:5173   # React app → Login with Keycloak button

# Login: analyst-user / Analyst123!
# → Method selection form appears (our FTL)
# → Choose Email → code arrives at http://localhost:8025 (MailHog)
# → Choose SMS  → code appears in: docker logs altana-keycloak 2>&1 | grep ALTANA-SMS
# → Enter code → redirect to React dashboard
```

---

## Deploying to Kubernetes

The key difference from local Docker development:
volumes cannot be used for extensions in Kubernetes — the JAR must be inside the image.

### Option 1: Custom Docker image (recommended for production)

```dockerfile
# Stage 1: Build the extension
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY keycloak-extension/ .
RUN ./gradlew jar --no-daemon

# Stage 2: Keycloak image with extension baked in
FROM quay.io/keycloak/keycloak:26.5.6
COPY --from=builder /build/build/libs/altana-keycloak-extension-*.jar /opt/keycloak/providers/
COPY docker/keycloak/themes/ /opt/keycloak/themes/
# Pre-compile Keycloak with providers (fast startup in production)
RUN /opt/keycloak/bin/kc.sh build
```

```yaml
# kubernetes/deployment.yaml
spec:
  containers:
    - name: keycloak
      image: registry.company.com/altana/keycloak:26.5.6-v1.2.0
```

> **Interview:** Why run `kc.sh build` in the Dockerfile?
> → Keycloak 26 is built on Quarkus. `kc.sh build` pre-compiles and optimizes
>   Keycloak with all discovered providers baked in. Without it, Keycloak does
>   this optimization on first startup (slow, ~60s). With it, startup is fast (~5s).
>   In `start-dev` mode (local Docker) this step is not required.

### Option 2: Init container

```yaml
initContainers:
  - name: copy-providers
    image: registry.company.com/altana/keycloak-providers:1.0.0
    command: ["cp", "/providers/altana-extension.jar", "/target/"]
    volumeMounts:
      - name: providers-vol
        mountPath: /target
containers:
  - name: keycloak
    image: quay.io/keycloak/keycloak:26.5.6
    volumeMounts:
      - name: providers-vol
        mountPath: /opt/keycloak/providers
volumes:
  - name: providers-vol
    emptyDir: {}
```

### Comparison

| Criteria | Custom image | Init container |
|----------|-------------|----------------|
| Immutability | Yes | No |
| Rollback | `kubectl rollout undo` | More complex |
| CI/CD | Standard pipeline | Requires two pipelines |
| `kc.sh build` | Inside Dockerfile | Hard to manage |
| **Recommended** | **Production** | Legacy/specific cases |

### Zero-downtime rollout

```bash
# CI/CD pipeline
./gradlew jar
docker build -t registry/altana/keycloak:26.5.6-v1.2.0 .
docker push registry/altana/keycloak:26.5.6-v1.2.0
kubectl set image deployment/keycloak keycloak=registry/altana/keycloak:26.5.6-v1.2.0
# Kubernetes replaces pods one at a time (RollingUpdate strategy)
```

Deployment config for zero downtime:
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0   # never take a pod down before a new one is ready
    maxSurge: 1         # allow one extra pod during the update
```

> **Interview:** How do you guarantee zero downtime when updating a Keycloak extension in Kubernetes?
> → Use a custom Docker image with the JAR baked in, and configure `RollingUpdate`
>   with `maxUnavailable: 0`. Kubernetes starts a new pod with the new image,
>   waits for it to pass health checks, then terminates the old pod. Since all
>   Keycloak config lives in the database (not the container), both old and new
>   pods can serve requests simultaneously during the rollout.

---

## Interview questions

**Q: What is the Keycloak SPI?**
> Service Provider Interface — Keycloak's extension mechanism. You implement a Java
> interface (Authenticator, ProtocolMapper, EventListener, etc.), package the class
> in a JAR with a `META-INF/services/` registration file, and place the JAR in
> `/opt/keycloak/providers/`. Keycloak discovers providers via `java.util.ServiceLoader`.

**Q: What is `context.challenge()` vs `context.success()` vs `context.failure()`?**
> `challenge(response)` — shows a form and waits for user input; the step stays active.
> `success()` — the step is complete; the flow moves to the next step or issues the token.
> `failure(error)` — authentication is denied; the flow ends with an error page.

**Q: How do you handle state between form submissions in a custom authenticator?**
> Via `AuthenticationSession.setAuthNote(key, value)`. Auth notes are temporary
> key/value pairs scoped to the current authentication flow. They are not included
> in the token and expire when the flow session expires. The Authenticator class
> itself must be stateless (it is a singleton) — never store per-request state
> in instance fields.

**Q: How do you prevent double-submission of the PKCE callback in React?**
> In React 18 StrictMode, components mount → unmount → remount in development.
> A module-level variable (outside the component) that tracks whether the callback
> was processed survives remounts. `useRef` does not — it is reset on remount.
> In production (StrictMode disabled) this is a non-issue, but the pattern is
> still a good defensive measure against duplicate requests.
