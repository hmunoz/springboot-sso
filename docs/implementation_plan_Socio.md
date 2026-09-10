# Sincronización de Entidad Socio con Keycloak vía RabbitMQ

Implementación de la entidad de negocio `Socio` vinculada a Keycloak (`keycloakId`, `email`), sincronizada automáticamente a través de eventos de dominio en un bus de RabbitMQ (`videoclub.events`). La arquitectura desacopla el adaptador de eventos de Keycloak del dominio de Socios para que pueda evolucionar o migrarse a un microservicio independiente, exponiendo una API protegida para administradores y una nueva vista en el frontend React.

## User Review Required

> [!IMPORTANT]
> **Estrategia de Baja (Soft Delete vs Hard Delete)**:
> Cuando un usuario se elimina en Keycloak (`keycloak.admin.USER.DELETE`), el plan implementa **Baja Lógica (Soft Delete)** (`activo = false`, `fechaBaja = LocalDateTime.now()`). Esto preserva la integridad histórica de alquileres o registros que los alumnos agregarán más adelante.

> [!NOTE]
> **Topología de Exchanges**:
> Separamos dos exchanges en RabbitMQ:
> 1. `amq.topic`: Exchange de infraestructura donde el SPI de Keycloak publica eventos técnicos (`keycloak.user.*`, `keycloak.admin.USER.*`).
> 2. `videoclub.events`: Exchange de negocio (Topic) donde se publican eventos canónicos de dominio (`Socio.CREATE`, `Socio.UPDATE`, `Socio.DELETE`). El consumidor de `Socio` solo conoce este exchange, replicando el patrón del proyecto de referencia `amqp-keycloak-rabbit`.
>
> El salto por el broker es intencional aunque productor y consumidor vivan hoy en el mismo proceso: es lo que permite extraer el Bounded Context de Socios a un servicio aparte sin tocar el ACL. El costo es entrega *at-least-once* sin transacción compartida, y por eso **toda operación del consumidor debe ser idempotente** (ver §3).

> [!WARNING]
> **Autorización: los grupos NO son authorities en el backend**
> `KeycloakGrantedAuthoritiesConverter` mapea únicamente `realm_access.roles` y `resource_access.*.roles`. El claim `groups` **no se convierte en authority**, por lo que `hasAuthority('administrador')` evalúa siempre a `false` en Spring Security.
> El grupo `administrador` (`/videoclub-default/administrador`) es identidad; la autorización se decide con client roles de grano fino. Por eso este plan crea el rol `socio-permission-read` en vez de chequear el grupo. En el frontend sí se usa el grupo, porque `usePermissions` lee el claim `groups` directamente (mapper con `full.path: false`).

---

## Decisiones de Arquitectura y Patrones (ADR)

### ¿Por qué NO aplican SAGA ni Transactional Outbox en este caso?

- **SAGA (Transacciones Distribuidas con Compensación)**:
  - *Problema que resuelve*: Orquestar o coreografiar una transacción de negocio multi-servicio que requiere *rollback compensatorio* inverso si una etapa posterior falla (ej: si falla el cobro con tarjeta, revertir la reserva de stock).
  - *Por qué no aplica acá*: La sincronización entre Keycloak y el videoclub es **unidireccional y de consistencia eventual**. Si falla la persistencia de un `Socio` en la base de datos relacional de la aplicación, **jamás se debe disparar una compensación para borrar la cuenta del usuario en Keycloak**. Keycloak es el Identity Provider maestro corporativo. Si hay un fallo en el consumidor local, el mensaje se desvía a la Dead Letter Queue (DLQ) o se reintenta, y ante cualquier inconsistencia se ejecuta el proceso de reconciliación (Backfill). No existe un flujo transaccional bidireccional que justifique la complejidad de un orquestador o coreógrafo SAGA.

