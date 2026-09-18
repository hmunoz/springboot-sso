# Infraestructura y Configuración de Keycloak — VideoClub

Esta carpeta contiene la configuración integral del servidor de identidad (**Keycloak 26.x**) utilizado en la plataforma VideoClub como **Identity Provider (IdP)** OpenID Connect / OAuth 2.0.

Aquí se gestiona la configuración declarativa del realm, los temas visuales y de correo personalizados, el entorno SMTP de pruebas y el plugin SPI para publicación reactiva de eventos hacia RabbitMQ.

---

## 🗺️ Mapa de Componentes

```text
docker/keycloak/
├── realm-export.json          # Configuración declarativa completa del Realm (Realm as Code)
├── themes/                    # Temas personalizados para login y correos
│   └── videoclub/
│       ├── login/             # Estilos CSS e i18n para la pantalla de inicio de sesión
│       └── email/             # Plantillas FreeMarker (.ftl) y diseño HTML de emails
├── keycloak-spi/              # Código fuente Maven del Event Listener SPI (Java 21)
│   ├── pom.xml
│   ├── src/
│   └── README.md              # Documentación técnica avanzada del SPI
└── README.md                  # Este documento (guía para estudiantes y desarrolladores)
```

---

## 1. Configuración Declarativa: `realm-export.json` (Realm as Code)

En lugar de configurar manualmente el servidor en cada entorno o reinstalación, Keycloak se inicializa de forma **declarativa y reproducible**.

### ¿Cómo se importa al arrancar?

En `docker/keycloak.yaml`, el contenedor monta el archivo JSON y arranca con el flag `--import-realm`:

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.7
    command: start-dev --import-realm
    volumes:
      - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
```

Keycloak detecta el archivo en `/opt/keycloak/data/import/realm-export.json` durante el boot y provisiona el realm `videoclub` únicamente si no existe previamente en la base de datos.

### ¿Qué define este archivo?

1. **Realm:** `videoclub`, con configuración de sesiones, tokens de acceso y tiempos de expiración.
2. **Clientes OIDC (Clients):**
   - `videoclub-backend`: Cliente **confidencial** con flujo *Client Credentials / Service Account*. Permite que los microservicios Spring Boot interactúen con la API Admin de Keycloak (gestión de usuarios y socios).
   - `videoclub-frontend`: Cliente **público** para la SPA en React 19, configurado con **Authorization Code Flow + PKCE** (Proof Key for Code Exchange) y redirecciones autorizadas (`http://localhost:5173/*`).
3. **Roles y Grupos:** Roles de sistema como `ADMIN` y `USER`, asignados a scopes y tokens.
4. **Usuarios semilla:** Cuentas iniciales con contraseñas preconfiguradas para desarrollo y testing.
5. **Configuración de Temas y Eventos:** Asignación de los temas visuales propios y activación del listener de RabbitMQ.

### 💡 Guía para estudiantes: ¿Cómo persistir cambios hechos en la consola web?

Si ingresás a la consola de administración (`http://localhost:9090`) y creás un cliente, un usuario o modificás una política, **esos cambios se perderán si el contenedor o su volumen se destruyen**, a menos que actualices `realm-export.json`.

Para exportar el estado actual del realm y versionarlo en Git:

#### Opción A: Desde la Consola Web (Recomendada)
1. Entrá a `http://localhost:9090` con el usuario admin.
2. Seleccioná el realm **videoclub** en el menú desplegable superior izquierdo.
3. Andá a **Realm Settings** > solapa **Action** (arriba a la derecha) > **Partial export**.
4. Marcá *Export groups and roles* y *Export clients*, y descargá el archivo.
5. Si querés el export completo con usuarios y credenciales cifradas, podés usar la opción B por CLI.

#### Opción B: Exportación completa vía CLI
Ejecutá el exportador nativo de Keycloak contra la base actual:
```bash
docker exec -it video-keycloak /opt/keycloak/bin/kc.sh export \
  --dir /tmp/export \
  --realm videoclub \
  --users realm_file

# Copiás el archivo resultante sobre tu archivo del repositorio:
docker cp video-keycloak:/tmp/export/videoclub-realm.json docker/keycloak/realm-export.json
```

---

## 2. Temas Personalizados (`themes/videoclub`)

Keycloak permite personalizar todas sus interfaces web mediante un motor de plantillas **FreeMarker** (`.ftl`), hojas de estilo CSS e internacionalización por archivos de propiedades (`.properties`).

El volumen montado en `docker/keycloak.yaml` vincula los temas locales:
```yaml
volumes:
  - ./keycloak/themes:/opt/keycloak/themes
```

### 2.1. Tema de Inicio de Sesión (`login`)

Ubicación: `docker/keycloak/themes/videoclub/login/`

- **Herencia (`theme.properties`):**
  ```properties
  parent=keycloak.v2
  import=common/keycloak
  styles=css/styles.css css/login.css
  darkMode=false
  ```
  Hereda del diseño moderno `keycloak.v2` y añade la hoja de estilos propia `login.css`.
- **Hojas de estilo (`resources/css/login.css`):** Define colores, fuentes, tarjetas centradas y estética alineada a la aplicación VideoClub.
- **Internacionalización (`messages/`):**
  - `messages_es.properties`: Textos y validaciones en español.
  - `messages_en.properties`: Textos en inglés.

