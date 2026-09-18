# Arquitectura y Configuración del API Gateway

Este documento describe la arquitectura, decisiones de diseño, configuración de red, manejo de CORS y estrategias de prueba del **API Gateway** en la plataforma VideoClub.

---

## 1. Rol y Propósito del API Gateway

El API Gateway actúa como el **punto único de entrada perimetral (Single Point of Entry)** para los clientes externos (como la aplicación SPA en React) hacia los servicios internos del backend. Gestiona exclusivamente el tráfico **Norte-Sur**.

```mermaid
flowchart TD
    subgraph Exterior ["Tráfico Norte-Sur (Vía API Gateway)"]
        SPA["Frontend SPA (React)<br/>http://localhost:5173"]
        GW["Spring Cloud Gateway<br/>http://localhost:9500"]
    end

    subgraph Identity ["Plano de Identidad (Independiente)"]
        KC["Keycloak (IdP / Autoridad)<br/>http://localhost:9090"]
    end

    subgraph Internal ["Tráfico Este-Oeste / Red Interna (SIN API Gateway)"]
        AG["videoclub-agent<br/>http://agent:8085"]
        CAT["catalog-service<br/>http://catalog:8081"]
        MEM["membership-service<br/>http://membership:8082"]
        RABBIT[("RabbitMQ Message Broker<br/>amqp://rabbitmq:5672")]
    end

    SPA -- "1. Autenticación OIDC (Directo)" --> KC
    SPA -- "2. Peticiones HTTP con Bearer JWT" --> GW
    
    GW -- "/movies/**" --> CAT
    GW -- "/api/socios/**" --> MEM
    GW -- "/api/users/**" --> MEM
    GW -- "/api/notifications/** (SSE)" --> MEM
    GW -- "/api/agent/**" --> AG

    AG -.->|"Llamada MCP directa (Streamable HTTP)<br/>POST http://catalog:8081/mcp"| CAT
    AG -.->|"Llamada MCP directa (Streamable HTTP)<br/>POST http://membership:8082/mcp"| MEM
    CAT <==>|"Eventos de dominio asíncronos"| RABBIT
    MEM <==>|"Eventos de dominio asíncronos"| RABBIT
```

### Principios Fundamentales

1. **Desacoplamiento Topológico:** Los clientes frontales desconocen direcciones IP, puertos internos y topología de red de los microservicios.
2. **Fachada Orientada a Recursos (Resource-Oriented Facade):** Las URLs expuestas al frontend representan entidades del dominio (`/movies`, `/api/socios`, `/api/agent`), no nombres de servicios o contenedores.
3. **Evolución sin Ruptura:** Permite extraer módulos a microservicios independientes cambiando únicamente la regla de ruteo del Gateway, sin alterar una sola línea de código en el frontend.

---

### 1.1 Cuándo se usa el API Gateway y cuándo NO (Tráfico Norte-Sur vs. Este-Oeste)

Para evitar convertir el Gateway en un cuello de botella o introducir saltos de red espurios, la arquitectura define con precisión qué flujos atraviesan el Gateway y cuáles se comunican directamente:

| Caso de Uso | ¿Pasa por el Gateway? | Tipo de Tráfico | Destino / Protocolo | Justificación Técnica |
| :--- | :---: | :--- | :--- | :--- |
| **Frontend React → Catálogo / Socios** | **SÍ** | Norte-Sur | `http://localhost:9500/movies`<br/>`http://localhost:9500/api/socios` | Punto único de entrada, centralización de política CORS, fachada común de URLs y desacople de puertos. |
| **Frontend React → Chat con Agente IA** | **SÍ** | Norte-Sur | `http://localhost:9500/api/agent/chat` | Evita llamadas cross-origin directas del browser al agente (:8085). El chat se expone como un recurso más de la plataforma. |
| **Agente IA → Servidores MCP de Dominio** | **NO** | Este-Oeste | `http://catalog:8081/mcp`<br/>`http://membership:8082/mcp` | **Comunicación interna directa:** ambos residen en la misma red Docker (`videoclub_default`). Pasar por el Gateway agregaría latencia innecesaria en cada tool call del LLM y expondría endpoints de backoffice que no deben ser públicos. |
| **Microservicio a Microservicio de Dominio** | **NO** | Este-Oeste | `amqp://rabbitmq:5672` | Desacoplamiento temporal y de resiliencia: la sincronización entre `catalog-service` y `membership-service` se realiza mediante mensajería asíncrona (RabbitMQ), sin llamadas HTTP sincrónicas. |
| **Cualquier componente → Keycloak SSO** | **NO** | Identidad | `http://localhost:9090` (externo)<br/>`http://keycloak:9090` (interno) | **Separación de planos:** Keycloak opera en el plano de control de identidad. Los navegadores hacen OIDC directo; los microservicios resuelven JWKS directamente para evitar que el claim `iss` (Issuer) se corrompa por reescritura de URLs. |

#### ¿Por qué la conexión Agente ↔ MCP es estrictamente interna?

