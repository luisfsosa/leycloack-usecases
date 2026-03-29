<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true displayInfo=true; section>

    <#if section = "header">
        Ingresa tu codigo

    <#elseif section = "info">
        <#-- Mensaje debajo del título: donde se envió el código -->
        <#if otp_method?? && otp_method == "email">
            Enviamos un codigo de 6 digitos a
            <strong>${destination!"tu email"}</strong>
        <#else>
            Enviamos un codigo de 6 digitos al numero
            <strong>${destination!"tu telefono"}</strong>
            (SMS simulado — revisa los logs de Keycloak)
        </#if>

    <#elseif section = "form">
        <form id="altana-enter-otp-form"
              action="${url.loginAction}"
              method="post">

            <input type="hidden" name="form_action" value="verify_otp" />

            <div class="${properties.kcFormGroupClass!}">
                <label for="otp_code" class="${properties.kcLabelClass!}">
                    Codigo de verificacion
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
                       value="Verificar" />
            </div>

            <#-- Opciones secundarias -->
            <div style="display:flex; justify-content:space-between; margin-top:12px; font-size:0.9em;">

                <#-- Reenviar código -->
                <button type="submit"
                        name="form_action"
                        value="resend"
                        style="background:none; border:none; color:#1a73e8; cursor:pointer; padding:0; text-decoration:underline;">
                    Reenviar codigo
                </button>

                <#-- Cambiar método -->
                <button type="submit"
                        name="form_action"
                        value="change_method"
                        style="background:none; border:none; color:#666; cursor:pointer; padding:0; text-decoration:underline;">
                    Usar otro metodo
                </button>

            </div>

        </form>
    </#if>

</@layout.registrationLayout>
