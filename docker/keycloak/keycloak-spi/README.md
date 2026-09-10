# Keycloak RabbitMQ Event Listener SPI

Custom Keycloak Event Listener SPI that publishes realm authentication and administrative events to RabbitMQ over AMQP 0-9-1 using a topic exchange.

Replaces the legacy, deprecated `keycloak-to-rabbit-3.0.5.jar` with a native implementation compiled for **Java 21** and compatible with **Keycloak 26.x**.

---

## 1. Architecture & AMQP Topology

### Exchange
- **Default Exchange**: `amq.topic` (configurable via `RABBITMQ_EXCHANGE`).
- **Exchange Type**: `topic`

### Routing Key Conventions

Events are routed using a hierarchical, dot-separated naming convention:

| Event Category | Routing Key Pattern | Examples | Description |
|---|---|---|---|
| **Admin Events** | `keycloak.admin.<RESOURCE_TYPE>.<OPERATION_TYPE>` | `keycloak.admin.USER.CREATE`<br>`keycloak.admin.USER.UPDATE`<br>`keycloak.admin.USER.DELETE` | Triggered when administrators or the REST API modify users, roles, or clients. |
| **User Events** | `keycloak.user.<EVENT_TYPE>` | `keycloak.user.REGISTER`<br>`keycloak.user.LOGIN`<br>`keycloak.user.LOGOUT` | Triggered by user-facing actions in Keycloak forms. |

---

## 2. Event Filtering & Queue Subscriptions

In an Event-Driven Architecture (EDA) using RabbitMQ, **filtering happens at the broker level via Queue Bindings**.

### Recommended Subscription (User Lifecycle Only)

If you only want to receive user creation, modification, and deletion (ignoring logins, logouts, client changes, etc.), bind your queue to `amq.topic` with the following routing keys:

```text
keycloak.admin.USER.*
```

And optionally, if public user self-registration is enabled:

```text
keycloak.user.REGISTER
```

### Routing Key Wildcards Reference
- `*` (star) matches exactly **one** word.
  - `keycloak.admin.USER.*` matches `CREATE`, `UPDATE`, `DELETE`.
  - Does **not** match `keycloak.admin.CLIENT.CREATE` or `keycloak.user.LOGIN`.
- `#` (hash) matches **zero or more** words.
  - `keycloak.#` matches every single event emitted by Keycloak.

---

## 3. Event Payloads

Events are serialized as UTF-8 JSON payloads with `deliveryMode: 2` (persistent).

### User Lifecycle Admin Event (`keycloak.admin.USER.CREATE`)

```json
{
  "resourceType": "USER",
  "operationType": "CREATE",
  "realmId": "videoclub",
  "resourcePath": "users/a1b2c3d4-...",
  "time": 1725800000000,
  "auth": {
    "realmId": "master",
    "clientId": "admin-cli",
    "userId": "admin-uuid",
    "ipAddress": "172.25.0.1"
  },
  "representation": {
    "username": "jdoe",
    "email": "jdoe@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "enabled": true
  }
}
```

> **Note**: `representation` is included only when `adminEventsDetailsEnabled: true` is configured in the realm.

### User Event (`keycloak.user.LOGIN`)

```json
{
  "type": "LOGIN",
  "realmId": "videoclub",
  "clientId": "web",
  "userId": "user-uuid",
  "ipAddress": "192.168.1.100",
  "time": 1725800000000,
  "details": {
    "auth_method": "openid-connect",
    "username": "jdoe"
  }
}
```

---

## 4. Configuration (Environment Variables)

The SPI reads connection parameters from environment variables defined in `docker/.env` and passed to the container in `docker/keycloak.yaml`:

| Variable | Default Value | Description |
|---|---|---|
| `RABBITMQ_HOST` | `localhost` | RabbitMQ container hostname or IP |
| `RABBITMQ_PORT` | `5672` | AMQP port |
| `RABBITMQ_USER` | `guest` | RabbitMQ username |
| `RABBITMQ_PASS` | `guest` | RabbitMQ password |
| `RABBITMQ_VHOST` | `/` | Virtual host |
| `RABBITMQ_EXCHANGE` | `amq.topic` | Target topic exchange name |

---

## 5. Keycloak Realm Configuration

The provider identifier is **`rabbitmq-event-listener`**.

For the SPI to be active, the realm configuration (`docker/keycloak/realm-export.json`) must contain:

```json
{
  "eventsEnabled": true,
  "eventsListeners": [
    "jboss-logging",
    "rabbitmq-event-listener"
  ],
  "adminEventsEnabled": true,
  "adminEventsDetailsEnabled": true
}
```

---

## 6. Build & Deployment

### Build the Shaded JAR

From the project root:

```bash
./mvnw clean package -f docker/keycloak/keycloak-spi/pom.xml
```

This generates `target/keycloak-spi-1.0.0.jar`, which shades the `amqp-client` library while excluding dependencies already provided by Keycloak runtime (`keycloak-core`, `jackson`, etc.).

### Docker Compose Mount

In `docker/keycloak.yaml`:

```yaml
volumes:
  - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
  - ./keycloak/keycloak-spi/target/keycloak-spi-1.0.0.jar:/opt/keycloak/providers/keycloak-spi.jar
```