1. **Aislamiento Perimetral (Least Privilege):** Los endpoints `/mcp` (`catalog:8081/mcp` y `membership:8082/mcp`) exponen primitivas de control y ejecución de herramientas (`tools/call`, `resources/read`). No son APIs públicas para el usuario final ni deben ser alcanzables directamente desde el navegador a través del Gateway.
2. **Latencia e Innecesarios Saltos de Red (Hop Penalty):** Durante una interacción conversacional, el orquestador del agente puede ejecutar múltiples herramientas encadenadas (ej. consultar socios y verificar disponibilidad de títulos). Cada tool invocation es una petición HTTP JSON-RPC síncrona; agregar un proxy intermedio añadiría sobrecarga de red y re-procesamiento de headers sin valor agregado.
3. **Propagación de Seguridad Punto a Punto:** El agente ya implementa el patrón **Token Relay** ([sso-token-propagation.md](sso-token-propagation.md)): captura el JWT del usuario llamante (`Bearer <user_token>`) y lo inyecta directamente en la cabecera `Authorization` de la petición HTTP hacia `catalog:8081/mcp` o `membership:8082/mcp`. El microservicio receptor valida la firma contra Keycloak y ejecuta el `@PreAuthorize` sobre el método `@McpTool` exactamente igual que si viniera del Gateway.

---

## 2. Decisiones de Arquitectura (ADR)

### ADR 1: Rutas Basadas en Recursos vs. Prefijos de Servicio

* **Contexto:** Inicialmente se consideró utilizar prefijos como `/catalogo/movies/**` o fijar `VITE_API_BASE_URL=http://localhost:9500/catalogo`.
* **Problema:** Si el frontend ata su base URL a `/catalogo`, no puede consumir otros servicios (`/socios`, `/alquileres`) a través del mismo Gateway sin múltiples instancias de clientes HTTP o refactorizaciones de código.
* **Decisión:** Implementar **Resource-Based Routing**:
  * Base URL del frontend: `http://localhost:9500`
  * El frontend invoca `/movies`, `/api/socios`, `/api/users`, `/api/notifications`.
  * El Gateway inspecciona el path y despacha al servicio correspondiente.
* **Beneficio:** Transición transparente de monolito modular a microservicios. Cuando el módulo `Socio` se convierta en un servicio independiente en el puerto `8082`, solo se modifica la URI de destino en el Gateway.

---

### ADR 2: Separación del Plano de Identidad (Keycloak fuera del Gateway)

* **Pregunta:** *¿Debería enrutarse Keycloak a través del API Gateway (ej. `/auth/**`)?*
* **Decisión:** **NO**. Keycloak debe mantenerse en su propio puerto y endpoint de autoridad (`:9090`).
* **Justificación Técnica:**
  1. **Separación de Planos:** Keycloak opera en el **Identity Control Plane** (emisión de tokens, flujos OIDC, consentimiento). El Gateway opera en el **Data Plane** (orquestación y ruteo de peticiones de negocio).
  2. **Validación del Claim `iss` (Issuer):** Según la especificación OpenID Connect Core 1.0, los tokens JWT contienen el claim obligatorio `iss` (ej. `http://localhost:9090/realms/videoclub`). Los Resource Servers (Spring Boot) validan que el `iss` coincida exactamente con el emisor configurado en `spring.security.oauth2.resourceserver.jwt.issuer-uri`. Si las peticiones pasan por el Gateway bajo otra URL, se producen discrepancias que provocan `JwtValidationException: Invalid issuer`.
  3. **Flujo Canónico OAuth2/OIDC:**
     * El navegador interactúa directamente con Keycloak para login, redirecciones y refresh tokens.
     * El navegador adjunta el `access_token` resultante en el header `Authorization: Bearer <token>` hacia el Gateway.

```mermaid
sequenceDiagram
    autonumber
    actor User as Usuario / Browser
    participant SPA as React SPA (:5173)
    participant KC as Keycloak (:9090)
    participant GW as API Gateway (:9500)
    participant CAT as catalog-service (:8081)

    User->>SPA: Accede a la aplicación
    SPA->>KC: Redirección OAuth2 / Token request
    KC-->>SPA: Retorna JWT (iss: http://localhost:9090/...)
    Note over SPA,GW: El frontend solo usa el Gateway para llamadas de negocio
    SPA->>GW: GET /movies (Header: Bearer JWT)
    GW->>CAT: Proxy inverso a http://catalog:8081/movies
    CAT->>CAT: Valida firma de JWT contra jwks_uri de Keycloak
    CAT-->>GW: 200 OK [Listado de Películas]
    GW-->>SPA: 200 OK [Listado de Películas]
```

---

## 3. Manejo de CORS y Headers Duplicados

