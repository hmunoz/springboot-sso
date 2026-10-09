# Guía de Integración: Servidores MCP con Claude, ChatGPT y ngrok

Este documento detalla la arquitectura, configuración de seguridad, red y procedimientos para permitir que clientes remotos de Inteligencia Artificial (**Claude Desktop / Claude Web** y **ChatGPT Connectors**) se autentiquen mediante OAuth 2.0 (Keycloak) y consuman los servidores MCP (`catalog-service` y `membership-service`) utilizando **ngrok** con un dominio estático permanente y un reverse proxy interno.

---

## 1. Arquitectura de Integración

### 1.1. Topología de Red y Arquitectura Ingress + API Gateway

Dado que una cuenta estándar de ngrok provee **1 único dominio estático permanente**, se implementó un esquema desacoplado de dos capas:

1. **Edge Ingress (Nginx)**: Termina ngrok, atiende el tráfico de Keycloak (`/realms/**`) y delega todo el tráfico de negocio al Gateway.
2. **Spring Cloud Gateway (WebFlux / Netty :9500)**: Resuelve el enrutamiento interno hacia los microservicios (`catalog:8081`, `membership:8082`, `agent:8085`), centralizando observabilidad (OpenTelemetry, Prometheus, Tempo), CORS y reescritura de endpoints MCP.

```mermaid
flowchart TB
    subgraph Clients ["1. Clientes de IA (Nube / Desktop)"]
        ChatGPT["ChatGPT<br/>(Custom Connector)"]
        Claude["Claude Web / Desktop<br/>(Remote MCP)"]
    end

    subgraph Tunnel ["2. Ingress Público ngrok"]
        NGROK["ngrok Agent<br/>(emmanuel-hirsute-savvily.ngrok-free.dev)"]
    end

    subgraph DockerNet ["3. Red Docker Interna (videoclub_default)"]
        NGINX["videoclub-ngrok-proxy (Nginx)<br/>Edge Ingress :80"]

        subgraph KC ["video-keycloak (:8080)"]
            KCAuth["Keycloak SSO<br/>Realm videoclub"]
        end

        subgraph GW ["videoclub-gateway (:9500)"]
            SCG["Spring Cloud Gateway<br/>(Observabilidad, Netty SSE, CORS)"]
        end

        subgraph Cat ["videoclub-catalog-prod (:8081)"]
            CatFilter["McpDiscoverFilter"]
            CatTools["MovieMcpTools (@PreAuthorize)<br/>/mcp"]
        end

        subgraph Mem ["videoclub-membership-prod (:8082)"]
            MemFilter["McpDiscoverFilter"]
            MemTools["SocioMcpTools (@PreAuthorize)<br/>/mcp"]
        end
    end

    ChatGPT & Claude -->|HTTPS| NGROK
    NGROK -->|"HTTP :80"| NGINX

    NGINX -->|"/realms/**"| KCAuth
    NGINX -->|"Resto del trafico"| SCG

    SCG -->|"/catalog/mcp"| CatFilter
    CatFilter --> CatTools
    SCG -->|"/membership/mcp"| MemFilter
    MemFilter --> MemTools
```

### 1.2. Flujo de Interacción y Autenticación (Paso a Paso)

```mermaid
sequenceDiagram
    autonumber
    actor User as Usuario
    participant Client as Cliente IA (Claude / ChatGPT)
    participant Nginx as ngrok + Nginx Ingress
    participant GW as Spring Cloud Gateway (:9500)
    participant MCP as Catalog MCP (:8081)
    participant Keycloak as Keycloak (:8080)

    Note over Client,MCP: Paso 1: Descubrimiento de Seguridad (RFC 9728)
    Client->>Nginx: GET /.well-known/oauth-protected-resource/catalog/mcp
    Nginx->>GW: GET /.well-known/oauth-protected-resource/catalog/mcp
    GW->>MCP: GET /.well-known/oauth-protected-resource
    MCP-->>Client: 200 OK (authorization_servers: Keycloak URL, resource: /catalog/mcp)

    Note over Client,Keycloak: Paso 2: Flujo de Autorización OAuth 2.0 (PKCE)
    Client->>User: Redirige al navegador para login
    User->>Keycloak: Ingresa credenciales (usuarioadmin / usuarioadmin)
    Keycloak-->>Client: Retorna Authorization Code
    Client->>Keycloak: POST /realms/videoclub/protocol/openid-connect/token (Code Exchange)
    Keycloak-->>Client: 200 OK (JWT Access Token con roles)

    Note over Client,MCP: Paso 3: Descubrimiento de Capacidades MCP
    Client->>Nginx: POST /catalog/mcp (JSON-RPC method: "server/discover")
    Nginx->>GW: POST /catalog/mcp
    GW->>MCP: POST /mcp (RewritePath)
    Note over MCP: McpDiscoverFilter intercepta y responde capacidades
    MCP-->>Client: 200 OK (result: supportedVersions, tools capabilities)

    Note over Client,MCP: Paso 4: Ejecución de Herramientas
    Client->>Nginx: POST /catalog/mcp (Bearer JWT, method: "tools/call", create_movie)
    Nginx->>GW: POST /catalog/mcp
    GW->>MCP: POST /mcp (RewritePath)
    Note over MCP: Spring Security valida firma JWT y evalúa @PreAuthorize('movie-permission-create')
    MCP-->>Client: 200 OK (Resultado de ejecucion de la herramienta)
```

