# Documentación de Arquitectura — VideoClub Platform

Bienvenido a la documentación técnica, arquitectónica y operativa de la plataforma VideoClub. Esta documentación refleja el estado del sistema en producción y desarrollo, estructurada por dominios de arquitectura y respaldada por registros formales de decisiones de diseño (ADRs).

---

## 🗺️ Ecosistema de la Plataforma

La plataforma VideoClub está organizada en microservicios desacoplados y servicios satélite, cada uno alojado en su propio repositorio con contratos bien definidos:

| Componente | Repositorio | Rol Arquitectónico | Documentación Destacada |
| :--- | :--- | :--- | :--- |
| **Backend Core / Resource Server** | [hmunoz/springboot-sso](https://github.com/hmunoz/springboot-sso) | Resource Server OAuth2/OIDC, catálogo de películas, sincronización de socios, eventos RabbitMQ y servidor MCP | [docs/](https://github.com/hmunoz/springboot-sso/tree/2026/docs) (este hub) |
| **API Gateway** | [munozhoracio/apigateway](https://github.com/munozhoracio/apigateway) | Spring Cloud Gateway (GraalVM Native), punto de entrada único (Data Plane), CORS global y fachada orientada a recursos | [README](https://github.com/munozhoracio/apigateway#readme) · [Guía de Arquitectura](api-gateway.md) |
| **Backend Agente IA** | [munozhoracio/agente-videoclub-sso](https://github.com/munozhoracio/agente-videoclub-sso) | Spring AI, microservicio asistente, cliente MCP con Token Relay, Generative UI con SSE | [README](https://github.com/munozhoracio/agente-videoclub-sso#readme) · [ADRs de IA](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/adr.md) |
| **Frontend SPA** | [hmunoz/react-sso](https://github.com/hmunoz/react-sso) | React 19 + Vite, autenticación OIDC/PKCE, RBAC de grano fino, streaming de chat | [README](https://github.com/hmunoz/react-sso#readme) |

---

## 📚 Índice Maestro de Documentación

### 🏛️ 1. Registros de Decisiones de Arquitectura (ADR)

* **[ADR-001 a ADR-012 — Decisiones de Plataforma y Backend Core](adr.md)**:
  * *ADR-001 a ADR-005:* Mensajería AMQP, topología de exchanges, consistencia eventual y sincronización del dominio de Socios.
  * *ADR-006 a ADR-007:* API Gateway, enrutamiento desacoplado orientado a recursos y aislamiento del plano de identidad.
  * *ADR-008 a ADR-012:* Servidor Model Context Protocol (MCP), transporte Streamable HTTP, autorización `@PreAuthorize` en las propias tools y soporte dual para Claude Code y Antigravity IDE.
* **[ADR-001 a ADR-007 — Decisiones del Agente IA (Microservicio Asistente)](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/adr.md)**:
  * *ADR-001 a ADR-003:* Arquitectura de Spring AI, integración con OpenAI/modelos locales y cliente MCP dinámico.
  * *ADR-004 a ADR-005:* Propagación segura de identidad (Token Relay / SSO) hacia el Resource Server.
  * *ADR-006 a ADR-007:* Streaming de componentes visuales (Generative UI) y protocolo de eventos Server-Sent Events (SSE).

---

### ⚙️ 2. Backend Core: Datos, Seguridad y Eventos (`springboot-sso`)

* **[Seguridad, OAuth2 y OpenID Connect con Keycloak](seguridad-oauth2-openid-connect-keycloak.md)**:
  Fundamentos teóricos de OAuth2/OIDC, flujos PKCE vs. Client Credentials, mapeo de claims a authorities con `KeycloakGrantedAuthoritiesConverter` y validación criptográfica de tokens JWT.
* **[CORS: Configuración, Capas y Diagnóstico](CORS.md)**:
  Anatomía del preflight `OPTIONS`, separación de capas (Keycloak, Gateway, Backend), resolución de cabeceras duplicadas W3C con `DedupeResponseHeader`, la trampa de `allowCredentials(true)` vs. `allowedOriginPatterns` y árbol de resolución con `curl`.
* **[Servidor Model Context Protocol (MCP) con Spring AI](mcp-server.md)**:
  Servidor MCP sobre Streamable HTTP (`POST /mcp`) en modo `STATELESS`, catálogo de tools (`get_movie`, `search_movies`, `get_socio`, `list_socios`), autorización granular basada en roles (`usuarioadmin` vs. `usuariocliente`) e integración con clientes de IA.
* **[Gestión y Sincronización de Socios](socios.md)**:
  Modelo de dominio JPA, baja lógica (Soft Delete), procesamiento asíncrono con *Event-Carried State Transfer*, consumidor idempotente y Dead Letter Queue (`socio.events.dlq`).
* **[Integración Keycloak, RabbitMQ y Anti-Corruption Layer (ACL)](keycloak-rabbitmq-integration.md)**:
  Compilación del plugin Keycloak RabbitMQ SPI, separación de exchanges técnicos (`keycloak.events`) vs. canónicos (`videoclub.events`) y normalización de payloads heterogéneos con Anti-Corruption Layer.

---

### 🌐 3. Entrada, Enrutamiento y CORS (`apigateway`)

* **[Arquitectura y Configuración del API Gateway](api-gateway.md)**:
  Especificación de la fachada orientada a recursos, aislamiento del puerto de Keycloak (`:9090`) para preservar el claim `iss`, resolución de CORS en el borde y pruebas comparativas directas vs. Gateway.
* **[Guía Operativa del Repositorio API Gateway](https://github.com/munozhoracio/apigateway#readme)**:
  Despliegue contenerizado, compilación de binario nativo con GraalVM Native Image y healthchecks con Spring Boot Actuator.

---

### 🤖 4. Inteligencia Artificial, Streaming y UI Generativa (`agente-videoclub-sso`)

* **[Propagación de Tokens SSO (Token Relay)](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/sso-token-propagation.md)**:
  Arquitectura para reenviar el Bearer JWT del usuario autenticado desde el frontend hacia el Agente y de este hacia las tools MCP del backend, preservando la identidad del usuario final en llamadas intermediadas por IA.
* **[Integración Gateway, Token Relay y Chat React](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/gateway-agent-integration.md)**:
  Ruta `/api/agent/**` en el Gateway, enrutamiento Netty hacia el microservicio en `:8085` y consumo desde el frontend.
* **[Patrón Generative UI con SSE Streaming](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/generative-ui-pattern.md)**:
  Emisión de tokens en tiempo real combinada con payload estructurado para que el frontend en React renderice componentes interactivos (fichas de películas, formularios) a medida que el LLM genera respuestas.
* **[Plan de Streaming AG-UI](https://github.com/munozhoracio/agente-videoclub-sso/blob/main/docs/agui-streaming-plan.md)**:
  Hoja de ruta y diseño técnico del protocolo de streaming de eventos para asistentes conversacionales.

---

### 💻 5. Frontend SPA: Interfaz y Experiencia de Usuario (`react-sso`)

* **[Documentación del Frontend en React 19](https://github.com/hmunoz/react-sso#readme)**:
  Implementación de `react-oidc-context` con PKCE, hook `usePermissions()`, protección de rutas con `PermissionGuard`, caché asíncrona con TanStack Query y componentes interactivos para el catálogo, usuarios y chat con el agente.

---

## 🛠️ Servicios de Infraestructura (Docker Compose)

El entorno de plataforma se administra de forma orquestada mediante `docker/services.yaml`:

```bash
docker compose -f docker/services.yaml up -d
```

| Servicio | Contenedor | Puerto Local | Descripción |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `video-postgresql` | `5432` | Base de datos relacional de la aplicación (`video`). |
| **Keycloak** | `video-keycloak` | `9090` | Identity Provider OAuth2/OIDC con Realm `videoclub`. |
| **RabbitMQ** | `video-rabbit` | `5672` / `15672` | Broker AMQP con consola de administración. |
| **API Gateway** | `videoclub-gateway-1` | `9500` | Spring Cloud Gateway (fachada de microservicios). |
| **Mailhog** | `mailhog` | `1025` / `8025` | Servidor SMTP simulado con interfaz web para emails. |