- **Transactional Outbox (Garantía contra Dual Write)**:
  - *Problema que resuelve*: Evitar inconsistencias cuando un mismo servicio debe guardar en su base de datos relacional y simultáneamente publicar un mensaje en un broker AMQP dentro de una única transacción atómica.
  - *Por qué no aplica acá*: El originador de la identidad es Keycloak (que guarda en su propio datastore y emite vía su SPI). Hay que analizar por separado los dos roles que cumple nuestro backend, porque el patrón se descarta por motivos distintos en cada uno:
    - **Como consumidor** (`SocioEventListener`): sólo escribe en PostgreSQL, **no publica ningún mensaje**. Al haber una única escritura, no existe dual write. La consistencia se garantiza con el ciclo de vida del mensaje en RabbitMQ: **no se envía el ACK al broker hasta que la transacción de PostgreSQL haga `COMMIT` con éxito**. Esto exige que el listener **no capture** las excepciones: si las traga, Spring AMQP ackea y el mensaje se pierde.
    - **Como productor** (`KeycloakEventListener`, el ACL): consume de `amq.topic` y publica en `videoclub.events`. Es mensaje entra → mensaje sale, **sin ninguna escritura a base de datos**. Tampoco hay dual write que coordinar. Lo que este rol necesita no es un outbox sino **`publisher-confirms` y orden de ACK correcto**: si se ackea el mensaje de Keycloak y después falla el publish al exchange de negocio, el evento se pierde sin dejar rastro (ver §2, `application.yml`).
  - *Cuándo pasaría a ser necesario*: el día que `SocioService` deba **guardar el `Socio` y además publicar su propio evento de dominio** (por ejemplo un `SocioDadoDeAlta` para un futuro Bounded Context de Alquileres). Ahí sí habría dos escrituras a coordinar en la misma transacción, y ahí sí entra Transactional Outbox.

### El dual write real está en Keycloak (y no lo podemos arreglar)

Descartar el outbox **no significa que el sistema no tenga un dual write**. Lo tiene, pero está aguas arriba, fuera de nuestro control: Keycloak commitea el usuario en su propia base y **después** publica el evento desde el SPI. Ese SPI descarta eventos en silencio si el broker no está disponible:

```java
// docker/keycloak/keycloak-spi/.../RabbitMQEventListenerProvider.java
if (channel == null || !channel.isOpen()) {
    LOG.warning("RabbitMQ channel not available — dropping event");
    return;  // el evento se perdió: el usuario existe y nadie se entera
}
```

El `catch` del método hace lo mismo: loguea `SEVERE` y continúa. Como no controlamos la transacción de Keycloak, **la pérdida de eventos es una posibilidad estructural del diseño**, no un bug a corregir.

De ahí se desprende la conclusión más importante de este ADR: **la reconciliación no es una comodidad, es el mecanismo compensatorio que sostiene la consistencia del sistema.** Ningún patrón de mensajería tapa este agujero; sólo un proceso que vuelva a comparar contra la fuente de verdad.

### Patrones de Diseño Seleccionados y Justificación

1. **Anti-Corruption Layer (ACL) (Domain-Driven Design)**:
   `KeycloakEventListener` aísla los eventos técnicos de bajo nivel y formatos específicos del proveedor de identidad (`keycloak.user.*`, `keycloak.admin.*`), traduciéndolos a eventos canónicos de negocio (`Event<String, SocioPayload>`). El Bounded Context de `Socio` permanece 100% puro y desacoplado del IdP.
2. **Idempotent Consumer**:
   Dado que RabbitMQ ofrece garantías de entrega *at-least-once*, el consumidor tolera recibir duplicados sin corromper el estado, apoyándose en la constraint de unicidad en `keycloakId` y lógica de *upsert*.
3. **Event-Carried State Transfer**:
   `SocioPayload` viaja con el estado completo del socio (`email`, `username`, `nombre`, `apellido`) en lugar de llevar sólo el `keycloakId`. La alternativa era *Event Notification*: mandar el identificador y que el consumidor consulte los datos a Keycloak. Se descartó porque acopla el procesamiento de cada evento a la disponibilidad del IdP. El costo asumido es que el payload puede quedar desactualizado respecto de Keycloak, y eso se compensa con el evento `UPDATE` y la reconciliación.