---

## 2. Modificaciones en Servicios Backend

### 2.1. Keycloak: Configuración de Hostname y Proxy Edge

*Archivos:* `docker/keycloak.yaml` y `docker/.env`

* **`KC_HOSTNAME`**: Fijado a la URL estática `https://emmanuel-hirsute-savvily.ngrok-free.dev`. Evita que Keycloak devuelva URLs `localhost` en redirects y OIDC discovery.
* **`KC_PROXY_HEADERS: xforwarded`**: Permite a Keycloak confiar en los headers `X-Forwarded-Proto`, `X-Forwarded-Host` y `X-Forwarded-For` inyectados por el proxy Nginx/ngrok, asegurando que las cookies de sesión lleven el flag `Secure`.

### 2.2. Keycloak: Cliente OAuth `videoclub-mcp`

*Archivo:* `docker/keycloak/realm-export.json`

1. **Redirect URIs permitidas**:
   * `https://claude.ai/api/mcp/auth_callback`
   * `https://claude.ai/*`
   * `https://chatgpt.com/connector_platform_oauth_redirect`
   * `https://chatgpt.com/*`
2. **Web Origins (CORS)**: `https://claude.ai`, `https://chatgpt.com`, `+`.
3. **Scope `service_account`**: Agregado como optional scope para resolver rechazos `invalid_scope` que envía ChatGPT por defecto.
4. **Habilitación de Roles Completos (`fullScopeAllowed: true`)**:
   * **Problema resuelto**: `videoclub-mcp` tenía `fullScopeAllowed: false` y solo mapeaba permisos de lectura. Keycloak omitía `movie-permission-create` del token aunque el usuario fuera administrador, provocando `403 Access Denied` al crear películas.
   * **Solución**: Al activar `fullScopeAllowed: true`, el access token de `usuarioadmin` incluye todos sus permisos de creación, actualización y borrado.

### 2.3. Keycloak: Inclusión Explícita de Scopes en Token (`include.in.token.scope`)

*Archivo:* `docker/keycloak/realm-export.json`

* **Problema**: Al conectar en ChatGPT, mostraba la advertencia: *"no se otorgaron todos los permisos solicitados. Es posible que algunas herramientas no funcionen hasta que lo vuelvas a conectar"*. ChatGPT solicita todos los scopes publicados (`roles`, `web-origins`, `basic`, `acr`), pero Keycloak los tenía configurados con `"include.in.token.scope": "false"`. Aunque los claims estaban en el JWT, Keycloak omitía sus nombres en el campo `scope` del JSON de `/token`, disparando una falsa alarma de ChatGPT.
* **Solución**: Se activó `"include.in.token.scope": "true"` en los Client Scopes (`roles`, `web-origins`, `service_account`, `acr`, `basic`), eliminando la advertencia.

### 2.4. Spring Boot MCP: Soporte para `server/discover`

*Archivos:*

* `catalog-service/src/main/java/ar/unrn/video/catalog/mcp/McpDiscoverFilter.java`
* `membership-service/src/main/java/ar/unrn/video/membership/mcp/McpDiscoverFilter.java`

* **Problema**: Clientes modernos como ChatGPT envían el método JSON-RPC `server/discover` (especificación MCP julio 2026). Spring AI MCP Starter 2.0.1 todavía no lo implementa nativamente y respondía con `500 Internal Server Error`.
* **Solución**: Se implementó un servlet filter con prioridad máxima (`Ordered.HIGHEST_PRECEDENCE`) que responde las capacidades del servidor (`supportedVersions`, `capabilities`, `serverInfo`) y delega el resto de los métodos a Spring AI.

---

## 3. Infraestructura de Red: Ingress (Nginx) + Spring Cloud Gateway

### 3.1. Edge Ingress (`docker/ngrok/nginx.conf`)

Nginx actúa como Ingress perimetral ultraliviano:

* `/realms/**`, `/resources/**`, `/js/**`, `/admin/**` $\rightarrow$ `http://video-keycloak:8080` (con buffers grandes para cabeceras OIDC).
* `/*` $\rightarrow$ `http://videoclub-gateway:9500` (todo el tráfico de APIs, MCP y agentes se delega al Gateway).

### 3.2. Spring Cloud Gateway (`docker/gateway/gateway.yml`)

Spring Cloud Gateway (WebFlux / Netty) centraliza el enrutamiento interno:

* `/catalog/mcp` $\rightarrow$ `http://catalog:8081/mcp`
* `/membership/mcp` $\rightarrow$ `http://membership:8082/mcp`
* `/.well-known/oauth-protected-resource/catalog/mcp` $\rightarrow$ `http://catalog:8081/.well-known/oauth-protected-resource`
* `/.well-known/oauth-protected-resource/membership/mcp` $\rightarrow$ `http://membership:8082/.well-known/oauth-protected-resource`
* `/movies/**`, `/api/socios/**`, `/api/agent/**` $\rightarrow$ microservicios correspondientes.

### 3.3. Docker Compose (`docker/ngrok.yaml`)

Compose independiente integrado a la red `videoclub_default`:

```yaml
name: videoclub

services:
  ngrok-proxy:
    image: nginx:alpine
    container_name: videoclub-ngrok-proxy
    restart: unless-stopped
    volumes:
      - ./ngrok/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    networks:
      - default

  ngrok:
    image: ngrok/ngrok:latest
    container_name: videoclub-ngrok
    restart: unless-stopped
    command: http ngrok-proxy:80 --url ${NGROK_URL:-https://emmanuel-hirsute-savvily.ngrok-free.dev}
    environment:
      - NGROK_AUTHTOKEN=${NGROK_AUTHTOKEN}
    depends_on:
      - ngrok-proxy
    networks:
      - default
```

### 3.4. Comandos de Operación

Levantar ngrok y el reverse proxy:

```bash
docker compose --env-file docker/.env -f docker/ngrok.yaml up -d
```

Verificar estado del túnel:

```bash
docker exec videoclub-ngrok-proxy wget -qO- http://videoclub-ngrok:4040/api/tunnels
```

Bajar el servicio:

```bash
docker compose -f docker/ngrok.yaml down
```

---

## 4. Endpoints Estáticos Simétricos

Con esta arquitectura, todos los clientes de IA consumen un único host permanente con URLs simétricas:

| Servicio | Tipo | Endpoint Público |
| :--- | :--- | :--- |
| **Catalog MCP** | Servidor MCP | `https://emmanuel-hirsute-savvily.ngrok-free.dev/catalog/mcp` |
| **Catalog MCP** | Discovery RFC 9728 | `https://emmanuel-hirsute-savvily.ngrok-free.dev/.well-known/oauth-protected-resource/catalog/mcp` |
| **Membership MCP** | Servidor MCP | `https://emmanuel-hirsute-savvily.ngrok-free.dev/membership/mcp` |
| **Membership MCP** | Discovery RFC 9728 | `https://emmanuel-hirsute-savvily.ngrok-free.dev/.well-known/oauth-protected-resource/membership/mcp` |
| **Keycloak SSO** | Issuer / OIDC | `https://emmanuel-hirsute-savvily.ngrok-free.dev/realms/videoclub` |

---

## 5. Configuración en Clientes de IA

### 5.1. Conectar ChatGPT (Custom Connectors / GPTs)

1. En ChatGPT, ir a **Ajustes** > **Conectores** > **Agregar conector** (o crear un GPT con Actions).
2. URL del Servidor MCP:
   * **Catálogo de Películas:** `https://emmanuel-hirsute-savvily.ngrok-free.dev/catalog/mcp`
   * **Padrón de Socios:** `https://emmanuel-hirsute-savvily.ngrok-free.dev/membership/mcp`
3. Tipo de Autenticación: **OAuth**.
4. ChatGPT consultará el discovery y obtendrá automáticamente la URL de Keycloak.
5. Iniciar sesión con:
   * **Usuario:** `usuarioadmin`
   * **Contraseña:** `usuarioadmin`

### 5.2. Conectar Claude (Desktop / Web)

1. En la configuración de MCP de Claude (`claude_desktop_config.json` o conectores remotos):

   ```json
   {
     "mcpServers": {
       "videoclub-catalog": {
         "url": "https://emmanuel-hirsute-savvily.ngrok-free.dev/catalog/mcp"
       },
       "videoclub-membership": {
         "url": "https://emmanuel-hirsute-savvily.ngrok-free.dev/membership/mcp"
       }
     }
   }
   ```

2. Claude abrirá el navegador para autenticar contra Keycloak mediante OAuth 2.0 PKCE.
3. Si ngrok muestra la pantalla inicial de advertencia (*"You are about to visit..."*), hacer clic en **"Visit Site"** (se realiza una única vez).
4. Iniciar sesión con `usuarioadmin` / `usuarioadmin`.

---

## 6. Usuarios y Matriz de Permisos

| Usuario | Contraseña | Roles / Permisos | Acciones Permitidas en MCP |
| :--- | :--- | :--- | :--- |
| **`usuarioadmin`** | `usuarioadmin` | `administrador` (`movie-permission-*`, `socio-permission-*`) | Lectura (`list_movies`, `get_movie`, `search_movies`) y **Escritura** (`create_movie`). |
| **`usuariocliente`** | `usuariocliente` | `cliente` (`movie-permission-read`, `socio-permission-read`) | **Únicamente lectura**. Cualquier intento de `create_movie` devuelve `403 Access Denied`. |
