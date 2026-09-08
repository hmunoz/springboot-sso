# Plan de Implementación: Plugin SPI para Keycloak + RabbitMQ

## Fase 1: Desarrollo del Plugin SPI (Keycloak)

**Objetivo:** Crear un módulo Java 21 independiente que intercepte los eventos de Keycloak y los envíe a RabbitMQ.

### 1.1 Configurar el proyecto Maven

- Configurar Java 21 como versión de compilación.
- Dependencias principales:
  - `org.keycloak:keycloak-server-spi` (scope `provided`)
  - `org.keycloak:keycloak-server-spi-private` (scope `provided`)
  - `com.rabbitmq:amqp-client` (scope `compile`)
  - `com.fasterxml.jackson.core:jackson-databind` (scope `provided`, ya presente en el runtime de Keycloak)
- Configurar el plugin `maven-shade-plugin` para empaquetar el cliente AMQP dentro del JAR final sin incluir las dependencias de Keycloak.

### 1.2 Implementar las clases SPI

**`RabbitMQEventListenerProviderFactory`**

- Lee la configuración de RabbitMQ por variables de entorno: `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASS`, `RABBITMQ_EXCHANGE`.
- Gestiona la conexión/canal único en el inicio del contenedor y limpia los recursos al detenerse.

**`RabbitMQEventListenerProvider`**

- `onEvent(Event event)`: Captura acciones de usuario (`REGISTER`, `LOGIN`, `UPDATE_PASSWORD`, etc.) y publica con routing key `keycloak.user.<event_type>`.
- `onEvent(AdminEvent event, boolean includeRepresentation)`: Captura acciones de administración (creación/edición de usuarios vía consola o API) y publica con routing key `keycloak.admin.<resource_type>.<operation_type>`.
- **Serialización segura:** Manejar excepciones en la publicación para evitar bloquear la transacción de Keycloak si RabbitMQ experimenta latencia transitoria.

### 1.3 Registrar el servicio SPI

Crear el descriptor:

```text
src/main/resources/META-INF/services/org.keycloak.events.EventListenerProviderFactory
```

Apuntando al nombre completo de la Factory.

---

## Fase 2: Entorno Local de Prueba (Docker Compose)

**Objetivo:** Levantar la infraestructura necesaria para la prueba de concepto (PoC).

### 2.1 Definir `Dockerfile` para Keycloak 26

- Utilizar la imagen base `keycloak/keycloak:26.0`.
- Copiar el JAR generado (`*-shaded.jar`) dentro del directorio `/opt/keycloak/providers/`.
- Ejecutar la optimización de Quarkus: `RUN /opt/keycloak/bin/kc.sh build`.

### 2.2 Definir `docker-compose.yml` integrando

- **PostgreSQL:** Base de datos para Keycloak.
- **RabbitMQ:** Imagen `rabbitmq:3-management` (para auditar colas y mensajes desde su UI web en el puerto `15672`).
- **Keycloak 26:** Conectado a la base de datos y a RabbitMQ, configurando las variables de entorno para el plugin y habilitando el modo de desarrollo (`start-dev`).

---

## Fase 3: Activación en Keycloak

**Objetivo:** Configurar el Realm para que emita los eventos hacia el plugin.

1. Iniciar sesión en la consola de administración de Keycloak (`http://localhost:8080`).
2. Seleccionar el Realm de prueba.
3. Ir a **Realm Settings** > solapa **Events** > subsolapa **Event Listeners**.
4. Agregar el identificador del plugin (ej. `rabbitmq-event-listener`) a la lista de listeners activos.
5. Habilitar **Save Events** para eventos de usuario y **Save Admin Events** (activando _Include Representation_ si se requiere el payload completo del usuario creado/modificado).
6. Guardar cambios.

---

## Fase 4: Consumo desde Spring Boot

**Objetivo:** Suscribirse a los tópicos y procesar los eventos en la aplicación backend.

### 4.1 Configurar dependencias y propiedades

- Agregar `spring-boot-starter-amqp`.
- Definir host, credenciales y virtual host en `application.yml`.

### 4.2 Declarar topología AMQP

Configurar un `@Configuration` con:

- Un exchange de tipo **Topic** (`keycloak-events`).
- Una cola durable (ej. `keycloak.users.sync`).
- El binding con la routing key deseada (ej. `keycloak.user.#` o `keycloak.admin.USER.#`).

### 4.3 Implementar el Listener

- Crear un `@Component` con un método anotado con `@RabbitListener(queues = "keycloak.users.sync")`.
- Parsear el JSON recibido y ejecutar la lógica de sincronización o notificación correspondiente.

---

## Fase 5: Validación y Casos de Prueba

| Caso de Prueba | Acción en Keycloak | Resultado Esperado en RabbitMQ / Spring Boot |
| --- | --- | --- |
| **Registro de usuario** | Crear un usuario desde el formulario de registro o consola de admin. | Se encola mensaje en RabbitMQ; Spring Boot loguea la recepción con los datos del nuevo usuario. |
| **Login / Logout** | Iniciar y cerrar sesión con un usuario existente. | Mensaje publicado con routing key `keycloak.user.login` / `logout`. |
| **Actualización de atributos** | Modificar nombre, apellido o atributos custom en Keycloak. | Evento administrativo recibido en Spring Boot para reflejar el cambio en la base local. |
| **Resiliencia ante caídas** | Detener temporalmente RabbitMQ mientras Keycloak opera. | Keycloak no debe fallar críticamente en el login; debe registrar error en logs y recuperarse al reiniciar el broker. |