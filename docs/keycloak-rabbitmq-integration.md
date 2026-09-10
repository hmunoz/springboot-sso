# Integración Keycloak, RabbitMQ y Anti-Corruption Layer (ACL)

Este documento detalla la arquitectura de integración reactiva basada en eventos entre **Keycloak**, el broker de mensajería **RabbitMQ** y la capa de adaptación (**Anti-Corruption Layer**) de la plataforma VideoClub.

---

## 1. Arquitectura de Integración

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Administrador
    participant KC as Keycloak (IdP)
    participant SPI as Keycloak RabbitMQ SPI
    participant RMQ_KC as Topic Exchange keycloak.events
    participant ACL as Anti-Corruption Layer (KeycloakEventListener)
    participant RMQ_DOM as Topic Exchange videoclub.events
    participant DOM as SocioEventListener

    Admin->>KC: Crea o modifica usuario
    KC->>KC: Commit en datastore de Keycloak
    KC->>SPI: onEvent(AdminEvent)
    SPI->>RMQ_KC: Publish con keycloak.admin.USER.CREATE
    RMQ_KC->>ACL: Consume de cola keycloak-events
    Note over ACL: Normaliza snake_case vs camelCase<br/>Construye Event canónico
    ACL->>RMQ_DOM: Publish Socio.CREATE (espera Publisher Confirm)
    RMQ_DOM->>DOM: Consume de cola socio.events.queue
    DOM->>DOM: Upsert idempotente en PostgreSQL
```

---

## 2. Plugin SPI para Keycloak (`keycloak-spi`)

El plugin reside en `docker/keycloak/keycloak-spi` y compila como un JAR embebido en el classpath de Keycloak (`/opt/keycloak/providers/keycloak-spi.jar`).

### Proveedores Implementados
* **`RabbitMQEventListenerProviderFactory`:**
  * Lee la configuración del broker vía variables de entorno (`RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASS`, `RABBITMQ_EXCHANGE`, `RABBITMQ_VHOST`).
  * Gestiona la conexión TCP persistente y el canal único con RabbitMQ.
  * Declara el exchange durable `keycloak.events` al inicializarse.
* **`RabbitMQEventListenerProvider`:**
  * `onEvent(Event event)`: Captura acciones de usuario final (`REGISTER`, `LOGIN`, `UPDATE_PASSWORD`) y publica con routing key `keycloak.user.<event_type>`.
  * `onEvent(AdminEvent event, boolean includeRepresentation)`: Captura acciones administrativas y publica con routing key `keycloak.admin.<resource_type>.<operation_type>`.
  * Serialización JSON segura: El payload de Keycloak se encapsula en JSON; si RabbitMQ no está disponible, captura la excepción y evita abortar la transacción de Keycloak.

---

## 3. Topología de Exchanges en RabbitMQ

El sistema utiliza una **topología de dos niveles** para aislar el contexto de infraestructura del contexto de negocio:

| Exchange | Tipo | Durabilidad | Rol Arquitectónico |
| :--- | :--- | :--- | :--- |
| **`keycloak.events`** | Topic | Durable | **Infraestructura:** Transporta eventos crudos de Keycloak (`keycloak.user.#`, `keycloak.admin.USER.*`). |
| **`videoclub.events`** | Topic | Durable | **Negocio:** Transporta eventos canónicos del dominio (`Socio.#`). |
| **`videoclub.events.dlx`** | Direct / Fanout | Durable | **Dead Letter:** Recepciona mensajes rechazados tras agotar reintentos. |

### Matriz de Colas y Bindings

| Cola | Exchange Origen | Routing Key Pattern | Dead Letter Destino |
| :--- | :--- | :--- | :--- |
| `keycloak-events` | `keycloak.events` | `keycloak.user.#`<br/>`keycloak.admin.USER.*` | — |
| `socio.events.queue` | `videoclub.events` | `Socio.#` | `videoclub.events.dlx` (`socio.dlq`) |
| `socio.events.dlq` | `videoclub.events.dlx` | `socio.dlq` | — |

---

## 4. Anti-Corruption Layer (ACL) (`KeycloakEventListener`)

El componente `ar.unrn.video.service.KeycloakEventListener` actúa como frontera de traducción entre ambos mundos:

### Normalización de Formatos de Keycloak
* **Eventos de Usuario (`REGISTER`):** Los atributos del usuario vienen en el mapa `details` en *snake_case* (`username`, `email`, `first_name`, `last_name`).
* **Eventos Administrativos (`USER.CREATE`, `USER.UPDATE`):** Los atributos vienen en el objeto `representation` en *camelCase* (`username`, `email`, `firstName`, `lastName`).
* **Eventos de Eliminación (`USER.DELETE`):** No contienen `representation`. El ACL extrae el `userId` desde el `resourcePath` (`users/{id}`) mediante `KeycloakEvent.extractTargetUserId()`.

### Garantías de Publicación
Al reenviar el evento hacia `videoclub.events`, `MessagePublisher` utiliza:
* `publisher-confirm-type: correlated`
* Espera sincrónica con timeout de la confirmación del broker (`correlation.getFuture().get(...)`).
* Si el broker rechaza el mensaje, se lanza una `AmqpException` para que el mensaje original de `keycloak.events` no sea confirmado y pueda ser reprocesado.

---

## 5. Tabla de Configuración Unificada

Para prevenir fallos silenciosos por desalineación de nombres de exchanges, todos los componentes del sistema apuntan a `keycloak.events`:

| Archivo / Componente | Propiedad / Variable | Valor Configurado |
| :--- | :--- | :--- |
| `RabbitMQEventListenerProviderFactory.java` | Fallback de entorno | `keycloak.events` |
| `docker/keycloak.yaml` | `RABBITMQ_EXCHANGE` | `keycloak.events` |
| `application.yml` | `videoclub.keycloak.rabbitmq.exchange` | `${RABBITMQ_EXCHANGE:keycloak.events}` |
| `RabbitMQConfig.java` | `@Value` inyección | `@Value("${keycloak.rabbitmq.exchange:keycloak.events}")` |
| `.env` / `docker/.env` | Variable de entorno | `RABBITMQ_EXCHANGE=keycloak.events` |
| `.env.example` | Plantilla de variables | `RABBITMQ_EXCHANGE=keycloak.events` |