4. **Dead Letter Queue (DLQ)**:
   Aislamiento de mensajes envenenados o payloads corruptos en `socio.events.dlq` mediante `videoclub.events.dlx`, evitando bloqueos o loops de reencolado infinito. Se combina con reintentos previos (ver §2) para no mandar a la DLQ un fallo meramente transitorio.
5. **Reconciliation / Backfill Pattern**:
   `POST /api/socios/sync` compara contra la fuente de verdad y repara desfasajes. **No es un mecanismo complementario**: es la única red de seguridad frente a los eventos que Keycloak puede descartar (ver la sección anterior) y frente a la ventana en que el backend estuvo caído. En producción debería además correr de forma periódica y programada, no sólo bajo demanda.

> [!NOTE]
> **Orden de los mensajes y protección contra escrituras rancias**
> Con `concurrency = 1` en el listener, RabbitMQ preserva el orden dentro de la cola y la secuencia `CREATE → UPDATE → DELETE` se aplica correctamente. Si en el futuro se sube la concurrencia para ganar throughput, un `UPDATE` en vuelo puede aplicarse **después** de un `DELETE` y resucitar un socio dado de baja.
> Mitigación cuando llegue ese momento: persistir `lastEventTime` en `Socio` y descartar todo evento cuyo `KeycloakEvent.time` sea anterior al último aplicado (*event versioning* / optimistic offline lock). El campo `time` ya viaja en el evento de Keycloak.

---

## Proposed Changes

### 0. Configuración del Realm (`docker/keycloak`)

#### [MODIFY] [realm-export.json](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/keycloak/realm-export.json)
- Agregar el client role `socio-permission-read` al cliente `videoclub-frontend`, junto a los `movie-permission-*` y `user-permission-*` existentes.
- Asignarlo al grupo `/videoclub-default/administrador` (que hoy ya concentra `user-permission-read`, `user-permission-create` y los cuatro `movie-permission-*`).
- No asignarlo al grupo `/videoclub-default/cliente`.
- Editar el JSON preservando el formato existente (`indent=2`, `ensure_ascii=False`) para mantener el diff limpio.

> Ya están habilitados `eventsEnabled: true`, `adminEventsEnabled: true` y `adminEventsDetailsEnabled: true`. Esto último es lo que hace que el SPI reciba `includeRepresentation = true` y pueda publicar el bloque `representation` con `email`, `username`, `firstName` y `lastName`. **Si se desactiva, el alta de socios deja de tener datos y rompe.**

---

### 1. Modelo de Dominio y Persistencia (`springboot-sso`)

#### [NEW] [Socio.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/domain/Socio.java)
- Entidad JPA mapeada a PostgreSQL, siguiendo el patrón de `Movie` (Lombok `@Getter`/`@Setter`, secuencia `primary_sequence` con `allocationSize = 1` e `initialValue = 10000`).
- Campos:
  - `id`: `Long` (PK con secuencia `primary_sequence`)
  - `keycloakId`: `String` (`@Column(nullable = false, unique = true)`)
  - `email`: `String` (nullable — ver nota abajo)
  - `username`: `String`
  - `nombre`: `String`
  - `apellido`: `String`
  - `activo`: `Boolean` (por defecto `true`)
  - `fechaAlta`: `LocalDateTime` (no nulo)
  - `fechaBaja`: `LocalDateTime` (nullable)

> `email` se declara **nullable** a propósito. Keycloak permite crear usuarios sin email, y un `NOT NULL` acá convierte un dato faltante en una violación de constraint dentro del consumidor de RabbitMQ, que sin DLQ termina en requeue infinito. La validación de negocio, si hace falta, va en la capa de servicio.
>
> La constraint `unique` sobre `keycloakId` no es decorativa: es la única garantía real de idempotencia si dos consumidores procesan el mismo evento en paralelo.

