# Documentación de Arquitectura — VideoClub Platform

Bienvenido a la documentación técnica, arquitectónica y operativa de la plataforma VideoClub. Esta documentación refleja el estado del sistema en producción y desarrollo, estructurada por dominios de arquitectura y respaldada por registros formales de decisiones de diseño (ADRs).

> [!IMPORTANT]
> **El Backend Core dejó de ser un único servicio.** `springboot-sso` se separó en dos proyectos Maven **independientes** —`catalog-service` (`:8081`) y `membership-service` (`:8082`)— cada uno con su propia base de datos, su propio servidor MCP y su propio `Dockerfile`. No hay POM agregador ni módulos Maven: cada carpeta es un proyecto Spring Boot completo que abre solo en el IDE.
>
> El razonamiento, las 7 decisiones (D1–D7) y las 10 trampas de configuración (C1–C10) están en **[Arquitectura de Dos Servicios: Decisiones y Trampas](arquitectura-dos-servicios.md)**.

---

## 🗺️ Ecosistema de la Plataforma

La plataforma VideoClub está organizada en microservicios desacoplados y servicios satélite, cada uno alojado en su propio repositorio con contratos bien definidos:

| Componente | Repositorio | Rol Arquitectónico | Documentación Destacada |
| :--- | :--- | :--- | :--- |
| **Catálogo** (`catalog-service`) | [hmunoz/springboot-sso](https://github.com/hmunoz/springboot-sso) `/catalog-service` | Resource Server OAuth2/OIDC del catálogo de películas y servidor MCP propio | [docs/](https://github.com/hmunoz/springboot-sso/tree/2026/docs) (este hub) |
| **Membresía** (`membership-service`) | [hmunoz/springboot-sso](https://github.com/hmunoz/springboot-sso) `/membership-service` | Resource Server OAuth2/OIDC de socios y usuarios, consumidor de eventos RabbitMQ, notificaciones SSE y servidor MCP propio | [docs/](https://github.com/hmunoz/springboot-sso/tree/2026/docs) (este hub) |
| **API Gateway** | [munozhoracio/apigateway](https://github.com/munozhoracio/apigateway) | Spring Cloud Gateway (GraalVM Native), punto de entrada único (Data Plane), CORS global y fachada orientada a recursos | [README](https://github.com/munozhoracio/apigateway#readme) · [Guía de Arquitectura](api-gateway.md) |
| **Backend Agente IA** | [munozhoracio/agente-videoclub-sso](https://github.com/munozhoracio/agente-videoclub-sso) | Spring AI, microservicio asistente, cliente MCP con Token Relay, Generative UI con SSE | [README](https://github.com/munozhoracio/agente-videoclub-sso#readme) · [ADRs de IA](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/adr.md) |
| **Frontend SPA** | [hmunoz/react-sso](https://github.com/hmunoz/react-sso) | React 19 + Vite, autenticación OIDC/PKCE, RBAC de grano fino, streaming de chat | [README](https://github.com/hmunoz/react-sso#readme) |

---

## 📚 Índice Maestro de Documentación

### 🏛️ 1. Registros de Decisiones de Arquitectura (ADR)

* **[ADR-001 a ADR-015 — Decisiones de Plataforma y Backend Core](adr.md)**:
  * *ADR-001 a ADR-005:* Mensajería AMQP, topología de exchanges, consistencia eventual y sincronización del dominio de Socios.
  * *ADR-006 a ADR-007:* API Gateway, enrutamiento desacoplado orientado a recursos y aislamiento del plano de identidad.
  * *ADR-008 a ADR-012:* Servidor Model Context Protocol (MCP), transporte Streamable HTTP, autorización `@PreAuthorize` en las propias tools y soporte dual para Claude Code y Antigravity IDE.
  * *ADR-013 a ADR-014:* Separación en dos microservicios (Database per Service y comunicación este-oeste por bus).
  * *ADR-015:* Sincronización entre verticales mediante Event-Carried State Transfer (ECST) y réplica de proyecciones locales (ej. actualización de precios de Catálogo hacia Carrito de Compras).
* **[ADR-013 a ADR-024 — Decisiones del Agente IA (Microservicio Asistente)](adr-agente.md)**:
  * *ADR-013 a ADR-015:* Agente como microservicio independiente, Token Relay sin fallback y bootstrap de descubrimiento MCP.
  * *ADR-016 a ADR-018:* Supervisor jerárquico, fail-fast ante ausencia de tools y propagación de denegaciones de permisos.
  * *ADR-019 a ADR-024:* Memoria conversacional, Generative UI híbrido, respuesta bloqueante/streaming y GraalVM Native Image.

---

### 🗺️ 2. Vista de conjunto

* **[Arquitectura de la Plataforma (Modelo C4)](arquitectura-c4.md)**:
  Diagramas de **Contexto** (quién usa el sistema y con qué sistemas externos habla) y de **Contenedores** (qué procesos corren, con qué tecnología y cómo se comunican). Es el mapa que da sentido a los documentos por dominio: si es la primera vez que mirás el sistema, empezá acá.

---

### ⚙️ 3. Backend Core: Datos, Seguridad y Eventos (`springboot-sso`)

**[Arquitectura de Dos Servicios: Decisiones y Trampas](arquitectura-dos-servicios.md)** — documento de referencia del corte. Decisiones D1–D7 (por qué no hay multi-módulo Maven, por qué las clases cross-cutting se duplican, cómo se reparten los clients de Keycloak) y trampas C1–C10: cosas que compilan, arrancan y fallan en runtime o en silencio. Es también el destino de los comentarios `Duplicated by design` del código.

#### Reparto de responsabilidades

| | `catalog-service` | `membership-service` |
| :--- | :--- | :--- |
| Puerto | `8081` | `8082` |
| Base de datos | `video_catalog` | `video_membership` |
| Package base | `ar.unrn.video.catalog` | `ar.unrn.video.membership` |
| Rutas REST | `/movies/**` | `/api/socios/**`, `/api/users/**`, `/api/notifications/**` |
| Servidor MCP | `:8081/mcp` — `list_movies`, `get_movie`, `search_movies`, `create_movie` | `:8082/mcp` — `list_socios`, `get_socio` |
| RabbitMQ | starter presente, sin topología propia todavía | consume `keycloak.events`, publica en `videoclub.events` |
| Keycloak Admin API | **no** | sí (client confidencial `videoclub-backend`) |

Ninguno de los dos necesita estar registrado como client en Keycloak para **validar** tokens: a un Resource Server le alcanza con el `issuer-uri` y las claves públicas. El client confidencial lo necesita solo `membership-service`, que es el único que **llama** a la Admin REST API. Por eso `KEYCLOAK_BACKEND_CLIENT_SECRET` no llega al contenedor de catálogo.

> [!WARNING]
> **Los dos servicios leen el mismo claim `resource_access`, sin mirar de qué client vino cada rol.** El corte no hizo el token más angosto: lo único que impide que un lector del catálogo lea el padrón de socios es el `@PreAuthorize` dentro de cada servicio. Es deuda conocida y está analizada en la [sección de Keycloak](arquitectura-dos-servicios.md#keycloak-reparto-de-clients-decisión-d5).

#### Documentación por dominio

* **[Seguridad, OAuth2 y OpenID Connect con Keycloak](seguridad-oauth2-openid-connect-keycloak.md)**:
  Fundamentos teóricos de OAuth2/OIDC, flujos PKCE vs. Client Credentials, mapeo de claims a authorities con `KeycloakGrantedAuthoritiesConverter` y validación criptográfica de tokens JWT.
* **[CORS: Configuración, Capas y Diagnóstico](CORS.md)**:
  Anatomía del preflight `OPTIONS`, separación de capas (Keycloak, Gateway, Backend), resolución de cabeceras duplicadas W3C con `DedupeResponseHeader`, la trampa de `allowCredentials(true)` vs. `allowedOriginPatterns` y árbol de resolución con `curl`.
* **[Servidor Model Context Protocol (MCP) con Spring AI](mcp-server.md)**:
  Servidor MCP sobre Streamable HTTP (`POST /mcp`) en modo `STATELESS`, autorización granular basada en roles (`usuarioadmin` vs. `usuariocliente`) e integración con clientes de IA.
  *Tras el corte hay **dos** servidores MCP, uno por servicio, cada uno anunciando su propio recurso en `/.well-known/oauth-protected-resource`. El documento todavía describe el servidor único del monolito; el mecanismo es idéntico, cambian el puerto y el reparto de tools.*
* **[MCP Resources y MCP Prompts](mcp-resources-prompts.md)**:
  Implementación de las tres primitivas de MCP: Resources (`@McpResource`) para inyección de contexto de catálogo y socios sin tool-calling, Prompts (`@McpPrompt`) para flujos guiados y consumo especializado en sub-agentes.
* **[Gestión y Sincronización de Socios](socios.md)**:
  Modelo de dominio JPA, baja lógica (Soft Delete), procesamiento asíncrono con *Event-Carried State Transfer*, consumidor idempotente y Dead Letter Queue (`socio.events.dlq`).
* **[Integración Keycloak, RabbitMQ y Anti-Corruption Layer (ACL)](keycloak-rabbitmq-integration.md)**:
  Compilación del plugin Keycloak RabbitMQ SPI, separación de exchanges técnicos (`keycloak.events`) vs. canónicos (`videoclub.events`) y normalización de payloads heterogéneos con Anti-Corruption Layer.

---

### 🌐 4. Entrada, Enrutamiento y CORS (`apigateway`)

* **[Arquitectura y Configuración del API Gateway](api-gateway.md)**:
  Especificación de la fachada orientada a recursos, aislamiento del puerto de Keycloak (`:9090`) para preservar el claim `iss`, resolución de CORS en el borde y pruebas comparativas directas vs. Gateway.
* **[Guía Operativa del Repositorio API Gateway](https://github.com/munozhoracio/apigateway#readme)**:
  Despliegue contenerizado, compilación de binario nativo con GraalVM Native Image y healthchecks con Spring Boot Actuator.

---

### 🤖 5. Inteligencia Artificial, Streaming y UI Generativa (`agente-videoclub-sso`)

* **[Propagación de Tokens SSO (Token Relay)](sso-token-propagation.md)**:
  Arquitectura para reenviar el Bearer JWT del usuario autenticado desde el frontend hacia el Agente y de este hacia las tools MCP del backend, preservando la identidad del usuario final en llamadas intermediadas por IA.
* **[Integración Gateway, Token Relay y Chat React](gateway-agent-integration.md)**:
  Ruta `/api/agent/**` en el Gateway, enrutamiento Netty hacia el microservicio en `:8085` y consumo desde el frontend.
* **[Patrón Generative UI con SSE Streaming](generative-ui-pattern.md)**:
  Emisión de tokens en tiempo real combinada con payload estructurado para que el frontend en React renderice componentes interactivos (fichas de películas, formularios) a medida que el LLM genera respuestas.
* **[Plan de Streaming AG-UI](plan/agui-streaming-plan.md)**:
  Hoja de ruta y diseño técnico del protocolo de streaming de eventos para asistentes conversacionales.
* **[Plan: Asesor Cinéfilo con un servidor MCP propio sobre TMDB](plan/plan-asesor-cinefilo-tmdb.md)**:
  Plan sin implementar. Conocimiento de cine de mundo abierto como proyecto MCP nuevo (`cinephile-service`), sub-agente de solo lectura, colaboración entre sub-agentes para el alta asistida de películas, y por qué se descartó el servidor MCP de IMDb.

> [!NOTE]
> **El agente pasó de un cliente MCP a dos.** `McpClientConfiguration` publica ahora cinco beans: un `McpSyncClient` por backend, un `SyncMcpToolCallbackProvider` con `@Qualifier` para cada uno —de modo que `CatalogSubAgent` no pueda ni ver las tools de socios— y un tercero `@Primary` que agrega ambos, que es el que responde `GET /api/agent/tools` con las 6 tools.
>
> Cada cliente tiene su **propia** ventana de descubrimiento (`AtomicBoolean` local). Compartir una sola haría que el `initialize()` del segundo cliente encontrara la ventana ya cerrada por el `finally` del primero, y el handshake de arranque fallaría. Los sub-agentes **no tienen una lista de nombres de tools**: cada uno usa todas las tools de su provider dedicado, y el límite del dominio queda en `McpClientConfiguration`. El fail-fast se mantiene si el provider no expone ninguna. Ver la decisión en la sección 10.5 de [mcp-resources-prompts.md](mcp-resources-prompts.md#105-decisión-los-sub-agentes-ya-no-tienen-una-lista-de-tools-escrita-a-mano).

---

### 💻 6. Frontend SPA: Interfaz y Experiencia de Usuario (`react-sso`)

* **[Documentación del Frontend en React 19](https://github.com/hmunoz/react-sso#readme)**:
  Implementación de `react-oidc-context` con PKCE, hook `usePermissions()`, protección de rutas con `PermissionGuard`, caché asíncrona con TanStack Query y componentes interactivos para el catálogo, usuarios y chat con el agente.

---

### 📋 7. Planes de Evolución y Arquitectura (`docs/plan/`)

Espacio centralizado donde residen los planes de diseño técnico y propuestas evolutivas para todos los componentes de la plataforma (independientemente del repositorio de destino):

* **[Plan: Asesor Cinéfilo con Servidor MCP sobre TMDB](plan/plan-asesor-cinefilo-tmdb.md)**:
  * **Problema:** Expande el conocimiento de cine del asistente desde un inventario cerrado a mundo abierto, permitiendo asesoramiento libre y alta asistida de películas en el catálogo del videoclub.
  * **Arquitectura:** Nuevo microservicio y servidor MCP (`cinephile-service`), sub-agente de solo lectura y colaboración inter-agentes (`CinephileSubAgent` + `CatalogSubAgent`).
* **[Plan: Human-in-the-Loop (HITL), Escalado a Operador y Guardrails](plan/plan-human-in-the-loop.md)**:
  * **Problema:** Permite la intervención humana ante consultas fuera de competencia del agente o para supervisar acciones críticas y destructivas de sub-agentes (bajas de socios, modificaciones sensibles).
  * **Arquitectura:** Dos patrones evaluados: (1) *Live Operator Hand-off* con RabbitMQ y SSE, y (2) *Action Approval Guardrails* con UI Generativa. Gobierno estricto mediante PBAC con el permiso `agent-permission-hitl` (sin `hasRole`), asignable al grupo `administrador`.
* **[Plan: Integración de Streaming AG-UI](plan/agui-streaming-plan.md)**:
  * **Problema:** Evaluación de protocolos y SDKs para streaming de eventos de agente (AG-UI vs SSE puro), y resolución del desacople de identidad en hilos reactivos antes de migrar a streaming completo.
  * **Arquitectura:** Análisis de coordenadas de dependencias, contención de seguridad sobre `ThreadLocal` / `SecurityContextHolder` y secuenciación en dos rutas de migración.

---

## 🛠️ Servicios de Infraestructura (Docker Compose)

El entorno de plataforma se administra de forma orquestada mediante `docker/services.yaml`:

```bash
docker compose -f docker/services.yaml up -d
```

| Servicio | Contenedor | Puerto Local | Descripción |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `video-postgresql` | `5432` | Motor relacional. Aloja **dos** bases: `video_catalog` y `video_membership`. |
| **Keycloak** | `video-keycloak` | `9090` | Identity Provider OAuth2/OIDC con Realm `videoclub`. |
| **RabbitMQ** | `videoclub-rabbit-1` | `5672` / `15672` | Broker AMQP con consola de administración. |
| **API Gateway** | `videoclub-gateway-1` | `9500` | Spring Cloud Gateway (fachada de microservicios). |
| **Mailhog** | `mailhog` | `1025` / `8025` | Servidor SMTP simulado con interfaz web para emails. |

Las dos bases las crea `docker/postgresql/init.sql`, montado en `/docker-entrypoint-initdb.d/`.

> [!CAUTION]
> PostgreSQL ejecuta ese script **solo cuando el directorio de datos está vacío**. Sobre un volumen que ya existe lo ignora sin ningún aviso. Para forzarlo:
>
> ```bash
> docker compose -f docker/services.yaml down -v   # destruye los datos existentes
> docker compose -f docker/services.yaml up -d
> ```
>
> Cada servicio crea su propio esquema al arrancar (`spring.jpa.hibernate.ddl-auto=update`), pero **no hay datos semilla**: tras un `down -v` el catálogo queda vacío.

---

## 🚀 Servicios de Aplicación

No forman parte de `docker/services.yaml`: cada uno se levanta desde su propio repositorio.

| Servicio | Puerto | Se levanta con |
| :--- | :--- | :--- |
| `catalog-service` | `8081` | `cd catalog-service && ./mvnw spring-boot:run`, o `docker compose up -d` desde la raíz |
| `membership-service` | `8082` | `cd membership-service && ./mvnw spring-boot:run`, o `docker compose up -d` desde la raíz |
| Agente IA | `8085` | repositorio `agente-videoclub-sso` |
| Frontend SPA | `5173` | repositorio `react-sso` |

> [!NOTE]
> Los `application.yml` de ambos servicios traen `spring.docker.compose` **apagado** por defecto. En el monolito estaba prendido y levantaba la infraestructura solo; con dos servicios, los dos intentarían levantar el mismo stack a la vez. La infraestructura se arranca a mano, con el comando de la sección anterior.

### Ruteo del Gateway

| Ruta | Destino |
| :--- | :--- |
| `/movies/**` | `catalog:8081` |
| `/api/socios/**`, `/api/users/**`, `/api/notifications/**` | `membership:8082` |
| `/api/agent/**` | `agent:8085` |

Los hostnames son las **claves de servicio** de los compose, que es lo que resuelve el DNS interno de Docker. Si cambian ahí, hay que cambiarlas también en `docker/gateway/gateway.yml` y en los compose del agente.

---

## 📌 Estado de esta documentación

Todos los documentos de este hub están **alineados con la arquitectura de dos servicios**. Los `:8080` que quedan en [CORS.md](CORS.md) §5.3 y §5.4 son deliberados: son post-mortems de configuraciones pasadas y cambiarles el puerto falsificaría lo que ocurrió.

| Documento | Estado |
| :--- | :--- |
| [arquitectura-c4.md](arquitectura-c4.md) | Diagramas C4 de contexto y contenedores. |
| [adr.md](adr.md) | ADR-001 a ADR-014. Los dos últimos registran la estrategia de microservicios. |
| [arquitectura-dos-servicios.md](arquitectura-dos-servicios.md) | Decisiones D1–D7 y trampas C1–C10. |
| [mcp-server.md](mcp-server.md) | Dos servidores MCP, uno por servicio. |
| [mcp-resources-prompts.md](mcp-resources-prompts.md) | Primitivas MCP (Tools, Resources, Prompts) en catálogo, membresía y agente. |
| [socios.md](socios.md) | `membership-service`, base `video_membership`. |
| [CORS.md](CORS.md) | Capas y diagnóstico sobre `:8081` / `:8082`. |
| [seguridad-oauth2-openid-connect-keycloak.md](seguridad-oauth2-openid-connect-keycloak.md) | Swagger por servicio y el caso del `redirect_uri`. |
| [api-gateway.md](api-gateway.md) | Tabla de ruteo a `catalog` y `membership`. |
| [keycloak-rabbitmq-integration.md](keycloak-rabbitmq-integration.md) | Topología AMQP con diagramas. |
| [adr-agente.md](adr-agente.md) | ADR-013 a ADR-024. Decisiones de arquitectura del Agente IA. |
| [gateway-agent-integration.md](gateway-agent-integration.md) | Integración Gateway, Token Relay y chat en React. |
| [sso-token-propagation.md](sso-token-propagation.md) | Propagación de identidad y JWT (Token Relay) hacia MCP. |
| [generative-ui-pattern.md](generative-ui-pattern.md) | Patrón de UI Generativa híbrida con componentes React. |
| [plan/plan-asesor-cinefilo-tmdb.md](plan/plan-asesor-cinefilo-tmdb.md) | Plan: Servidor MCP sobre TMDB y sub-agente cinéfilo. |
| [plan/plan-human-in-the-loop.md](plan/plan-human-in-the-loop.md) | Plan: Soporte HITL, hand-off vía RabbitMQ y guardrails PBAC. |
| [plan/agui-streaming-plan.md](plan/agui-streaming-plan.md) | Plan: Evaluación de streaming AG-UI y propagación reactiva. |
