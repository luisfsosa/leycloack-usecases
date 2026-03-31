<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=true; section>

    <#if section = "header">
        Enter your code

    <#elseif section = "info">
        <#-- Message below the title: where the code was sent -->
        <#if otp_method?? && otp_method == "email">
            We sent a 6-digit code to
            <strong>${destination!"your email"}</strong>
        <#else>
            We sent a 6-digit code to
            <strong>${destination!"your phone"}</strong>
            (SMS simulated — check Keycloak logs)
        </#if>

    <#elseif section = "form">
        <form id="altana-enter-otp-form"
              action="${url.loginAction}"
              method="post">

            <input type="hidden" name="form_action" value="verify_otp" />

            <div class="${properties.kcFormGroupClass!}">
                <label for="otp_code" class="${properties.kcLabelClass!}">
                    Verification code
                </label>
                <input id="otp_code"
                       name="otp_code"
                       type="text"
                       inputmode="numeric"
                       pattern="[0-9]{6}"
                       maxlength="6"
                       autocomplete="one-time-code"
                       autofocus
                       placeholder="000000"
                       class="${properties.kcInputClass!}"
                       style="
                           font-size: 2em;
                           letter-spacing: 0.6em;
                           text-align: center;
                           font-weight: bold;
                       " />
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       type="submit"
                       value="Verify" />
            </div>

            <#-- Secondary options -->
            <div style="display:flex; justify-content:space-between; margin-top:12px; font-size:0.9em;">

                <#-- Resend code -->
                <button type="submit"
                        name="form_action"
                        value="resend"
                        style="background:none; border:none; color:#1a73e8; cursor:pointer; padding:0; text-decoration:underline;">
                    Resend code
                </button>

                <#-- Change method -->
                <button type="submit"
                        name="form_action"
                        value="change_method"
                        style="background:none; border:none; color:#666; cursor:pointer; padding:0; text-decoration:underline;">
                    Use another method
                </button>

            </div>

        </form>
    </#if>

</@layout.registrationLayout>