#### [NEW] [SocioRepository.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/repos/SocioRepository.java)
- Métodos de consulta:
  - `Optional<Socio> findByKeycloakId(String keycloakId)`
  - `boolean existsByKeycloakId(String keycloakId)`
  - `List<Socio> findAllByOrderByFechaAltaDesc()`

#### [NEW] [SocioDTO.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/model/SocioDTO.java)
- Record para transferencia en la API REST y capa de presentación:
  - `id`, `keycloakId`, `email`, `username`, `nombre`, `apellido`, `activo`, `fechaAlta`, `fechaBaja`.

---

### 2. Mensajería de Dominio y Event-Driven Architecture (`springboot-sso`)

Basado en el diseño de `amqp-keycloak-rabbit`, creamos el envelope de eventos de dominio desacoplado de Keycloak.

#### [NEW] [Event.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/event/Event.java)
- Clase genérica `Event<K, T>` con enum `Type { CREATE, DELETE, UPDATE }`, `key`, `data` y cálculo de routing key (`Socio.CREATE`, `Socio.UPDATE`, `Socio.DELETE`).

#### [NEW] [SocioPayload.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/event/SocioPayload.java)
- Contrato del evento de negocio con los datos del socio (`keycloakId`, `email`, `username`, `nombre`, `apellido`).

#### [NEW] [MessagePublisher.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/event/MessagePublisher.java)
- Publicador que inyecta `AmqpTemplate` y envía `Event<K, T>` al exchange de negocio `videoclub.events`.

#### [MODIFY] [RabbitMQConfig.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/config/RabbitMQConfig.java)
- Declarar el TopicExchange de negocio: `videoclubEventsExchange` (`videoclub.events`).
- Declarar la Queue de socios: `socioQueue` (`socio.events.queue`), con Dead Letter Exchange:
  - `QueueBuilder.durable("socio.events.queue").deadLetterExchange("videoclub.events.dlx").deadLetterRoutingKey("socio.dlq").build()`
- Declarar el DLX (`videoclub.events.dlx`), la cola muerta (`socio.events.dlq`) y su binding.
- Declarar el Binding: `socioQueue` a `videoclubEventsExchange` con patrón `Socio.#`.
- En `rabbitListenerContainerFactory`, setear `factory.setDefaultRequeueRejected(false)` para que un mensaje que no se puede procesar caiga a la DLQ en vez de reencolarse en loop infinito.
- Fijar `factory.setConcurrentConsumers(1)` de forma explícita, para dejar documentada la garantía de orden en la que se apoya la secuencia `CREATE → UPDATE → DELETE`.

> `setDefaultRequeueRejected(false)` sin reintentos manda a la DLQ **cualquier** fallo, incluido uno transitorio (PostgreSQL reiniciándose por unos segundos). Por eso se combina con la política de reintentos configurada en `application.yml`: primero se reintenta con *backoff*, y sólo si el error persiste el mensaje se considera envenenado y se descarta a la DLQ. Un mensaje en la DLQ es un socio desincronizado hasta que alguien la drene o corra la reconciliación.

#### [MODIFY] [application.yml](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/resources/application.yml)
- Agregar las propiedades de negocio que consumen `RabbitMQConfig` y `SocioEventListener`:
  ```yaml
  videoclub:
    rabbitmq:
      exchange: ${VIDEOCLUB_EXCHANGE:videoclub.events}
      socio-queue: ${VIDEOCLUB_SOCIO_QUEUE:socio.events.queue}
  ```
- Activar confirmaciones de publicación y reintentos en el consumidor, bajo el `spring.rabbitmq` ya existente:
  ```yaml
  spring:
    rabbitmq:
      publisher-confirm-type: correlated
      publisher-returns: true
      listener:
        simple:
          concurrency: 1
          retry:
            enabled: true
            max-attempts: 3
            initial-interval: 1s
            multiplier: 2
  ```

