# Exchange dedicado para los eventos de Keycloak (`amq.topic` → `keycloak.events`)

Mover la publicación de eventos del SPI de Keycloak desde el exchange built-in `amq.topic` hacia un exchange propio y gestionable, `keycloak.events`, manteniendo la separación de dos exchanges: uno de infraestructura para los eventos técnicos del proveedor de identidad y otro de negocio (`videoclub.events`) para los eventos canónicos de dominio.

## User Review Required

> [!WARNING]
> **El nombre del exchange está en cuatro lugares y todos tienen que coincidir**
> Hoy el valor por defecto `amq.topic` está escrito en tres archivos distintos, y un cuarto no tiene default:
>
> | Lugar | Hoy | Consecuencia si queda desalineado |
> |---|---|---|
> | `docker/keycloak/keycloak-spi/.../RabbitMQEventListenerProviderFactory.java:51` | `env("RABBITMQ_EXCHANGE", "amq.topic")` | el SPI publica en un exchange y nadie lo escucha |
> | `src/main/resources/application.yml:96` | `${RABBITMQ_EXCHANGE:amq.topic}` | Spring bindea la cola a otro exchange |
> | `./.env` | `RABBITMQ_EXCHANGE=amq.topic` | lo lee Spring |
> | `docker/keycloak.yaml:20` | `${RABBITMQ_EXCHANGE}` — **sin default** | si `docker/.env` no la define, Compose inyecta cadena vacía y el SPI cae a *su* default |
>
> **El modo de falla es silencioso.** Si el SPI publica en un exchange y la cola `keycloak-events` está bindeada a otro, no hay excepción, no hay log de error y no hay mensaje en la DLQ: la cola simplemente no recibe nada. Los eventos se pierden y todo parece funcionar.

> [!IMPORTANT]
> **Recrear el contenedor de Keycloak destruye datos**
> Las variables de entorno se fijan al **crear** el contenedor: `docker restart` no toma el cambio, hace falta `--force-recreate`. Y en este setup eso borra la base:
> - `DB_VENDOR=h2` con `start-dev`, y **no hay volumen** para `/opt/keycloak/data` (los únicos binds son el jar del SPI, `realm-export.json` y `themes`).
> - Se pierden los usuarios del realm y vuelven sólo los definidos en `realm-export.json`.
> - Las filas de la tabla `socio` quedan huérfanas, apuntando a `keycloakId` que ya no existen.
>
> Confirmado con el usuario: es un entorno de prueba y se puede borrar todo. La causa de fondo — la falta de volumen — queda registrada como deuda al final de este documento.

---

## Decisiones de Arquitectura (ADR)

### ¿Por qué un exchange propio y no `amq.topic`?

`amq.topic` es un exchange **built-in** de RabbitMQ, y eso trae tres limitaciones concretas:

1. **No se puede gestionar.** RabbitMQ reserva el prefijo `amq.`: no se puede borrar ni recrear, así que su ciclo de vida no es nuestro.
2. **No admite permisos finos.** Los permisos de RabbitMQ son expresiones regulares sobre el nombre del exchange. Con `amq.topic` no hay forma de otorgarle al SPI permiso para publicar *sus* eventos sin dárselo sobre el exchange compartido que usa todo el broker.
3. **No comunica nada.** En la consola de management, `keycloak.events` dice qué transporta; `amq.topic` es un cajón de sastre.

### ¿Por qué NO usar un único exchange para todo (`videoclub.events`)?

Técnicamente **funciona**, y por eso conviene entender qué se pierde.

Las routing keys no colisionan (`keycloak.user.#` y `keycloak.admin.USER.*` contra `Socio.#`), pero no es eso lo que evita el problema: lo que lo evita es que son **colas distintas con bindings distintos**. El `messageConverter` tiene `setAlwaysConvertToInferredType(true)`, así que infiere el tipo desde la firma del listener — `KeycloakEvent` en uno, `Event<String, SocioPayload>` en el otro — y cada listener sólo ve mensajes de su propia cola. Las formas heterogéneas conviven sin romperse.

