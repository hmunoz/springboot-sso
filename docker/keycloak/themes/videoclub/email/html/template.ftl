<#macro emailLayout>
<!DOCTYPE html>
<html lang="${locale.language!'es'}" dir="${(ltr)?then('ltr','rtl')}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${realmName!'VideoClub SSO'}</title>
</head>
<body style="margin: 0; padding: 0; background-color: #f7fafc; font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a202c; -webkit-font-smoothing: antialiased;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color: #f7fafc; padding: 36px 16px;">
    <tr>
      <td align="center">
        <!-- Wrapper container -->
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width: 520px; margin: 0 auto;">
          <!-- Brand Badge Header -->
          <tr>
            <td align="center" style="padding-bottom: 24px;">
              <div style="display: inline-block; background-color: #1a202c; border: 1px solid #2d3748; border-radius: 8px; padding: 8px 18px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);">
                <span style="font-size: 18px; vertical-align: middle;">📼</span>
                <span style="font-size: 15px; font-weight: 700; color: #ffffff; letter-spacing: -0.025em; vertical-align: middle; margin-left: 6px;">VideoClub SSO</span>
              </div>
            </td>
          </tr>
          <!-- Main Content Card -->
          <tr>
            <td style="background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 12px; box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.08), 0 8px 10px -6px rgba(0, 0, 0, 0.04); padding: 36px 32px; border-top: 4px solid #1a202c;">
              <div style="font-size: 15px; line-height: 1.6; color: #2d3748;">
                <#nested>
              </div>
            </td>
          </tr>
          <!-- Footer Disclaimers -->
          <tr>
            <td align="center" style="padding-top: 24px; font-size: 12px; color: #a0aec0; line-height: 1.5;">
              <p style="margin: 0;">Mensaje automático enviado por el sistema de autenticación de <strong>VideoClub SSO</strong>.</p>
              <p style="margin: 4px 0 0 0;">Si no reconocés esta actividad, podés desestimar este mensaje de forma segura.</p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
</#macro>