### El Problema de la Duplicación de Cabeceras
Cuando tanto Spring Boot (`SecurityConfiguration`) como Spring Cloud Gateway agregan la cabecera `Access-Control-Allow-Origin: http://localhost:5173`, la respuesta HTTP contiene dos valores idénticos o concatenados por coma:
```http
Access-Control-Allow-Origin: http://localhost:5173, http://localhost:5173
```
La especificación W3C / Fetch Standard prohíbe valores múltiples en `Access-Control-Allow-Origin`. Al recibir esto, el navegador bloquea la petición por violación de CORS, afectando tanto llamadas `fetch` como conexiones persistentes de Server-Sent Events (`EventSource`).

### Regla W3C: Wildcard vs. Credenciales
La especificación establece que cuando `Access-Control-Allow-Credentials` es `true`, el valor de `Access-Control-Allow-Origin` **no puede ser `*`**.

### Solución Implementada
1. **`allowedOriginPatterns: "*"`:** Permite cualquier origen sin violar la especificación, ya que Spring refleja dinámicamente el origen que emitió la petición (`Origin: http://localhost:5173`).
2. **Filtro `DedupeResponseHeader`:** Elimina cabeceras CORS duplicadas generadas tanto por el Gateway como por los microservicios aguas abajo.

Fragmento de configuración en `docker/gateway/gateway.yml`:
```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          globalcors:
            cors-configurations:
              '[/**]':
                allowedOriginPatterns: "*"
                allowedMethods: "*"
                allowedHeaders: "*"
                allowCredentials: true
          routes:
            - id: service-catalogo
              uri: http://catalog:8081
              predicates:
                - Path=/movies/**
              filters:
                - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE
```

---

## 4. Despliegue con Docker Compose

El Gateway utiliza la imagen compilada con **GraalVM Native Image** (`registry.gitlab.com/public-unrn/apigateway:1.0`).

### Consideración sobre Imágenes Nativas (AOT)
Las imágenes GraalVM AOT tienen un classpath estático e inmutable en tiempo de ejecución. Filtros como `RequestRateLimiter` requieren que la dependencia reactiva de Redis (`spring-boot-starter-data-redis-reactive`) y los beans asociados (`KeyResolver`) hayan sido analizados y compilados en el artefacto nativo. No se pueden inyectar dinámicamente si la imagen base no los incluye.

### Definición del Servicio en `docker/apigateway.yaml`
```yaml
services:
  gateway:
    image: registry.gitlab.com/public-unrn/apigateway:1.0
    container_name: videoclub-gateway-1
    ports:
      - "9500:9500"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - ./gateway/gateway.yml:/workspace/config/application.yml:ro
```

* **`extra_hosts`:** Permite que el contenedor del Gateway resuelva `host.docker.internal` hacia el host de desarrollo. Desde la separación en dos servicios ya no se usa para el backend: `catalog` y `membership` corren en la misma red `videoclub_default` y se resuelven por nombre de servicio.
* **Volumen `/workspace/config/application.yml`:** Monta el archivo externo de rutas y CORS sin necesidad de lidiar con el formateo sensible de variables de entorno en listas y mapas YAML.

---

## 5. Tabla de Rutas Activas

| ID de Ruta | Predicado (Path) | Destino Interno | Filtros Aplicados |
| :--- | :--- | :--- | :--- |
| `service-catalogo` | `/movies/**` | `http://catalog:8081` | `DedupeResponseHeader` |
| `service-socios` | `/api/socios/**` | `http://membership:8082` | `DedupeResponseHeader` |
| `service-users` | `/api/users/**` | `http://membership:8082` | `DedupeResponseHeader` |
| `service-notificaciones` | `/api/notifications/**` | `http://membership:8082` | `DedupeResponseHeader` |
| `agent-service` | `/api/agent/**` | `http://agent:8085` | `DedupeResponseHeader` |

> [!NOTE]
> Los destinos son **nombres de servicio de Docker Compose**, no `host.docker.internal`. La clave del servicio en el compose es el hostname DNS dentro de `videoclub_default`, y por eso el gateway resuelve igual en desarrollo y en producción. Si cambia ahí, hay que cambiarla también acá.
>
> Todo este ruteo es tráfico **norte-sur** (cliente → servicio). Entre `catalog-service` y `membership-service` no hay tráfico HTTP: se comunican por el bus de RabbitMQ. Ver ADR-014.

---

## 6. Verificación y Testing

### Configuración del Frontend (`react-sso/.env`)
```env
VITE_API_BASE_URL=http://localhost:9500
```

### Pruebas Automatizadas con Cliente HTTP
Las pruebas se encuentran centralizadas en [VideoClub con Seguridad.http](file:///home/horacio/proyectos/unrn/taller/springboot-sso/postman/VideoClub%20con%20Seguridad.http):

1. **Prueba Directa a `catalog-service` (:8081), salteando el Gateway:**
   ```http
   GET http://localhost:8081/movies
   Authorization: Bearer {{access_token}}
   ```
2. **Prueba a través del API Gateway (:9500):**
   ```http
   GET http://localhost:9500/movies
   Authorization: Bearer {{access_token}}
   ```
3. **Soporte Server-Sent Events (SSE):**
   ```bash
   curl -N -H "Accept: text/event-stream" http://localhost:9500/api/notifications/stream
   ```