### 2.2. Tema de Correos Electrónicos (`email`)

Ubicación: `docker/keycloak/themes/videoclub/email/`

Cuando Keycloak envía correos transaccionales (confirmación de cuenta, reseteo de clave, notificaciones de seguridad), utiliza este tema.

- **Plantilla base (`html/template.ftl`):** Diseña un layout HTML responsivo con encabezado corporativo (`📼 VideoClub SSO`), tipografía adaptable y footer legal unificado.
- **Acciones específicas:**
  - `email-verification.ftl`: Correo con enlace seguro para validar el correo al registrarse.
  - `password-reset.ftl`: Correo con enlace temporal para restablecer la contraseña.
  - `executeActions.ftl`: Enlace para acciones requeridas administrativas (e.g. configurar MFA o actualizar perfil).
  - `email-test.ftl`: Plantilla para el botón "Test connection" de la consola de administración.
- **Textos de asunto y cuerpo:** Definidos en `messages/messages_es.properties` y `messages/messages_en.properties`.

---

## 3. Servidor SMTP para Desarrollo: Mailhog

En un entorno de desarrollo o laboratorio no es viable (ni seguro) utilizar un servidor SMTP real con credenciales personales o corporativas. Para resolver esto, la plataforma incluye **Mailhog** en `docker/email.yaml`.

```yaml
services:
  mailhog:
    image: mailhog/mailhog
    container_name: 'mailhog'
    ports:
      - "1025:1025"   # Puerto SMTP estándar de Mailhog
      - "8025:8025"   # Web UI interactiva
```

### Configuración en Keycloak (`realm-export.json`)

Keycloak se conecta a Mailhog de forma interna mediante la red de Docker:

```json
"smtpServer": {
  "host": "mailhog",
  "port": "1025",
  "from": "no-reply@videoclub.unrn.edu.ar",
  "fromDisplayName": "VideoClub",
  "replyTo": "soporte@videoclub.unrn.edu.ar",
  "auth": "false",
  "ssl": "false",
  "starttls": "false"
}
```

### 📬 ¿Cómo probarlo en el laboratorio?

1. Levantá los servicios con `docker compose -f docker/services.yaml up -d` (o `docker/email.yaml`).
2. Abrí tu navegador en **`http://localhost:8025`**. Verás la bandeja de entrada de Mailhog.
3. En la pantalla de login de VideoClub (`http://localhost:9090` o vía frontend), hacé clic en **"¿Olvidaste tu contraseña?"** e ingresá el correo de un usuario (por ejemplo `user@test.com`).
4. Volvé a Mailhog: verás instantáneamente el correo interceptado con el diseño visual del tema `videoclub`, sin haber salido a Internet ni enviado spam a destinatarios reales. Podés hacer clic en el enlace del correo para completar el reseteo.

---

## 4. Keycloak RabbitMQ Event Listener SPI (`keycloak-spi`)

Keycloak opera como el núcleo de seguridad, pero el resto de los microservicios (`membership-service`) necesitan enterarse cuando suceden eventos de identidad (por ejemplo, cuando un usuario se registra para crear automáticamente su ficha de socio).

En lugar de acoplar sincrónicamente a Keycloak mediante webhooks HTTP frágiles, se desarrolló un **SPI (Service Provider Interface)** nativo en Java 21:

```text
[Usuario/Admin] ──► [Keycloak 26] ──(SPI)──► [RabbitMQ Exchange: keycloak.events]
                                                      │
                                                      ├──► Queue: membership-service
                                                      └──► Queue: auditoria-service
```

### Características Principales:
- **Compilación Nativa Java 21:** Construido en `docker/keycloak/keycloak-spi/` excluyendo las librerías provistas por el runtime de Keycloak (`keycloak-core`, Jackson) e incluyendo en modo *shaded* el cliente AMQP (`amqp-client`).
- **Exchange:** Emite eventos persistentes a un topic exchange durable (`keycloak.events`).
- **Routing Keys Jerárquicas:**
  - `keycloak.admin.USER.CREATE`, `keycloak.admin.USER.UPDATE`, `keycloak.admin.USER.DELETE`
  - `keycloak.user.LOGIN`, `keycloak.user.REGISTER`, `keycloak.user.LOGOUT`

Para ver los detalles de los payloads JSON, wildcards de binding y configuración del código Java, consultá **[README técnico de keycloak-spi](keycloak-spi/README.md)**.

---

## 5. Resumen de Puertos y URLs Útiles

| Servicio | URL / Puerto | Credenciales por Defecto | Propósito |
| :--- | :--- | :--- | :--- |
| **Keycloak Admin** | `http://localhost:9090` | `admin` / `admin` (según `docker/.env`) | Gestión de usuarios, clientes, roles y configuración |
| **Mailhog Web UI** | `http://localhost:8025` | *(Sin autenticación)* | Bandeja de entrada para inspeccionar correos generados |
| **Mailhog SMTP** | `localhost:1025` | *(Sin autenticación)* | Puerto de envío de correo utilizado por Keycloak |
| **RabbitMQ Management** | `http://localhost:15672` | `guest` / `guest` | Inspección de colas, bindings y exchange `keycloak.events` |