> Hoy `application.yml` sólo define `host`, `port`, `username`, `password` y `virtual-host`: **no hay confirmaciones de publicación**. Sin `publisher-confirm-type`, un publish fallido del ACL hacia `videoclub.events` no levanta ningún error y el evento se pierde en silencio. Es el punto ciego que se analiza en el ADR.

---

> El binding de infraestructura `keycloak.admin.USER.*` **ya existe** y matchea `keycloak.admin.USER.CREATE`, `.UPDATE` y `.DELETE`. No requiere cambios.
>
> `Event<K, T>` es un tipo genérico: su deserialización depende de que `messageConverter` tenga `setAlwaysConvertToInferredType(true)` (ya configurado) y de que el listener declare el tipo concreto `Event<String, SocioPayload>` en la firma del método, de donde Spring AMQP infiere el `TypeReference`.

---

### 3. Servicios y Adaptadores (`springboot-sso`)

#### [NEW] [SocioService.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/service/SocioService.java)
- `crearSocioDesdeEvento(SocioPayload payload)`: Operación idempotente. Si no existe socio con ese `keycloakId`, lo crea y guarda. Captura `DataIntegrityViolationException` y la trata como éxito (otro consumidor ganó la carrera).
- `actualizarSocioDesdeEvento(SocioPayload payload)`: Operación idempotente. Si el socio existe, refresca `email`, `username`, `nombre` y `apellido`. Si no existe, lo crea (*upsert*: cubre usuarios anteriores a esta feature).
- `darDeBajaSocioDesdeEvento(String keycloakId)`: Operación idempotente. Marca `activo = false` y setea `fechaBaja`. Si ya estaba dado de baja, no hace nada.
- `List<SocioDTO> findAll()`: Lista todos los socios para la vista admin.
- `SocioDTO getById(Long id)`: Consulta individual.
- `int sincronizarDesdeKeycloak()`: **Backfill inicial**. Recorre los usuarios existentes vía `KeycloakAdminClient` y hace *upsert* de cada uno. Sin esto, los usuarios creados antes de desplegar la feature nunca aparecen como socios, porque la sincronización es puramente reactiva a eventos futuros.

#### [NEW] [SocioEventListener.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/service/SocioEventListener.java)
- `@RabbitListener(queues = "${videoclub.rabbitmq.socio-queue:socio.events.queue}")`.
- Firma: `onSocioEvent(Event<String, SocioPayload> event)`.
- Despacha según `eventType` (`CREATE`, `UPDATE` o `DELETE`) invocando a `SocioService`.
- Este componente representa el Bounded Context de Socios como consumidor aislado.

#### [MODIFY] [KeycloakEventListener.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/service/KeycloakEventListener.java)
- Actúa como **Anti-Corruption Layer (ACL)**:
  - Al recibir `keycloak.user.REGISTER` o `keycloak.admin.USER.CREATE`, construye un `SocioPayload` y publica `Event.Type.CREATE` a través de `MessagePublisher`.
  - Al recibir `keycloak.admin.USER.UPDATE`, publica `Event.Type.UPDATE`. Sin esto, un cambio de email en Keycloak deja al socio desincronizado para siempre.
  - Al recibir `keycloak.admin.USER.DELETE`, extrae el `userId` con `KeycloakEvent.extractTargetUserId()` (los admin events no traen `userId`: lo deriva de `resourcePath = "users/{id}"`) y publica `Event.Type.DELETE`.
  - Mantiene la emisión SSE para los toasts en tiempo real.
- Extracción de datos según el origen del evento:
  - **User events** (`REGISTER`): los datos vienen en `details` (`username`, `email`, `first_name`, `last_name` — atención al *snake_case*).
  - **Admin events** (`USER.CREATE`/`USER.UPDATE`): los datos vienen en `representation`, que el SPI publica como objeto JSON via `MAPPER.readTree(...)` (`username`, `email`, `firstName`, `lastName` — acá *camelCase*).
  - El auto-registro dispara `REGISTER`; el alta por Admin API dispara `USER.CREATE`. El alta desde `UserResource` de esta misma app dispara `USER.CREATE`. La idempotencia por `keycloakId` cubre cualquier solapamiento.

