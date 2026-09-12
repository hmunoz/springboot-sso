# Documentación de Arquitectura — VideoClub Platform

Bienvenido a la documentación técnica, arquitectónica y operativa de la plataforma VideoClub. Esta documentación refleja el estado del sistema en producción y desarrollo, estructurada por temas y respaldada por registros formales de decisiones de diseño.

---

## 📚 Índice Temático

### 1. [Registros de Decisiones de Arquitectura (ADR)](adr.md)
Compilación completa de los 12 ADRs del sistema, detallando el contexto, opciones descartadas, decisiones adoptadas y trade-offs:
* **ADR-001 a ADR-005:** Mensajería, topología de exchanges, consistencia eventual y dominio de Socios.
* **ADR-006 a ADR-007:** API Gateway, enrutamiento desacoplado y separación de planos de identidad.
* **ADR-008 a ADR-012:** Servidor Model Context Protocol (MCP), Streamable HTTP, autorización sobre las propias tools y clientes de IA.

---

### 2. [Gestión y Sincronización de Socios](socios.md)
* Modelo de dominio JPA (`Socio`), claves UUID de Keycloak y política de **baja lógica (Soft Delete)**.
* Procesamiento asíncrono con **Event-Carried State Transfer** sobre la cola `socio.events.queue`.
* Consumidor idempotente, políticas de reintentos con backoff y aislamiento en Dead Letter Queue (`socio.events.dlq`).
* Proceso de reconciliación y backfill manual (`POST /api/socios/sync`).
* Endpoints REST protegidos e integración en tiempo real con el frontend en React vía SSE.

---

### 3. [Integración Keycloak, RabbitMQ y Anti-Corruption Layer (ACL)](keycloak-rabbitmq-integration.md)
* Arquitectura y compilación del plugin **Keycloak RabbitMQ SPI** (`docker/keycloak/keycloak-spi`).
* Topología de exchanges en RabbitMQ: aislamiento de eventos técnicos (`keycloak.events`) y eventos canónicos de negocio (`videoclub.events`).
* Implementación del **Anti-Corruption Layer (ACL)** en `KeycloakEventListener` para normalizar formatos heterogéneos de Keycloak (*snake_case* en registros vs. *camelCase* en admin events).
* Publicación confiable con `publisher-confirm-type: correlated`.
* Matriz de alineación unificada de configuración y variables de entorno.

---

### 4. [Arquitectura y Configuración del API Gateway](api-gateway.md)
* Implementación con **Spring Cloud Gateway** (imagen GraalVM Native Image).
* **Fachada orientada a recursos:** Desacoplamiento de nombres internos de servicios (`/movies`, `/api/socios`, `/api/users`, `/api/notifications`).
* Resolución de conflictos de **CORS y cabeceras duplicadas W3C** mediante `allowedOriginPatterns: "*"` y filtro `DedupeResponseHeader`.
* Justificación técnica sobre la exclusión de Keycloak del Gateway (prevención de `JwtValidationException: Invalid issuer`).
* Pruebas automatizadas directas vs. vía Gateway en el archivo de pruebas HTTP.

---

### 5. [Servidor Model Context Protocol (MCP) con Spring AI](mcp-server.md)
* Integración del servidor MCP mediante **Spring AI 2.x** sobre **Streamable HTTP en modo `STATELESS`** (`POST /mcp`).
* Catálogo de 5 herramientas de consulta de solo lectura (`get_movie`, `list_movies`, `search_movies`, `get_socio`, `list_socios`).
* **Autorización en la propia tool:** `@PreAuthorize` sobre cada método `@McpTool`, verificado contra el descubrimiento proxy-consciente de Spring AI 2.0.1; el riesgo asumido y su vuelta atrás están documentados y cubiertos por tests centinela.
* Control de acceso basado en identidad: consultas diferenciadas para perfil administrador (`usuarioadmin`) y perfil socio (`usuariocliente`).
* Integración dual con clientes de IA:
  * **Claude Code CLI:** Flujo interactivo OAuth 2.0 PKCE con descubrimiento RFC 9728 (`/.well-known/oauth-protected-resource`).
  * **Antigravity IDE:** Bridge stdio desatendido con renovación automática de tokens Keycloak (`.agents/scripts/mcp-bridge.py`).

---

### 6. [Seguridad, OAuth2 y OpenID Connect con Keycloak](seguridad-oauth2-openid-connect-keycloak.md)
* Guía de referencia exhaustiva sobre conceptos fundamentales de OAuth2 / OIDC.
* Configuración de Realms, Clientes (`videoclub-frontend`, `videoclub-backend`, `videoclub-mcp`) y Scopes.
* Conversión de Claims a Authorities mediante `KeycloakGrantedAuthoritiesConverter`.
* Estructura de tokens JWT y validación criptográfica en Spring Boot Resource Server.

---

## 🛠️ Servicios de Infraestructura (Docker Compose)

El entorno se administra de forma orquestada mediante `docker/services.yaml`:

```bash
docker compose -f docker/services.yaml up -d
```

| Servicio | Contenedor | Puerto Local | Descripción |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `video-postgresql` | `5432` | Base de datos relacional de la aplicación (`video`). |
| **Keycloak** | `video-keycloak` | `9091` | Identity Provider OAuth2/OIDC con Realm `videoclub`. |
| **RabbitMQ** | `video-rabbit` | `5672` / `15672` | Broker AMQP con consola de administración. |
| **API Gateway** | `videoclub-gateway-1` | `9500` | Spring Cloud Gateway (fachada de microservicios). |
| **Mailhog** | `mailhog` | `1025` / `8025` | Servidor SMTP simulado con interfaz web para emails. |
