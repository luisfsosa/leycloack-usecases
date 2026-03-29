<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true; section>

    <#if section = "header">
        Verificacion en dos pasos

    <#elseif section = "form">
        <form id="altana-select-method-form"
              action="${url.loginAction}"
              method="post">

            <div class="${properties.kcFormGroupClass!}">
                <p style="margin-bottom: 16px; color: #555;">
                    Elige como quieres recibir tu codigo de verificacion:
                </p>

                <#-- Opcion Email -->
                <label style="
                    display: block;
                    padding: 14px 16px;
                    border: 2px solid #dde3f0;
                    border-radius: 8px;
                    cursor: pointer;
                    margin-bottom: 10px;
                    transition: border-color 0.2s;
                ">
                    <input type="radio"
                           name="otp_method"
                           value="email"
                           checked
                           style="margin-right: 10px;" />
                    <strong>Correo electronico</strong>
                    <span style="display:block; color:#888; font-size:0.85em; margin-top:4px; margin-left:22px;">
                        Enviamos el codigo a tu email registrado
                    </span>
                </label>

                <#-- Opcion SMS -->
                <label style="
                    display: block;
                    padding: 14px 16px;
                    border: 2px solid #dde3f0;
                    border-radius: 8px;
                    cursor: pointer;
                    margin-bottom: 20px;
                    transition: border-color 0.2s;
                ">
                    <input type="radio"
                           name="otp_method"
                           value="sms"
                           style="margin-right: 10px;" />
                    <strong>SMS</strong>
                    <span style="display:block; color:#888; font-size:0.85em; margin-top:4px; margin-left:22px;">
                        Enviamos el codigo a tu numero de telefono registrado
                    </span>
                </label>
            </div>

            <input type="hidden" name="form_action" value="select_method" />

            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       type="submit"
                       value="Enviar codigo" />
            </div>

        </form>
    </#if>

</@layout.registrationLayout>