Lo que se pierde es la **garantía estructural**. Con dos exchanges, que un evento crudo de Keycloak llegue a la cola de Socios es imposible. Con uno solo, queda a un binding descuidado de distancia: un `#` mal puesto mete mensajes con la forma equivocada en una cola y terminan en la DLQ. El límite deja de ser estructural y pasa a ser una convención de prefijos.

Y hay una razón de diseño, no sólo de robustez: los dos exchanges transportan **idiomas distintos**.

| | `keycloak.events` | `videoclub.events` |
|---|---|---|
| Contenido | eventos técnicos del proveedor | eventos canónicos de dominio |
| Vocabulario | `keycloak.user.LOGIN` | `Socio.CREATE` |
| Dueño | la infraestructura de identidad | el dominio del videoclub |

Compartir el exchange convierte al Anti-Corruption Layer en un relay dentro de la misma habitación, y mata el argumento que sostiene todo el diseño: poder extraer el Bounded Context de Socios a un servicio aparte sin tocar el ACL.

### ¿Por qué NO emitir `Event<K,T>` desde el SPI?

Es posible y es trivial —el SPI ya construye un `ObjectNode`, y `Event<K,T>` es sólo JSON—. Se descarta por dos razones, y la primera está en el propio código del proyecto:

1. **El stream crudo se necesita igual.** `react-sso/src/hooks/useNotifications.ts` consume los eventos técnicos para los toasts: `LOGIN` (con `ipAddress`), `UPDATE_PASSWORD`, `UPDATE_TOTP`, `VERIFY_EMAIL`, `SEND_VERIFY_EMAIL`. De todo ese conjunto sólo un puñado se mapea a `Socio`. Si el SPI emitiera únicamente eventos de dominio, se rompen las notificaciones; y si emite ambos formatos, el SPI **es** el ACL — de Socios hoy y de todo consumidor futuro. Eso es un *smart pipe*: la tubería empieza a saber de negocio y crece con cada consumidor nuevo.
2. **Invierte la dependencia.** El SPI se despliega **dentro** de Keycloak, en `/opt/keycloak/providers`. Si emite `Socio.CREATE`, el proveedor de identidad pasa a conocer el vocabulario del videoclub, y cada cambio del modelo de dominio obliga a recompilar y redesplegar un plugin de Keycloak. El dominio depende de la identidad, nunca al revés.

> **Punto medio considerado y descartado:** que el SPI emita un envelope canónico con vocabulario de infraestructura (`aggregate="KeycloakUser"` en lugar de `"Socio"`). Unifica el formato del bus sin meter "Socio" dentro de Keycloak, pero sigue haciendo falta el ACL para mapear `KeycloakUser → Socio`, y agrega el versionado de un contrato de envelope cruzando el límite de un plugin. Ganancia marginal, costo real.

---

## Proposed Changes

### 1. SPI de Keycloak

#### [DONE] [RabbitMQEventListenerProviderFactory.java](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/keycloak/keycloak-spi/src/main/java/ar/unrn/keycloak/spi/RabbitMQEventListenerProviderFactory.java)
**Ya aplicado y el jar ya recompilado.** Se agregó en `init()`, después de `createChannel()`:

```java
channel.exchangeDeclare(exchange, "topic", true);
```

Este era el bloqueante real del cambio, no el nombre del exchange. El SPI **nunca declaraba** el exchange: funcionaba sólo porque `amq.topic` siempre existe. Apuntando `RABBITMQ_EXCHANGE` a cualquier otro nombre, el primer `basicPublish` falla con 404, el broker cierra el **único canal de por vida** del SPI, y desde ahí todo evento se descarta en silencio por este guard, hasta reiniciar Keycloak:

