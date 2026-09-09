<#import "template.ftl" as layout>
<@layout.emailLayout>
  <h2 style="margin: 0 0 16px 0; font-size: 20px; font-weight: 700; color: #1a202c; letter-spacing: -0.02em;">
    Prueba de Conexión SMTP
  </h2>

  <p style="margin: 0 0 16px 0; font-size: 15px; color: #4a5568; line-height: 1.6;">
    Este es un mensaje de prueba enviado desde <strong>${realmName!'VideoClub SSO'}</strong> para validar la integración con el servidor de correo.
  </p>

  <div style="background-color: #f0fff4; border: 1px solid #9ae6b4; border-radius: 6px; padding: 14px 18px; margin: 24px 0; color: #22543d; font-size: 14px;">
    ✅ <strong>Conexión exitosa:</strong> El servicio SMTP se encuentra activo, autenticado y listo para enviar notificaciones de seguridad a los usuarios.
  </div>
</@layout.emailLayout>