> [!NOTE]
> **Deuda conocida que este plan NO resuelve**: `SseEmitterManager.broadcast()` envía todos los eventos de autenticación a todos los clientes conectados, exponiendo logins, IPs, emails y cambios de contraseña de un usuario al resto. `sendToUser(userId, ...)` y `KeycloakEvent.extractTargetUserId()` ya existen y no se usan. Queda fuera de alcance acá, pero debe atacarse en un cambio propio antes de exponer esto a usuarios reales.

---

### 4. API REST y Seguridad (`springboot-sso`)

#### [NEW] [SocioResource.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/java/ar/unrn/video/rest/SocioResource.java)
- `@RestController` en `/api/socios` (alineado con `UserResource`, que usa `/api/users`).
- `@PreAuthorize("hasAuthority('socio-permission-read')")`.
- Endpoints:
  - `GET /api/socios`: Lista todos los socios ordenados por fecha de alta descendente.
  - `GET /api/socios/{id}`: Detalle de un socio.
  - `POST /api/socios/sync`: Dispara el backfill inicial desde Keycloak. Protegido con `hasAuthority('user-permission-create')`.
- Documentado con OpenAPI Swagger (`@Tag`, `@Operation`, `bearerAuth`).

> **No usar `hasAuthority('administrador')`**: es un grupo, no un rol, y el converter no lo mapea a authority (ver el warning al inicio). La expresión evaluaría siempre `false` y el endpoint quedaría inaccesible incluso para el admin.

---

### 5. Frontend React (`react-sso`)

#### [MODIFY] [constants.ts](file:///home/horacio/proyectos/unrn/taller/react-sso/src/constants.ts)
- Agregar ruta `socios: '/socios'` al objeto `appRoutes`.

#### [NEW] [sociosApi.ts](file:///home/horacio/proyectos/unrn/taller/react-sso/src/api/sociosApi.ts)
- Cliente que consume `GET /api/socios` reutilizando `apiRequest` de `src/api/client.ts` (mismo patrón que `usersApi.ts` y `moviesApi.ts`, incluido el manejo de `ProblemDetail`/`ApiError`).
- Tipado `Socio` (`id`, `keycloakId`, `email`, `username`, `nombre`, `apellido`, `activo`, `fechaAlta`, `fechaBaja`).

#### [NEW] [SociosView.tsx](file:///home/horacio/proyectos/unrn/taller/react-sso/src/components/routes/SociosView.tsx)
- Vista para administradores:
  - Panel con métricas rápidas (Total Socios, Activos, Inactivos).
  - Tabla con chips de estado (`Activo` en verde, `Inactivo/Baja` en gris/rojo).
  - Formateo de fechas localizadas.
  - Botón de refresco manual e invalidación con React Query (`@tanstack/react-query` ya está en `package.json`).
  - Al recibir un `ADMIN_EVENT` por SSE, invalidar la query de socios para que la tabla se actualice sola sin recargar la página.

#### [MODIFY] [Layout.tsx](file:///home/horacio/proyectos/unrn/taller/react-sso/src/components/Layout.tsx)
- Agregar pestaña `💳 Socios` en la barra de navegación superior, visible con `hasPermission('socio-permission-read')`.

> **Desviación deliberada respecto del plan original**, que pedía usar `isAdmin` para la pestaña. Se usa el permiso de grano fino en su lugar para que la visibilidad de la pestaña y el acceso real al endpoint compartan exactamente el mismo predicado. Decidir la pestaña por grupo y el endpoint por permiso permitiría el estado confuso de ver una pestaña que responde 403. `usePermissions` sigue exponiendo `isAdmin`, y `PermissionGuard` acepta `adminOnly` para quien lo necesite, pero la autorización se decide con el permiso — igual que en el backend.

#### [MODIFY] [App.tsx](file:///home/horacio/proyectos/unrn/taller/react-sso/src/components/App.tsx)
- Registrar la ruta `/socios` protegida para administradores.