```java
if (channel == null || !channel.isOpen()) {
    LOG.warning("RabbitMQ channel not available — dropping event");
    return;
}
```

Declarar es idempotente, así que re-declarar `amq.topic` con propiedades coincidentes es un no-op: el cambio es seguro incluso si se decide no mover el exchange.

#### [DONE] mismo archivo — alinear el default
- `env("RABBITMQ_EXCHANGE", "amq.topic")` → `env("RABBITMQ_EXCHANGE", "keycloak.events")`.
- Javadoc de la clase actualizado, documentando que el exchange se declara al arrancar.
- Jar recompilado con `mvn -o package`. Verificado con `javap` que la constante `amq.topic` ya no está en el `.class` y que quedan `keycloak.events` y `topic`.

#### [DONE] [README.md del SPI](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/keycloak/keycloak-spi/README.md)
- Default Exchange → `keycloak.events`, tipo `topic` y durable.
- Documentado que **el SPI declara el exchange** al iniciar, y que declarar es idempotente (apuntarlo a `amq.topic` sigue funcionando).
- Instrucción de binding y tabla de variables de entorno actualizadas.

---

### 2. Configuración de la aplicación

#### [DONE] [application.yml](file:///home/horacio/proyectos/unrn/taller/springboot-sso/src/main/resources/application.yml)
- `exchange: ${RABBITMQ_EXCHANGE:amq.topic}` → `${RABBITMQ_EXCHANGE:keycloak.events}`.

#### [DONE] [docker/keycloak.yaml](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/keycloak.yaml)
- `RABBITMQ_EXCHANGE: ${RABBITMQ_EXCHANGE}` → `${RABBITMQ_EXCHANGE:-keycloak.events}`, con un comentario que explica por qué el default es obligatorio acá.

> Es la única de las cuatro puntas **sin** valor por defecto. Hoy, si `docker/.env` no define la variable, Compose inyecta cadena vacía, el SPI cae a su propio default y se produce exactamente la desincronización silenciosa descrita arriba. Poner el default acá elimina esa clase de falla.

#### [PENDIENTE — único paso que falta] `./.env` y `./docker/.env`
**Los hace el usuario**: estos archivos están bloqueados por permisos en el entorno del agente.

> [!NOTE]
> **El cambio ya aplicado es seguro y no conmuta nada todavía.** Los cuatro defaults dicen ahora `keycloak.events`, pero ambos `.env` siguen definiendo `RABBITMQ_EXCHANGE=amq.topic`, y un valor explícito gana sobre el default tanto en `${VAR:default}` de Spring como en `${VAR:-default}` de Compose. Es decir: hoy todo sigue publicando y consumiendo en `amq.topic`, de forma consistente. La conmutación ocurre recién al editar estos dos archivos, y por eso son el último paso.

- En **ambos**: `RABBITMQ_EXCHANGE=keycloak.events`.
- En `docker/.env`: **borrar** `KK_TO_RMQ_EXCHANGE=amq.topic`. Es configuración muerta — verificado con `rg` sobre `src`, los tres YAML de `docker/` y el fuente del SPI: **nadie la lee**. Es resto del plugin legacy `keycloak-to-rabbit-3.0.5.jar` que este SPI reemplazó.
- Revisar `.env.example` y alinearlo, para que un clon nuevo arranque coherente.

---

### 3. Migración del entorno

Orden **obligatorio**: el SPI declara el exchange al arrancar, así que Keycloak puede iniciar primero sin problema — eso es justamente lo que compra el `exchangeDeclare`.

1. Recrear Keycloak para que tome la variable nueva y el jar nuevo:
   ```bash
   docker compose -f docker/services.yaml up -d --force-recreate keycloak
   ```
