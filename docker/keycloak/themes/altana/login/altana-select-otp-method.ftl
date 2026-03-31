<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=true; section>

    <#if section = "header">
        Two-step verification

    <#elseif section = "form">
        <form id="altana-select-method-form"
              action="${url.loginAction}"
              method="post">

            <div class="${properties.kcFormGroupClass!}">
                <p style="margin-bottom: 16px; color: #555;">
                    Choose how you want to receive your verification code:
                </p>

                <#-- Email option -->
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
                    <strong>Email</strong>
                    <span style="display:block; color:#888; font-size:0.85em; margin-top:4px; margin-left:22px;">
                        We will send the code to your registered email address
                    </span>
                </label>

                <#-- SMS option -->
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
                        We will send the code to your registered phone number
                    </span>
                </label>
            </div>

            <input type="hidden" name="form_action" value="select_method" />

            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       type="submit"
                       value="Send code" />
            </div>

        </form>
    </#if>

</@layout.registrationLayout>
