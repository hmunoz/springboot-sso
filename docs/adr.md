# Registros de Decisiones de Arquitectura (ADR)

Este documento centraliza todos los **Architecture Decision Records (ADR)** del proyecto VideoClub, documentando el contexto, las opciones evaluadas, la decisión final y sus consecuencias técnicas.

---

## Índice de Decisiones

* [ADR-001: Topología de Doble Exchange en RabbitMQ (`keycloak.events` vs. `videoclub.events`)](#adr-001-topología-de-doble-exchange-en-rabbitmq-keycloakevents-vs-videoclubevents)
* [ADR-002: Por qué NO aplican SAGA ni Transactional Outbox en la Sincronización de Socios](#adr-002-por-qué-no-aplican-saga-ni-transactional-outbox-en-la-sincronización-de-socios)
* [ADR-003: Anti-Corruption Layer (ACL) para Aislar el SPI de Keycloak](#adr-003-anti-corruption-layer-acl-para-aislar-el-spi-de-keycloak)
* [ADR-004: Estrategia de Baja Lógica (Soft Delete) en la Entidad Socio](#adr-004-estrategia-de-baja-lógica-soft-delete-en-la-entidad-socio)
* [ADR-005: Autorización por Authorities de Grano Fino vs. Grupos de Keycloak](#adr-005-autorización-por-authorities-de-grano-fino-vs-grupos-de-keycloak)
* [ADR-006: Ruteo Orientado a Recursos (Resource-Based Facade) en API Gateway](#adr-006-ruteo-orientado-a-recursos-resource-based-facade-en-api-gateway)
* [ADR-007: Exclusión de Keycloak del API Gateway (Separación de Planos)](#adr-007-exclusión-de-keycloak-del-api-gateway-separación-de-planos)
* [ADR-008: Servidor MCP sobre Streamable HTTP en Modo `STATELESS`](#adr-008-servidor-mcp-sobre-streamable-http-en-modo-stateless)
* [ADR-009: `@PreAuthorize` directo sobre los métodos `@McpTool`](#adr-009-preauthorize-directo-sobre-los-métodos-mcptool)
* [ADR-010: Metadatos de Recursos Protegidos (RFC 9728) y Clientes MCP Públicos con PKCE](#adr-010-metadatos-de-recursos-protegidos-rfc-9728-y-clientes-mcp-públicos-con-pkce)
* [ADR-011: Declaración Explícita de Hints de Solo Lectura en Herramientas MCP](#adr-011-declaración-explícita-de-hints-de-solo-lectura-en-herramientas-mcp)
* [ADR-012: Bridge Stdio con Direct Access Grants para Clientes No Interactivos (Antigravity)](#adr-012-bridge-stdio-con-direct-access-grants-para-clientes-no-interactivos-antigravity)

---

### ADR-001: Topología de Doble Exchange en RabbitMQ (`keycloak.events` vs. `videoclub.events`)

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El SPI de Keycloak publica eventos técnicos de ciclo de vida de usuarios (`keycloak.user.*`, `keycloak.admin.USER.*`). Inicialmente se utilizaba el exchange predeterminado `amq.topic` o se evaluó compartir un único exchange para todo el sistema (`videoclub.events`).

#### Decisión
1. Reemplazar `amq.topic` por un topic exchange dedicado y durable: **`keycloak.events`**.
2. Mantener **dos exchanges separados**:
   * `keycloak.events`: Exchange de infraestructura donde publica el SPI de Keycloak.
   * `videoclub.events`: Exchange de dominio donde el backend publica eventos canónicos (`Socio.CREATE`, `Socio.UPDATE`, `Socio.DELETE`).

#### Justificación
* `amq.topic` es un exchange reservado de RabbitMQ: no se puede configurar, no admite políticas de permisos finos por vhost y mezcla tráfico no relacionado.
* Un solo exchange compartido debilita las fronteras arquitectónicas: un error en un wildcard de binding (`#`) enviaría eventos crudos del IdP a colas de dominio que no saben parsearlos, enviándolos a la DLQ.
* Los dos exchanges hablan **idiomas distintos**: infraestructura (`keycloak.admin.USER.CREATE`) vs. negocio (`Socio.CREATE`). Separarlos permite extraer el microservicio de Socios en el futuro sin modificar el SPI.

#### Consecuencias
* **Positivas:** Desacoplamiento total entre infraestructura de identidad y eventos de negocio. Seguridad estructural de enrutamiento.
* **Trade-offs:** Requiere un componente intermedio (ACL) que consuma de un exchange y republique en el otro con confirmaciones (`publisher-confirms`).

---

### ADR-002: Por qué NO aplican SAGA ni Transactional Outbox en la Sincronización de Socios

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Al diseñar la sincronización entre Keycloak y el Bounded Context de Socios, se evaluó si era necesario implementar patrones de transacciones distribuidas como SAGA o Transactional Outbox.

#### Decisión
**NO** implementar SAGA ni Transactional Outbox para este flujo. La arquitectura se basa en **sincronización unidireccional con consistencia eventual y reconciliación (Backfill)**.

#### Justificación
* **SAGA:** Resuelve transacciones de negocio que requieren compensación inversa (rollback en múltiples servicios). En este flujo, Keycloak es el maestro de identidad: si la persistencia de `Socio` en PostgreSQL falla, **jamás se debe disparar una compensación para borrar el usuario de Keycloak**. El fallo se deriva a reintentos y Dead Letter Queue (DLQ).
* **Transactional Outbox:** Resuelve la escritura dual atómica (base de datos + broker) en el mismo proceso.
  * *Como consumidor (`SocioEventListener`):* Solo escribe en PostgreSQL, no publica. La consistencia se garantiza no enviando el `ACK` a RabbitMQ hasta que la transacción relacional haga `COMMIT`.
  * *Como productor (`KeycloakEventListener`):* Solo consume de `keycloak.events` y publica en `videoclub.events`. No escribe en base de datos; no hay dual write. Se garantiza con `publisher-confirm-type: correlated`.
* **El Dual Write real ocurre en Keycloak:** Keycloak escribe en su datastore y luego el SPI emite al broker. Si RabbitMQ está caído, el SPI descarta el evento. Este desacople estructural fuera de nuestro control convierte a la **reconciliación (`POST /api/socios/sync`)** en el mecanismo indispensable de consistencia.

#### Consecuencias
* **Positivas:** Simplicidad arquitectónica sin sobreingeniería.
* **Trade-offs:** La consistencia es eventual. Si Keycloak descarta un evento en una caída del broker, la discrepancia persiste hasta la ejecución del endpoint de sincronización/reconciliación.

---

### ADR-003: Anti-Corruption Layer (ACL) para Aislar el SPI de Keycloak

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
El SPI de Keycloak emite eventos heterogéneos: los registros de usuario (`REGISTER`) envían datos en `details` con nombres en *snake_case*, mientras que las acciones administrativas (`USER.CREATE`, `USER.UPDATE`) envían una representación JSON en *camelCase*.

#### Decisión
Implementar un **Anti-Corruption Layer (ACL)** en `KeycloakEventListener` que traduzca eventos técnicos del IdP a un envelope canónico de dominio `Event<String, SocioPayload>`.

#### Justificación
* El Bounded Context de Socios debe permanecer agnóstico de si la identidad proviene de Keycloak, Auth0 u otro proveedor.
* Resuelve las diferencias de formato de Keycloak (`details` vs `representation`) en una única frontera, entregando al dominio un payload tipado y normalizado.

#### Consecuencias
* **Positivas:** El modelo `Socio` y su listener de eventos no conocen tipos ni estructuras de Keycloak.
* **Trade-offs:** Salto adicional de procesamiento en memoria antes de reenviar el evento canónico.

---

### ADR-004: Estrategia de Baja Lógica (Soft Delete) en la Entidad Socio

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Cuando un usuario es eliminado en Keycloak (`keycloak.admin.USER.DELETE`), debe determinarse la política de borrado en la base de datos de la aplicación.

#### Decisión
Implementar **Baja Lógica (Soft Delete)**: la fila en `socio` no se elimina; se marcan los campos `activo = false` y `fechaBaja = LocalDateTime.now()`.

#### Justificación
* El videoclub gestiona entidades de negocio dependientes (historial de alquileres, pagos, penalizaciones). Si se ejecutara un `DELETE` físico (`Hard Delete`), se violaría la integridad referencial de claves foráneas o se perdería el historial contable y operativo.

#### Consecuencias
* **Positivas:** Preservación histórica y trazabilidad total.
* **Trade-offs:** Todas las consultas activas deben filtrar explícitamente por `activo = true` o presentar chips visuales diferenciados en la interfaz de administración.

---

### ADR-005: Autorización por Authorities de Grano Fino vs. Grupos de Keycloak

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Se evaluó si la seguridad en endpoints (`@PreAuthorize`) debía verificar la membresía en grupos de Keycloak (ej. `hasAuthority('administrador')`) o roles de cliente.

#### Decisión
Autorizar exclusivamente mediante **Client Roles de grano fino** (`movie-permission-read`, `socio-permission-read`, `user-permission-create`).

#### Justificación
* `KeycloakGrantedAuthoritiesConverter` mapea a `GrantedAuthority` los claims `realm_access.roles` y `resource_access.*.roles`. El claim `groups` **no se convierte en authority** en Spring Security. Usar `hasAuthority('administrador')` evalúa siempre a `false`.
* Principio de diseño: **Los grupos representan identidad/organización; los roles representan permisos de acceso**. Asociar permisos específicos a los grupos en Keycloak y verificar los permisos en backend permite modificar los privilegios de un grupo sin tocar el código fuente.

#### Consecuencias
* **Positivas:** Seguridad desacoplada de la jerarquía de grupos. Compatibilidad nativa con Spring Security.
* **Trade-offs:** El archivo de exportación del realm (`realm-export.json`) debe mapear explícitamente los roles a los grupos.

---

### ADR-006: Ruteo Orientado a Recursos (Resource-Based Facade) en API Gateway

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
En el API Gateway se consideró enrutar mediante prefijos de servicio (ej. `/catalogo/movies/**`, fijando `VITE_API_BASE_URL=http://localhost:9500/catalogo`).

#### Decisión
Configurar el API Gateway como una **fachada orientada a recursos de dominio**:
* `VITE_API_BASE_URL=http://localhost:9500`
* Rutas directas: `/movies/**`, `/api/socios/**`, `/api/users/**`, `/api/notifications/**`.

#### Justificación
* Si el frontend ata su base URL a `/catalogo`, no puede consumir otros servicios (`/socios`, `/notifications`) sin instanciar múltiples clientes HTTP o romper contratos.
* Permite extraer cualquier módulo a un microservicio independiente (ej. `Socio` al puerto `8082`) modificando únicamente la URI en `docker/gateway/gateway.yml`, con **cero cambios** en el frontend.

#### Consecuencias
* **Positivas:** Flexibilidad total para descomponer el monolito modular en microservicios. Frontend 100% agnóstico de la topología interna.

---

### ADR-007: Exclusión de Keycloak del API Gateway (Separación de Planos)

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Se planteó si Keycloak debía quedar accesible a través del API Gateway (ej. exponiendo `/auth/**` en el puerto `9500`).

#### Decisión
**NO** colocar Keycloak detrás del API Gateway. Keycloak opera en su propio puerto y autoridad (`:9091`).

#### Justificación
* **Separación de Planos:** Keycloak es la Autoridad de Certificación (Identity Plane). El Gateway gestiona las APIs de negocio (Data Plane).
* **Validación de Issuer (`iss`):** Keycloak emite JWTs con el claim `iss: http://localhost:9091/realms/videoclub`. Si el cliente negocia el token a través del Gateway, los Resource Servers que validan contra el IdP directo rechazan los tokens con `JwtValidationException: Invalid issuer`, requiriendo complejas reescrituras de cabeceras `X-Forwarded-*`.
* **Flujo Estándar OAuth2:** La SPA negocia directamente con Keycloak (`:9091`) y luego consume el Gateway (`:9500`) adjuntando el `Bearer token`.

#### Consecuencias
* **Positivas:** Cumplimiento estricto del estándar OIDC y arquitectura limpia sin proxies innecesarios.

---

### ADR-008: Servidor MCP sobre Streamable HTTP en Modo `STATELESS`

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Para exponer herramientas de negocio a agentes de IA mediante el Model Context Protocol (MCP), se evaluaron los transportes disponibles en Spring AI 2.0 (`SSE`, `STREAMABLE`, `STATELESS`).

#### Decisión
Adoptar **Streamable HTTP en modo `STATELESS`** sobre el endpoint `POST /mcp`.

#### Justificación
* El transporte HTTP+SSE quedó formalmente deprecado en la revisión de la especificación MCP de marzo 2025.
* Para un conjunto de herramientas de consulta y lectura (catálogo y socios), `STATELESS` trata cada llamada como un POST HTTP estándar:
  * El `SecurityContext` se evalúa en el hilo del servlet en cada petición, igual que en cualquier endpoint REST.
  * El token JWT se revalida en cada invocación (en SSE de larga vida, la validación solo ocurre al conectar y envejece con la sesión).
  * No requiere estado de sesión en memoria, permitiendo escalabilidad horizontal sin sesiones pegajosas (*sticky sessions*).

#### Consecuencias
* **Positivas:** Seguridad por petición, sin sesiones huérfanas, compatibilidad total con filtros de Spring Security.
* **Trade-offs:** No soporta notificaciones iniciadas por el servidor hacia el cliente (sampling/elicitation), innecesarias para este caso de uso.

---

### ADR-009: `@PreAuthorize` directo sobre los métodos `@McpTool`

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026
* **Reemplaza a:** la versión previa de este ADR, que adoptaba un patrón Delegate (`AuthorizedMovieQueries` / `AuthorizedSocioQueries`) por creer que `@PreAuthorize` sobre un método `@McpTool` rompía el descubrimiento de herramientas. Esa premisa se verificó y resultó **falsa** para Spring AI 2.0.1.

#### Contexto
Los servicios de dominio (`MovieService`, `SocioService`) no tienen control de acceso propio: los protege la capa REST. Las herramientas MCP los alcanzan por debajo de esa capa, así que necesitan su propia autorización.

La opción directa —anotar los métodos `@McpTool` con `@PreAuthorize`— se descartaba por una creencia extendida: que el proxy CGLIB resultante deja al servidor MCP publicando cero herramientas. Se verificó contra Spring AI 2.0.1 y **no ocurre**. La autoconfiguración registra las tools vía `SyncMcpAnnotationProviders`, cuyo descubrimiento resuelve `AopUtils.getTargetClass(bean)` antes de leer los métodos; la invocación por reflexión sobre la instancia proxy sigue atravesando el interceptor de Spring Security. El análisis completo está en [mcp-server.md §4](mcp-server.md#4-arquitectura-de-autorización-preauthorize-sobre-las-tools).

#### Decisión
Colocar `@PreAuthorize` directamente sobre cada método `@McpTool`, en `MovieMcpTools` y `SocioMcpTools`, y eliminar los beans delegados.

#### Justificación
* **Legibilidad:** el permiso queda al lado de la operación que protege. No hay que saltar entre dos clases para responder "¿quién puede llamar a esta tool?".
* **Menos superficie:** se eliminan dos `@Component` y un salto de indirección por agregado, sin perder ninguna garantía de seguridad.
* **Riesgo acotado y observable:** la dependencia que se asume está cubierta por tests que la convierten en build rojo (ver Consecuencias).

#### Consecuencias
* **Positivas:** Herramientas descubribles y autorización de grano fino sobre los mismos métodos, con dos clases menos.
* **Negativas — la que importa:** el arreglo depende de un **detalle de implementación interno** de Spring AI, no de un contrato publicado. La API pública `AbstractMcpToolProvider` sigue leyendo `bean.getClass().getDeclaredMethods()` y es ciega a los proxies. Si un upgrade elimina el override proxy-consciente de `SyncMcpAnnotationProviders`, el servidor arranca **sin errores en los logs** y publica una lista vacía de herramientas.
* **Mitigación:** `McpToolsSecurityTest#everyToolIsDiscoverable` ejercita el camino exacto de producción sobre beans proxiados, y `#publicProviderApiIsProxyBlind` fija el comportamiento de la API pública. Cualquier cambio en Spring AI en cualquiera de las dos direcciones produce un build rojo antes del deploy.
* **Vuelta atrás:** reintroducir el patrón Delegate, documentado paso a paso en [mcp-server.md §4](mcp-server.md#-cómo-corregirlo-si-rompe).
* **Riesgo secundario inactivo:** el descubrimiento invoca `Method` de la clase target sobre el proxy; con un proxy dinámico JDK fallaría con `IllegalArgumentException`. Hoy no aplica (CGLIB por defecto, sin interfaces).

---

### ADR-010: Metadatos de Recursos Protegidos (RFC 9728) y Clientes MCP Públicos con PKCE

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Los agentes de IA modernos (como Claude Code CLI) implementan la especificación de descubrimiento de OAuth para MCP. Se requería que un agente pueda conectarse sin requerir hardcodear URLs de autenticación.

#### Decisión
1. Publicar el documento de descubrimiento **RFC 9728** en `/.well-known/oauth-protected-resource` mediante `.oauth2ResourceServer(o -> o.protectedResourceMetadata(...))`.
2. Registrar en Keycloak el cliente público **`videoclub-mcp`** con PKCE (`S256`) y `redirectUris` locales en loopback (`http://localhost:8090/*`).

#### Justificación
* Permite el flujo interactivo de Claude CLI (`claude mcp add --transport http --client-id videoclub-mcp --callback-port 8090 videoclub http://localhost:8080/mcp`): el cliente recibe un 401 con `WWW-Authenticate`, descubre Keycloak por sí mismo, abre el navegador, autentica al usuario y completa el canje de código por token de forma desatendida.

#### Consecuencias
* **Positivas:** Interoperabilidad total con cualquier cliente MCP compliant con el estándar abierto.

---

### ADR-011: Declaración Explícita de Hints de Solo Lectura en Herramientas MCP

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
Por defecto, Spring AI anuncia todas las herramientas registradas con `readOnlyHint: false` y `destructiveHint: true`.

#### Decisión
Declarar explícitamente en todas las anotaciones `@McpTool`:
```java
readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
```

#### Justificación
* Los LLMs y agentes de IA leen las anotaciones de esquema para decidir si solicitan confirmación previa al usuario antes de ejecutar una tool. Si una consulta de solo lectura se anuncia como destructiva, el agente degrada la experiencia de usuario pidiendo confirmaciones innecesarias.

#### Consecuencias
* **Positivas:** El agente comprende que las consultas de catálogo y socios son seguras e idempotentes.

---

### ADR-012: Bridge Stdio con Direct Access Grants para Clientes No Interactivos (Antigravity)

* **Estado:** Aceptado e Implementado
* **Fecha:** Septiembre 2026

#### Contexto
A diferencia de Claude CLI, el IDE Antigravity ejecuta servidores MCP en procesos en segundo plano vía `stdio` y no dispone de un servidor web embebido para recibir callbacks interactivos de OAuth en el puerto 8090. Si se configurara un token estático en `serverUrl`, este expiraría a los 30 minutos.

#### Decisión
Implementar un bridge ligero en Python (`.agents/scripts/mcp-bridge.py`) configurado en `.agents/mcp_config.json`, que utiliza el flujo **Direct Access Grants (ROPC)** contra Keycloak.

#### Justificación
* El cliente `videoclub-mcp` tiene habilitado `directAccessGrantsEnabled: true`. El bridge obtiene el JWT mediante HTTP POST directo sin abrir navegador, renueva el token automáticamente antes de su expiración y reenvía las peticiones JSON-RPC a `http://localhost:8080/mcp`.
* Intercepta mensajes propietarios o iniciales de sondeo (`server/discover`) respondiendo `-32601 Method not found`, lo que permite que el cliente en Go de Antigravity proceda de inmediato al handshake de `initialize`.

#### Consecuencias
* **Positivas:** Conexión MCP en Antigravity 100% autónoma y transparente, con renovación perpetua de sesión y posibilidad de alternar entre perfiles (`usuarioadmin` vs `usuariocliente`) mediante variables de entorno.