2. Reiniciar la aplicación Spring, para que declare `keycloak.events` y bindee `keycloak-events` con `keycloak.user.#` y `keycloak.admin.USER.*`.
3. Limpiar los bindings viejos: la cola `keycloak-events` conserva sus bindings a `amq.topic` (el `RabbitAdmin` de Spring declara, nunca borra). Quedan inertes porque el SPI ya no publica ahí, pero conviene eliminarlos para que la topología diga la verdad.
4. Reparar los socios huérfanos: al recrear Keycloak, los `keycloakId` cambian.
   ```sql
   truncate table socio;
   ```
   y después `POST /api/socios/sync`.
5. Recordar que el realm re-importado ya incluye `socio-permission-read` (está en `realm-export.json`), así que no hace falta volver a agregarlo con `kcadm`.

---

## Verification Plan

### Verificación automática
```bash
./mvnw test        # 14 tests; ninguno depende del nombre del exchange
npm run build && npm run lint   # en react-sso
```

Los tests no cubren el nombre del exchange: es configuración de topología, y se verifica contra el broker.

### Verificación manual

1. **El SPI declara el exchange.** En el log de Keycloak:
   ```
   RabbitMQ SPI connected to rabbit:5672 (exchange: keycloak.events, declared as durable topic)
   ```
2. **El exchange existe y es topic durable:**
   ```bash
   docker exec videoclub-rabbit-1 rabbitmqctl list_exchanges name type durable | rg keycloak
   ```
3. **Los bindings apuntan al exchange nuevo y ya no al viejo:**
   ```bash
   docker exec videoclub-rabbit-1 rabbitmqctl list_bindings source_name destination_name routing_key | rg -i "keycloak|socio"
   ```
   Esperado: `keycloak.events → keycloak-events` con las dos routing keys, `videoclub.events → socio.events.queue` con `Socio.#`, y **ninguna** entrada con `amq.topic` como origen.
4. **Camino de eventos completo:** crear un usuario con `POST /api/users` y comprobar que aparece el toast SSE y que el socio se da de alta solo en `GET /api/socios`.
5. **Baja lógica:** borrar ese usuario de Keycloak y verificar `activo=false` con `fechaBaja`.
6. **La DLQ queda vacía** durante todo el ejercicio:
   ```bash
   docker exec videoclub-rabbit-1 rabbitmqctl list_queues name messages | rg dlq
   ```
7. **Prueba de arranque invertido — la que justifica el cambio de código.** Levantar Keycloak con la aplicación Spring **apagada**, crear un usuario, y sólo entonces arrancar la aplicación. El evento debe estar esperando en la cola. Antes del `exchangeDeclare`, este escenario dejaba el canal del SPI cerrado y descartaba todos los eventos en silencio.

---

## Deuda registrada, fuera de alcance

- **Falta un volumen para `/opt/keycloak/data`.** Es la causa de fondo de que cualquier cambio de configuración de Keycloak cueste el estado del realm. Con un volumen —o mejor, moviendo Keycloak a PostgreSQL en lugar de `start-dev` con H2— recrear el contenedor pasa a ser gratis. El exchange es la consecuencia; esto es el problema.
- **El SPI descarta eventos en silencio** cuando el canal no está disponible (`LOG.warning` + `return`). El `exchangeDeclare` elimina una de las causas, no el comportamiento. La red de seguridad sigue siendo la reconciliación (`POST /api/socios/sync`), que debería correr además de forma periódica y no sólo bajo demanda.
- **El SPI publica con `PERSISTENT_TEXT_PLAIN`**, lo que obliga al workaround de `contentType` en `RabbitMQConfig.messageConverter`. Arreglarlo en el origen —publicar `application/json`— permitiría borrar esa subclase anónima.
- **`SseEmitterManager.broadcast()` sigue enviando todos los eventos de autenticación a todos los clientes conectados**, exponiendo logins, IPs, emails y cambios de contraseña de un usuario al resto. `sendToUser(userId, ...)` y `KeycloakEvent.extractTargetUserId()` ya existen y no se usan.