#### [MODIFY] [PermissionGuard.tsx](file:///home/horacio/proyectos/unrn/taller/react-sso/src/components/auth/PermissionGuard.tsx)
- Agregar la prop opcional `adminOnly?: boolean` que use `isAdmin` de `usePermissions`, manteniendo la prop `permission` actual y el bloque de fallback "Acceso Restringido (HTTP 403 Forbidden)" ya existente.

> El guard del frontend es UX, no seguridad. La autorización real la impone `@PreAuthorize` en el backend; el guard solo evita mostrar una pantalla que igual devolvería 403.

---

## Verification Plan

### Estrategia de testing: se eligió la Vía A (sin dependencias nuevas)

El proyecto solo tiene `spring-boot-starter-test`: no hay Testcontainers, ni H2, ni `spring-rabbit-test`. Un test de integración que atraviese RabbitMQ y PostgreSQL necesitaría infraestructura que no existe, así que **`pom.xml` no se modificó**.

- **Vía A (implementada)**: `SocioServiceTest` prueba `SocioService` contra un `SocioRepository` mockeado (idempotencia, transiciones de estado, reconciliación), y `SocioEventSerializationTest` prueba que el envelope genérico `Event<String, SocioPayload>` se deserializa como tipo concreto y no como `LinkedHashMap`. El recorrido real por el broker se valida a mano.
- **Vía B (pendiente, si algún día se quiere cobertura de integración)**: agregar `org.testcontainers:rabbitmq`, `org.testcontainers:postgresql` y `spring-rabbit-test` en scope `test`. Requiere Docker disponible al correr `./mvnw test`.

### Automated Tests
1. **Tests en Spring Boot** — `./mvnw test` (14 tests, en verde):
   - `Event.Type.CREATE` → `SocioService` crea `Socio` con `activo = true` y `fechaAlta` seteada.
   - `Event.Type.UPDATE` → refresca los datos; si el socio no existe, lo crea (upsert).
   - `Event.Type.DELETE` → marca `activo = false` con `fechaBaja`.
   - Idempotencia: CREATE sobre un socio existente y DELETE sobre uno ya inactivo no escriben (y `fechaBaja` original se preserva).
   - Un `SocioPayload` sin `email` se persiste sin lanzar excepción.
   - Reconciliación: da de alta usuarios preexistentes y refleja el flag `enabled` de Keycloak en `activo`.
   - El envelope `Event<String, SocioPayload>` deserializa `data` como `SocioPayload` (no como `Map`), y `routingKey` no se serializa al wire format.
2. **Compilación y linter frontend**:
   ```bash
   npm run build && npm run lint
   ```

### Manual Verification
1. Iniciar sesión como `usuarioadmin` en React (`http://localhost:5173`).
2. Verificar que aparece el nuevo tab **💳 Socios**.
3. Ejecutar `POST /api/socios/sync` y comprobar que los usuarios preexistentes de Keycloak aparecen como socios activos.
4. En la vista de **👥 Gestión Usuarios**, crear un nuevo usuario (`socionuevo@test.com`).
5. Comprobar en tiempo real:
   - El toast de notificación SSE aparece en el navegador.
   - En el tab **💳 Socios**, el usuario aparece registrado automáticamente como socio activo con su ID de Keycloak.
6. Cambiar el email del usuario desde la consola de Keycloak y verificar que el socio refleja el nuevo valor.
7. Eliminar el usuario desde Keycloak o mediante `kcadm.sh`:
   - En el tab **💳 Socios**, el socio pasa automáticamente al estado inactivo (`Baja`) con la marca de tiempo de baja.
8. Iniciar sesión como `usuariocliente` y verificar que el tab **💳 Socios** no es visible, y que `GET /api/socios` devuelve **403** (el grupo `cliente` no tiene `socio-permission-read`).
9. Publicar a mano un mensaje inválido en `videoclub.events` con routing key `Socio.CREATE` y confirmar que termina en `socio.events.dlq` en vez de reencolarse indefinidamente.
