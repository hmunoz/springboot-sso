<#import "template.ftl" as layout>
<@layout.emailLayout>
  <h2 style="margin: 0 0 16px 0; font-size: 20px; font-weight: 700; color: #1a202c; letter-spacing: -0.02em;">
    ${msg("emailVerificationTitle")}
  </h2>

  <p style="margin: 0 0 16px 0; font-size: 15px; color: #4a5568; line-height: 1.6;">
    ${kcSanitize(msg("emailVerificationIntro", realmName!'VideoClub SSO'))?no_esc}
  </p>

  <p style="margin: 0 0 24px 0; font-size: 15px; color: #4a5568; line-height: 1.6;">
    ${msg("emailVerificationInstruction")}
  </p>

  <div style="text-align: center; margin: 32px 0;">
    <a href="${link}" style="background-color: #3182ce; color: #ffffff; text-decoration: none; font-size: 15px; font-weight: 600; padding: 13px 32px; border-radius: 6px; display: inline-block; box-shadow: 0 2px 4px rgba(49, 130, 206, 0.25);">
      ${msg("emailVerificationBtn")}
    </a>
  </div>

  <div style="background-color: #f7fafc; border-left: 3px solid #3182ce; border-radius: 4px; padding: 12px 16px; margin: 24px 0;">
    <p style="margin: 0; font-size: 13px; color: #718096; line-height: 1.5;">
      ⏱️ ${kcSanitize(msg("emailVerificationExpiry", linkExpirationFormatter(linkExpiration)))?no_esc}
    </p>
  </div>

  <p style="margin: 24px 0 6px 0; font-size: 12px; color: #a0aec0;">
    Si el botón no funciona, copiá y pegá el siguiente enlace en tu navegador:
  </p>
  <p style="margin: 0; font-size: 12px; color: #3182ce; word-break: break-all; line-height: 1.4;">
    <a href="${link}" style="color: #3182ce;">${link}</a>
  </p>
</@layout.emailLayout>
