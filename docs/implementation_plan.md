# Plan de Arquitectura: Dos Servicios Independientes (Catálogo y Membresía)

Este plan detalla la separación de `springboot-sso` en **dos proyectos Maven totalmente independientes**, alojados en subcarpetas del mismo repositorio:
- **`catalog-service/`** (Microservicio de Catálogo de Películas)
- **`membership-service/`** (Microservicio de Membresía, Socios y Notificaciones)

**No hay POM agregador, no hay módulos Maven, no hay reactor.** Cada carpeta es un proyecto Spring Boot común, con su propio `pom.xml` colgando directo de `spring-boot-starter-parent` — exactamente la forma que genera `start.spring.io`.

---

## Decisiones Tomadas

| # | Decisión | Consecuencia asumida |
| :--- | :--- | :--- |
| **D7** | **Sin multi-módulo Maven.** Dos proyectos independientes, cada uno con su `pom.xml`, su `mvnw` y su `.mvn/`. | Cero conceptos de Maven multi-módulo para enseñar. **Costo: las versiones de Spring Boot y Spring AI quedan declaradas dos veces** y pueden derivar. Ver [C9](#c9-deriva-de-versiones-entre-los-dos-pomxml). *Esta decisión se tomó después de D1–D6 y simplifica D1 y D2.* |
| **D1** | **No hay código compartido entre los dos servicios.** Las clases cross-cutting se **duplican**. | Con D7 esto deja de ser una elección: proyectos independientes no pueden compartir un módulo. **Costo aceptado: todo cambio de seguridad, CORS o manejo de errores se aplica dos veces.** Ver [sección 4](#4-clases-duplicadas-decisión-d1). |
| **D2** | **Un `Dockerfile` por proyecto**, con las 5 etapas (JVM + GraalVM Native). | **Simplificado por D7:** el build context es la carpeta del servicio, no la raíz del repo. El `Dockerfile` pasa a ser copia casi literal del actual. |
| **D3** | En `videoclub-agent`: **dos providers MCP por dominio (`@Qualifier`) + uno agregado (`@Primary`)**. | Aislamiento real por sub-agente, y `AgentService` sigue funcionando sin tocar su constructor. **Se conserva el filtro por nombre** de `AbstractDomainSubAgent` como defensa en profundidad. |
| **D4** | El `docker-compose.yml` de desarrollo levanta **los dos servicios siempre**. | Paridad con producción: el gateway rutea de verdad. **Costo aceptado: ~2x RAM y 2x tiempo de arranque.** |
| **D5** | **Los clients de Keycloak se comparten, no se duplican.** | `videoclub-backend` (secreto + service account) queda **exclusivo de membership-service**. El acoplamiento de roles al client del frontend se documenta como deuda. Ver [sección 9](#9-keycloak-reparto-de-clients-decisión-d5). |
| **D6** | **`spring-boot-starter-amqp` va en los dos proyectos**, aunque hoy `catalog-service` no publique ni consuma. | Anticipa mensajería en catálogo sin editar el POM a mitad de clase. **Costo: el health indicator de Rabbit se autoconfigura solo.** Ver [C8](#c8-el-health-indicator-de-rabbit-se-prende-solo). **`RabbitMQConfig` NO se duplica.** |

---

## Qué se gana y qué se pierde con D7

Vale la pena tenerlo explícito, porque es la decisión que más cambia la forma del repo.

**Se gana:**
- **Nada de Maven multi-módulo que explicar.** Ni `<packaging>pom</packaging>`, ni `<modules>`, ni `-pl`, ni `-am`, ni reactor, ni herencia de POM padre. Cada proyecto es la forma que el equipo ya conoce.
- **Cada carpeta abre sola en el IDE.** `catalog-service/` es un proyecto completo, no "una parte de".
- **El `Dockerfile` se simplifica.** Build context = la carpeta del servicio. Desaparece la trampa de "el context tiene que ser la raíz porque necesita el POM padre".
- **Los comandos son los de siempre**: `cd catalog-service && ./mvnw test`. Sin flags que haya que justificar.

**Se pierde:**
- **No hay un comando único que construya todo.** Son dos, o un script de dos líneas.
- **Las versiones se declaran dos veces** (Spring Boot 4.1.1, Spring AI 2.0.1, springdoc 3.1.1). Es el costo real — ver [C9](#c9-deriva-de-versiones-entre-los-dos-pomxml).
- **El wrapper de Maven se duplica** (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
- En CI serían dos jobs de build en vez de uno.

> [!NOTE]
> Si más adelante hay tiempo para explicar multi-módulo al equipo, la migración inversa es mecánica: se crea el POM agregador en la raíz, se le agregan los dos `<module>`, se suben las versiones a su `<dependencyManagement>` y se borran los wrappers duplicados. **Ningún código Java cambia.** Por eso se puede posponer sin deuda estructural.

---

## Estructura de Carpetas Objetivo

```
springboot-sso/
├── docker/                        <-- INFRAESTRUCTURA Y GATEWAY (compartida)
│   ├── services.yaml
│   ├── postgresql.yaml            <-- [MODIFY] monta el nuevo init.sql
│   ├── postgresql/init.sql        <-- [NEW] crea video_catalog y video_membership
│   ├── gateway/gateway.yml        <-- [MODIFY] ruteo a catalog:8081 / membership:8082
│   ├── apigateway.yaml
│   ├── keycloak.yaml, keycloak/
│   ├── rabbit.yaml
│   └── email.yaml
├── docker-compose.yml             <-- DEV: catalog (:8081) + membership (:8082)
├── docker-compose.prod.yml        <-- PROD: catalog + membership
├── .env / .env.prod / .env.*.example
├── docs/                          <-- HUB MAESTRO DE DOCUMENTACIÓN
│
├── catalog-service/               <-- PROYECTO INDEPENDIENTE (:8081)
│   ├── pom.xml                    <-- POM de aplicación; hereda de spring-boot-starter-parent (Maven Central)
│   ├── mvnw, mvnw.cmd, .mvn/      <-- copia propia del wrapper
│   ├── .dockerignore              <-- propio (el context es esta carpeta)
│   ├── Dockerfile                 <-- 5 etapas
│   └── src/
│       ├── main/
│       │   ├── java/ar/unrn/video/catalog/
│       │   │   ├── config/        (SecurityConfiguration, KeycloakGrantedAuthoritiesConverter,
│       │   │   │                   JacksonConfig, OpenApi30Config, DomainConfig, NativeRuntimeHints)
│       │   │   ├── domain/        (Movie, Genre)
│       │   │   ├── repos/         (MovieRepository)
│       │   │   ├── model/         (MovieDTO, MovieTitleUnique)
│       │   │   ├── service/       (MovieService)
│       │   │   ├── rest/          (MovieResource, GlobalExceptionHandler)
│       │   │   ├── util/          (NotFoundException)
│       │   │   ├── mcp/           (MovieMcpTools)
│       │   │   └── CatalogApplication.java
│       │   └── resources/         (application.yml, banner.txt)
│       └── test/java/ar/unrn/video/catalog/
│           (MovieServiceSearchTest, McpToolsNotFoundTest, McpToolsSecurityTest — porción catálogo)
│
└── membership-service/            <-- PROYECTO INDEPENDIENTE (:8082)
    ├── pom.xml                    <-- POM de aplicación; hereda de spring-boot-starter-parent (Maven Central)
    ├── mvnw, mvnw.cmd, .mvn/      <-- copia propia del wrapper
    ├── .dockerignore
    ├── Dockerfile                 <-- 5 etapas
    └── src/
        ├── main/
        │   ├── java/ar/unrn/video/membership/
        │   │   ├── config/        (SecurityConfiguration, KeycloakGrantedAuthoritiesConverter,
        │   │   │                   JacksonConfig, OpenApi30Config, DomainConfig,
        │   │   │                   NativeRuntimeHints, RabbitMQConfig)
        │   │   ├── domain/        (Socio)
        │   │   ├── repos/         (SocioRepository)
        │   │   ├── model/         (SocioDTO, UserCreateRequestDTO, KeycloakEvent)
        │   │   ├── client/keycloak/ (KeycloakAdminClient, KeycloakAuthInterceptor,
        │   │   │                     KeycloakClientConfiguration, dto/*)
        │   │   ├── event/         (Event, MessagePublisher, SocioPayload)
        │   │   ├── service/       (SocioService, UserService, SseEmitterManager,
        │   │   │                   KeycloakEventListener, SocioEventListener)
        │   │   ├── rest/          (SocioResource, UserResource, NotificationController,
        │   │   │                   GlobalExceptionHandler)
        │   │   ├── util/          (NotFoundException)
        │   │   ├── mcp/           (SocioMcpTools)
        │   │   └── MembershipApplication.java
        │   └── resources/         (application.yml, banner.txt)
        └── test/java/ar/unrn/video/membership/
            (SocioServiceTest, SocioEventSerializationTest, KeycloakEventDeserializationTest,
             McpToolsNotFoundTest, McpToolsSecurityTest — porción socios)
```

**En la raíz del repo NO queda**: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `Dockerfile`, `target/`.

> [!IMPORTANT]
> **No confundir "POM agregador" con `spring-boot-starter-parent`.** Maven llama "parent POM" a las dos cosas:
>
> | | Qué es | Dónde vive |
> | :--- | :--- | :--- |
> | **POM agregador** | Declara `<modules>` y arma el reactor | Un archivo **en este repo**. **Es lo que elimina D7.** |
> | **`spring-boot-starter-parent`** | De quien se heredan las versiones de dependencias y la configuración de plugins | Un artefacto **en Maven Central**. Lo tiene todo proyecto Spring Boot. |
>
> Cada `pom.xml` de servicio conserva su `<parent>` apuntando a `spring-boot-starter-parent` — igual que cualquier zip de `start.spring.io`. El `<relativePath/>` vacío es justamente la instrucción *"no busques un POM padre en disco"*, y es la prueba de que no quedó agregador.

> [!NOTE]
> **Correcciones respecto del borrador original de este plan:**
> - `RabbitMQConfig` vive hoy en `config/`, no en `event/`.
> - `docker/rabbit/` no existe: es `docker/rabbit.yaml`.
> - `docker/postgresql/init.sql` **no existe todavía**: es un archivo nuevo, no una modificación.
> - El árbol original omitía por completo `config/`, `model/`, `util/`, `event/` y `src/test/`.

---

## Arquitectura de Datos y Puertos

| Servicio | Puerto Host | Puerto Docker | Base de Datos (PostgreSQL) | MCP Endpoint | Herramientas MCP |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`catalog-service`** | `8081` | `8081` | `video_catalog` | `http://catalog:8081/mcp` | `list_movies`, `get_movie`, `search_movies`, **`create_movie`** |
| **`membership-service`** | `8082` | `8082` | `video_membership` | `http://membership:8082/mcp` | `list_socios`, `get_socio` |

Ambos consumidos transparentemente por el **API Gateway en `:9500`**.

> [!WARNING]
> **`create_movie` es obligatoria.** `MovieMcpTools` la expone (línea 113) y `CatalogSubAgent.CATALOG_TOOL_NAMES` ya la declara. Si no migra, el filtro de `AbstractDomainSubAgent` la descarta y el usuario pierde la capacidad de crear películas desde el agente.

Puertos ya ocupados en el stack: Keycloak `9090`, Gateway `9500`, Agente `8085`, Postgres `5432`, RabbitMQ `5672`, MailHog `8025`.

---

## Proposed Changes

### 1. Los Dos Proyectos Independientes (Decisión D7)

#### [NEW] `catalog-service/pom.xml` y `membership-service/pom.xml`

Cada uno es un POM de aplicación normal. **Ninguno referencia al otro, y no hay POM en la raíz.**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>   <!-- vacio: no hay POM padre en disco, se baja de Maven Central -->
</parent>

<groupId>ar.unrn</groupId>
<artifactId>catalog-service</artifactId>   <!-- membership-service en el otro -->
<version>0.0.1-SNAPSHOT</version>

<properties>
    <java.version>25</java.version>
    <spring-ai.version>2.0.1</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Ambos POM arrastran además, **copiados tal cual del POM actual**: el `maven-compiler-plugin` con el annotation processor de Lombok, el `spring-boot-maven-plugin` con el perfil `local` y la exclusión de Lombok, y la declaración del `native-maven-plugin` (con su comentario explicando por qué hace falta traerlo al `<build>`).

Lo único que difiere entre los dos: `<artifactId>`, y el `<imageName>` del `native-maven-plugin` (`catalog-service` / `membership-service`).

#### [NEW] Wrapper de Maven en cada proyecto

Se copian desde la raíz actual, sin modificar:
```
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
```

> [!WARNING]
> **Es el olvido más fácil de esta migración.** Sin `.mvn/wrapper/maven-wrapper.properties`, `./mvnw` falla con un error que no dice nada útil sobre lo que falta. Copiar los tres, en los dos proyectos.

#### Reparto de dependencias

Todas salen del `pom.xml` actual de la raíz.

| Dependencia | catalog | membership | Nota |
| :--- | :---: | :---: | :--- |
| `spring-boot-starter-web` | ✅ | ✅ | |
| `spring-boot-starter-security` | ✅ | ✅ | |
| `spring-boot-starter-oauth2-resource-server` | ✅ | ✅ | |
| `spring-security-oauth2-jose` | ✅ | ✅ | |
| `spring-boot-starter-data-jpa` | ✅ | ✅ | |
| `postgresql` (runtime) | ✅ | ✅ | |
| `spring-boot-starter-validation` | ✅ | ✅ | |
| `spring-ai-starter-mcp-server-webmvc` | ✅ | ✅ | |
| `springdoc-openapi-starter-webmvc-ui` 3.1.1 | ✅ | ✅ | Versión explícita en ambos POM |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | ✅ | ✅ | |
| `lombok` | ✅ | ✅ | |
| `spring-boot-starter-test` + `spring-security-test` | ✅ | ✅ | |
| `spring-boot-starter-amqp` | ✅ | ✅ | **Decisión D6.** Hoy solo membresía lo usa. Requiere apagar el health indicator en catálogo — ver [C8](#c8-el-health-indicator-de-rabbit-se-prende-solo). |
| `spring-boot-devtools` | ✅ | ✅ | |
| `spring-boot-docker-compose` | ⚠️ | ⚠️ | Ver [C1](#c1-spring-boot-docker-compose-se-dispara-dos-veces) |

#### [DELETE] Raíz del repositorio (**solo al final — ver Fase 9**)

Se eliminan: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `Dockerfile`, `target/`.

---

### 2. `catalog-service/`

- Clases migradas (package base `ar.unrn.video.catalog`):
  - `Movie`, `Genre`, `MovieRepository`, `MovieService`, `MovieDTO`, `MovieTitleUnique`.
  - `MovieResource` (`/movies`).
  - `MovieMcpTools` (`list_movies`, `get_movie`, `search_movies`, `create_movie`).
  - `CatalogApplication.java` (con `@ImportRuntimeHints(NativeRuntimeHints.class)`).
  - **+ las clases duplicadas de la sección 4.**
- Configuración: `server.port: 8081`, `spring.datasource.url: .../video_catalog`, y `spring.config.import` con `optional:file:.env[.properties]` y `optional:file:../.env[.properties]` para resolución local fuera de Docker (ver [C10](#c10-carga-de-env-al-ejecutar-desde-la-subcarpeta)).
- **`RabbitMQConfig` NO se migra a catálogo.** Declara la topología del bounded context de Socio (exchange `keycloak.events`, queue `socio.events.queue`, su DLX/DLQ y el `MessageConverter` que fuerza `application/json` porque el SPI de Keycloak publica `text/plain`). Cuando catálogo necesite mensajería declarará **su** topología, no ésta.
- **Sin bloque `keycloak.admin.*`** en su `application.yml` — ver [sección 9](#9-keycloak-reparto-de-clients-decisión-d5).

### 3. `membership-service/`

- Clases migradas (package base `ar.unrn.video.membership`):
  - `Socio`, `SocioRepository`, `SocioService`, `SocioDTO`.
  - `UserService`, `UserResource`, `UserCreateRequestDTO`.
  - `KeycloakAdminClient`, `KeycloakAuthInterceptor`, `KeycloakClientConfiguration`, `dto/*`.
  - `SseEmitterManager`, `NotificationController` (`/api/notifications`).
  - `KeycloakEventListener`, `SocioEventListener`, `RabbitMQConfig`, `KeycloakEvent`.
  - `Event`, `MessagePublisher`, `SocioPayload`.
  - `SocioMcpTools` (`list_socios`, `get_socio`).
  - `MembershipApplication.java` (con `@ImportRuntimeHints(NativeRuntimeHints.class)`).
  - **+ las clases duplicadas de la sección 4.**
- Configuración: `server.port: 8082`, `spring.datasource.url: .../video_membership`, bloque `keycloak.admin.*` completo, y `spring.config.import` con `optional:file:.env[.properties]` y `optional:file:../.env[.properties]`.

---

### 4. Clases Duplicadas (Decisión D1)

Estas clases **no pertenecen a ningún dominio** y se copian a ambos proyectos. Con D7 la duplicación ya no es una elección de diseño: dos proyectos independientes no tienen dónde compartirlas.

| Clase | catalog | membership | Diferencia entre copias |
| :--- | :---: | :---: | :--- |
| `SecurityConfiguration` | ✅ | ✅ | Default de `videoclub.mcp.resource-uri`: `:8081/mcp` vs `:8082/mcp` |
| `KeycloakGrantedAuthoritiesConverter` | ✅ | ✅ | Idénticas |
| `JacksonConfig` | ✅ | ✅ | Idénticas |
| `OpenApi30Config` | ✅ | ✅ | Título/descripción por servicio |
| `GlobalExceptionHandler` | ✅ | ✅ | Idénticas |
| `NotFoundException` | ✅ | ✅ | Idénticas |
| `DomainConfig` | ✅ | ✅ | Cambian los packages de `@EntityScan` / `@EnableJpaRepositories` |
| `NativeRuntimeHints` | ✅ | ✅ | **Se parten, no se copian** — ver abajo |
| `banner.txt` | ✅ | ✅ | Texto por servicio |

**`NativeRuntimeHints` se reparte por contenido:**

| Hint actual | Va a |
| :--- | :--- |
| `MovieDTO` | catalog |
| JDK proxies de `KeycloakAdminClient` + reflexión de sus métodos | membership |
| `KeycloakTokenResponseDTO`, `KeycloakUserDTO`, `KeycloakUserCreateDTO`, `KeycloakCredentialDTO` | membership |
| `UserCreateRequestDTO`, `KeycloakEvent`, `KeycloakEvent.AuthDetails` | membership |
| `SocioPayload`, `Event`, `SocioDTO` | membership |

> [!TIP]
> Dejar un comentario cruzado en ambas copias:
> `// Duplicated by design (see docs/implementation_plan.md, decisions D1/D7). Keep in sync with membership-service.`

---

### 5. Migración de Tests

Son 6 clases y **dos de ellas cubren ambos dominios**, así que hay que partirlas.

| Test actual | Destino |
| :--- | :--- |
| `MovieServiceSearchTest` | catalog |
| `SocioServiceTest` | membership |
| `SocioEventSerializationTest` | membership |
| `KeycloakEventDeserializationTest` | membership |
| `McpToolsNotFoundTest` | **Se parte**: casos de `get_movie` → catalog; casos de `get_socio` → membership |
| `McpToolsSecurityTest` | **Se parte**: `@PreAuthorize` de movie tools → catalog; de socio tools → membership |

---

### 6. Base de Datos: Script de Inicialización

#### [NEW] `docker/postgresql/init.sql`

```sql
CREATE DATABASE video_catalog;
CREATE DATABASE video_membership;
```

#### [MODIFY] [docker/postgresql.yaml](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/postgresql.yaml)

El archivo actual **no monta ningún script de inicialización**. Sin este mount, `init.sql` no se ejecuta nunca:

```yaml
    volumes:
      - ./postgresql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

> [!NOTE]
> - Los scripts de `/docker-entrypoint-initdb.d/` corren **solo con el data dir vacío**. La imagen oficial de PostgreSQL declara un `VOLUME` anónimo internamente: si ya existe un contenedor o volumen previo, PostgreSQL ignora la carpeta de inicialización. Bajar la infra con `docker compose -f docker/services.yaml down -v` antes de arrancar para forzar la creación limpia de ambas bases.
> - La DB `video` que crea `POSTGRES_DB` queda huérfana tras la migración.
> - `ddl-auto: update` sigue vigente: cada servicio crea su propio esquema en su propia base al arrancar.

---

### 7. API Gateway: Actualización de Rutas

#### [MODIFY] [docker/gateway/gateway.yml](file:///home/horacio/proyectos/unrn/taller/springboot-sso/docker/gateway/gateway.yml)

> [!WARNING]
> **Este cambio es un reemplazo de `uri:`, NO un reemplazo del archivo.** El `gateway.yml` actual contiene el bloque `globalcors` y un filtro `DedupeResponseHeader` en **cada una de las 5 rutas**, agregados en el commit `f30cb20`. Escribir el archivo desde cero los borra y rompe el frontend con errores de CORS.

| Ruta | `uri` actual | `uri` nuevo |
| :--- | :--- | :--- |
| `service-catalogo` (`/movies/**`) | `http://backend:8080` | `http://catalog:8081` |
| `service-socios` (`/api/socios/**`) | `http://backend:8080` | `http://membership:8082` |
| `service-users` (`/api/users/**`) | `http://backend:8080` | `http://membership:8082` |
| `service-notificaciones` (`/api/notifications/**`) | `http://backend:8080` | `http://membership:8082` |
| `agent-service` (`/api/agent/**`) | `http://agent:8085` | *(sin cambios)* |

Se conservan intactos: el bloque `globalcors` y el `filters: - DedupeResponseHeader=...` de las cinco rutas.

**No se agrega ruta `/mcp` al gateway**: el agente habla MCP directo por la red interna de Docker.

---

### 8. Docker: Imágenes y Orquestación (Decisiones D2 y D4)

#### [NEW] `catalog-service/Dockerfile` y `membership-service/Dockerfile`

**Gracias a D7, es copia casi literal del `Dockerfile` actual.** El de hoy ya hace `COPY pom.xml ./` y `COPY src ./src` sobre `/app`, que es exactamente lo que necesita un proyecto independiente. Lo único que cambia es el nombre del binario nativo:

```dockerfile
# native-runtime
COPY --from=native-build /app/target/catalog-service ./catalog-service
ENTRYPOINT ["./catalog-service"]
```

Y en el compose, el context es la carpeta del servicio:

```yaml
build:
  context: ./catalog-service
  dockerfile: Dockerfile
  target: ${BUILD_TARGET:-runtime}
```

> [!NOTE]
> **Esto es lo que D7 simplificó.** Con POM agregador habría hecho falta `context: .` (la raíz), `./mvnw package -pl catalog-service -am` y `COPY --from=build /app/catalog-service/target/*.jar`. Nada de eso hace falta ahora.

#### [NEW] `.dockerignore` en cada proyecto

El actual está en la raíz y deja de aplicar cuando el context es la carpeta del servicio. Se copia a cada proyecto, ajustado:

```
target/
.git/
.idea/
*.iml
```

#### [MODIFY] `docker-compose.yml` (DESARROLLO)

Hoy define **un** servicio `backend`. Pasa a definir dos (D4: ambos siempre arriba):

```yaml
services:
  catalog:
    build: { context: ./catalog-service, dockerfile: Dockerfile, target: dev }
    container_name: videoclub-catalog-dev
    volumes: [ "./catalog-service:/app", "~/.m2:/root/.m2" ]
    command: mvn spring-boot:run
    ports: [ "8081:8081" ]
    environment:
      SERVER_PORT: 8081
      JDBC_DATABASE_URL: jdbc:postgresql://postgresql:5432/video_catalog
      # sin variables de RabbitMQ ni de Keycloak Admin

  membership:
    build: { context: ./membership-service, dockerfile: Dockerfile, target: dev }
    container_name: videoclub-membership-dev
    volumes: [ "./membership-service:/app", "~/.m2:/root/.m2" ]
    command: mvn spring-boot:run
    ports: [ "8082:8082" ]
    environment:
      SERVER_PORT: 8082
      JDBC_DATABASE_URL: jdbc:postgresql://postgresql:5432/video_membership
      # + RABBITMQ_* y KEYCLOAK_BACKEND_*
```

> [!IMPORTANT]
> **Nombres de servicio sin `-dev` (`catalog` y `membership`):** Las claves del compose definen el hostname DNS en la red interna de Docker (`videoclub_default`). Al nombrarse `catalog` y `membership`, el gateway (`http://catalog:8081`) y el agente (`http://catalog:8081/mcp`) resuelven de manera idéntica tanto en desarrollo como en producción. Solo se usa el sufijo `-dev` en el `container_name`.

> [!NOTE]
> **El bind mount también se simplifica con D7.** Era `.:/app` (todo el repo); ahora es `./catalog-service:/app` — solo el proyecto. Menos archivos vigilados y `mvn spring-boot:run` sin flags.

> [!NOTE]
> **`SERVER_PORT` deja de servir como variable única.** Hoy `${SERVER_PORT:-8080}` aplica al único backend. Con dos servicios se fija por servicio (como arriba).

#### [MODIFY] `docker-compose.prod.yml`

```yaml
  catalog:
    build: { context: ./catalog-service, dockerfile: Dockerfile, target: "${BUILD_TARGET:-runtime}" }
    image: videoclub-catalog:${APP_VERSION:-1.0.0}${IMAGE_SUFFIX:-}
  membership:
    build: { context: ./membership-service, dockerfile: Dockerfile, target: "${BUILD_TARGET:-runtime}" }
    image: videoclub-membership:${APP_VERSION:-1.0.0}${IMAGE_SUFFIX:-}
```

Los hostnames `catalog` y `membership` deben coincidir con los `uri:` del gateway (sección 7).

#### [MODIFY] `.env`, `.env.example`, `.env.prod*`

`JDBC_DATABASE_URL` es una sola variable y ahora hacen falta dos:

```
CATALOG_JDBC_DATABASE_URL=jdbc:postgresql://postgresql:5432/video_catalog
MEMBERSHIP_JDBC_DATABASE_URL=jdbc:postgresql://postgresql:5432/video_membership
```

> [!CAUTION]
> Verificar que ningún `.env*` versionado contenga el secreto real de Keycloak. Los `*.example` deben llevar placeholder.

---

### 9. Keycloak: Reparto de Clients (Decisión D5)

**No se crea ningún client nuevo.** Los tres clients del realm `videoclub` se reparten tal cual están.

| Pregunta | Respuesta |
| :--- | :--- |
| ¿Cada backend necesita un client para **validar** tokens? | **No.** Un OAuth2 Resource Server no necesita registro en Keycloak: le alcanza con `issuer-uri` + `jwk-set-uri` para bajar las claves públicas y verificar la firma. |
| ¿Cada backend necesita un client para **llamar** a Keycloak? | **Solo membership-service**, porque es el único con `KeycloakAdminClient` (client credentials para crear usuarios). |

#### Reparto

| Client | catalog-service | membership-service | Para qué |
| :--- | :---: | :---: | :--- |
| `videoclub-frontend` (public) | compartido | compartido | Login del frontend. **Define los 7 roles de permiso** y se los asigna a los subgrupos `administrador` y `cliente`. |
| `videoclub-backend` (confidential, `serviceAccountsEnabled=true`) | **NO** | exclusivo | Admin REST de Keycloak. Service account con `realm-management: manage-users, view-users, query-users, query-groups`. |
| `videoclub-mcp` (public) | compartido | compartido | MCP Inspector y clientes MCP externos (redirect a `localhost:6274` / `:8090`). |

#### Configuración por servicio

**`catalog-service`** — solo validación:
```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri:  ${KEYCLOAK_ISSUER_URI:...}
  jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:...}
```
Y **se elimina de su copia** todo el bloque `keycloak.admin.*` (url, realm, client-id, **client-secret**).

**`membership-service`** — validación + admin. Conserva lo anterior **más** el bloque `keycloak.admin.*` completo.

> [!CAUTION]
> **`KEYCLOAK_BACKEND_CLIENT_SECRET` no debe llegar a `catalog-service`.** Ver [C7](#c7-el-secreto-de-keycloak-viaja-a-quien-no-lo-necesita).

#### Deuda documentada: los permisos cuelgan del client del frontend

Los 7 roles que hacen cumplir los `@PreAuthorize` del backend están definidos como **client roles de `videoclub-frontend`**:

```
videoclub-frontend :: movie-permission-read | create | update | delete
videoclub-frontend :: socio-permission-read
videoclub-frontend :: user-permission-read | create
```

Funciona por una sola razón — `KeycloakGrantedAuthoritiesConverter:33`:

```java
for (Object clientObj : resourceAccess.values()) {   // <-- itera TODOS los clients
```

El converter **aplana todo `resource_access` en un solo `Set`, sin mirar de qué client vino cada rol**. Sumado a que el realm no tiene ningún *audience mapper* y `SecurityConfiguration` no valida el claim `aud` (solo issuer y firma):

> **Cualquier client del realm que emita un rol llamado `movie-permission-create` es aceptado por el backend. El nombre del rol es la única credencial.**

No es explotable hoy porque los 3 clients del realm están bajo control. Pero la migración lo vuelve más visible: ahora son **dos** backends confiando en el mismo `resource_access` plano.

**Decisión D5: se documenta, no se corrige en esta migración.** Es ortogonal al corte — el problema existe igual con monolito. Cuando se encare:
1. Clients `catalog-service` y `membership-service` como `bearerOnly`, con sus roles propios.
2. Client scope `videoclub-services` con *audience mappers* a ambos, asignado a `videoclub-frontend`.
3. Converter que lea **solo** `resource_access.<su-propio-clientId>.roles`.
4. `JwtClaimValidator` de `aud` en cada servicio.

---

### 10. Agente IA (`videoclub-agent`): Clientes MCP Dedicados (Decisión D3)

#### [MODIFY] `videoclub-agent/src/main/resources/application.yml`

Hoy: `videoclub.mcp.url: ${VIDEOCLUB_MCP_URL:http://localhost:8080/mcp}` (una sola URL). Pasa a dos:

```yaml
videoclub:
  mcp:
    catalog-url: ${CATALOG_MCP_URL:http://catalog:8081/mcp}
    membership-url: ${MEMBERSHIP_MCP_URL:http://membership:8082/mcp}
```

Y en los compose del agente, `VIDEOCLUB_MCP_URL` se reemplaza por `CATALOG_MCP_URL` y `MEMBERSHIP_MCP_URL`.

#### [MODIFY] `videoclub-agent/.../config/McpClientConfiguration.java`

Se pasa de 2 beans a 5. El método `mcpSyncClient(...)` actual se refactoriza a un helper privado parametrizado por URL, porque toda su lógica (ventana de descubrimiento, captura del token del llamador, `transportContextProvider`) se necesita idéntica en ambos clientes:

```java
@Bean(destroyMethod = "close")
McpSyncClient catalogMcpClient(@Value("${videoclub.mcp.catalog-url}") String url, TokenRelayService t)   { ... }

@Bean(destroyMethod = "close")
McpSyncClient membershipMcpClient(@Value("${videoclub.mcp.membership-url}") String url, TokenRelayService t) { ... }

@Bean @Qualifier("catalogTools")
SyncMcpToolCallbackProvider catalogToolCallbackProvider(McpSyncClient catalogMcpClient) { ... }

@Bean @Qualifier("membershipTools")
SyncMcpToolCallbackProvider membershipToolCallbackProvider(McpSyncClient membershipMcpClient) { ... }

@Bean @Primary   // consumido por AgentService para GET /api/agent/tools
SyncMcpToolCallbackProvider allToolCallbackProvider(McpSyncClient catalogMcpClient,
                                                   McpSyncClient membershipMcpClient) {
    return SyncMcpToolCallbackProvider.builder()
            .mcpClients(catalogMcpClient, membershipMcpClient)
            .build();
}
```

> [!WARNING]
> **`discoveryWindow` no puede compartirse.** Hoy es un `AtomicBoolean` local por cliente. Con dos clientes hacen falta **dos flags independientes**: si se comparte uno, el `initialize()` del segundo cliente encuentra la ventana ya cerrada (la cerró el `finally` del primero) y el handshake de arranque falla con `getUserBearerToken()`.

> [!WARNING]
> **Dos `initialize()` al arranque = dos puntos de fallo tolerados.** El `catch` actual degrada a warning y confía en la recuperación perezosa en el primer request autenticado. Ese comportamiento se mantiene por cliente, pero el log debe identificar **cuál** de los dos falló.

#### [MODIFY] `CatalogSubAgent.java` y `MembershipSubAgent.java`

Solo cambia el constructor: se anota el parámetro con el `@Qualifier` correspondiente.

```java
public CatalogSubAgent(ChatClient.Builder b,
                       @Qualifier("catalogTools") SyncMcpToolCallbackProvider p) { ... }
```

> [!IMPORTANT]
> **`AbstractDomainSubAgent` NO cambia. El filtro por nombre se conserva.**
> El borrador original decía *"se elimina la necesidad de filtrar manualmente las tools"*. Es un error: ese filtro (`AbstractDomainSubAgent:53-56`) es lo que alimenta el **fail-fast** de la línea 59. Sin él, un `catalog-service` caído devuelve un array vacío de callbacks, el `IllegalStateException` nunca se lanza, y el sub-agente responde alucinando en lugar de fallar con mensaje claro. Con clientes dedicados el filtro pasa a ser redundante en el camino feliz — y esa es exactamente la razón para dejarlo: defensa en profundidad.

#### [MODIFY] Tests del agente

`CatalogSubAgentTest` y `MembershipSubAgentTest` mockean `SyncMcpToolCallbackProvider` por tipo. Como ahora hay tres beans de ese tipo, los tests de contexto necesitan `@Qualifier` o `@MockitoBean(name=...)`. Los tests unitarios puros con `@Mock` siguen funcionando sin cambios.

#### `AgentService` — sin cambios

Inyecta `SyncMcpToolCallbackProvider` por tipo (`AgentService.java:36`, usado en `:190`). El bean `@Primary` lo resuelve y `GET /api/agent/tools` pasa a listar las 6 tools. **Si no se declara el `@Primary`, el arranque muere con `NoUniqueBeanDefinitionException`.**

---

## Trampas de Configuración

Cosas que compilan, arrancan, y fallan en runtime o en silencio.

### C1. `spring-boot-docker-compose` se dispara dos veces

`application.yml` tiene hoy:
```yaml
spring.docker.compose:
  file: ./docker/services.yaml
  enabled: true
```
Dos problemas al partir:
1. **La ruta es relativa.** Desde `catalog-service/` (que ahora es la raíz del proyecto), `./docker/services.yaml` no resuelve.
2. **Los dos servicios intentarían levantar el mismo stack de infra.**

Solución: en ambos proyectos `enabled: ${SPRING_DOCKER_COMPOSE_ENABLED:false}` y `file: ../docker/services.yaml`, documentando el arranque explícito de la infra:
```bash
docker compose -f docker/services.yaml up -d
```

### C2. `videoclub.mcp.resource-uri` está clavado al puerto 8080

`SecurityConfiguration:26` lo usa para el documento RFC 9728 (`/.well-known/oauth-protected-resource`) con default `http://localhost:8080/mcp`. Cada servicio necesita el suyo (`:8081/mcp`, `:8082/mcp`) o anuncia un recurso que no existe. **Falla silenciosa:** el endpoint responde 200 con un `resource` incorrecto.

### C3. `<imageName>` del native-maven-plugin

Hoy está clavado en `videoclub-backend`. Cada proyecto declara el suyo (`catalog-service` / `membership-service`), y el `COPY --from=native-build` y el `ENTRYPOINT` del Dockerfile respectivo tienen que coincidir exactamente. Con D7 ya no hay riesgo de colisión entre ambos, pero sí de que el COPY apunte al nombre viejo.

### C4. Nombre del servidor MCP

`spring.ai.mcp.server.name: videoclub-mcp-server` e `instructions` describen hoy "catálogo **y** socios". Cada servicio debe declarar su propio nombre e instrucciones, o el agente recibe dos servidores que dicen ser lo mismo.

### C5. `spring.application.name: video`

Debe pasar a `catalog-service` / `membership-service`, o las métricas de Prometheus de ambos se mezclan bajo la misma etiqueta `application`.

### C6. SSE a través del gateway

`NotificationController` (`/api/notifications`) emite Server-Sent Events. Ya funciona hoy vía gateway, y el cambio de `uri` no lo altera — pero es el primer endpoint a verificar tras el corte, porque un timeout de proxy mal heredado se manifiesta solo ahí.

### C7. El secreto de Keycloak viaja a quien no lo necesita

`KEYCLOAK_BACKEND_CLIENT_SECRET` aparece hoy como valor literal en `application.yml` y como default en los dos compose. Al duplicar la configuración (D1), la copia de `catalog-service` se lo lleva puesto aunque nunca use `KeycloakAdminClient`. **Falla silenciosa:** nada rompe, el servicio arranca igual, y el secreto queda expuesto en un contenedor de más. Hay que borrarlo a mano de la copia de catálogo. Ver [sección 9](#9-keycloak-reparto-de-clients-decisión-d5).

### C8. El health indicator de Rabbit se prende solo

Consecuencia directa de **D6**. Verificado en `spring-boot-amqp-4.1.1.jar`:

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration
  org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration
  org.springframework.boot.amqp.autoconfigure.metrics.RabbitMetricsAutoConfiguration
```

Condiciones de `RabbitHealthContributorAutoConfiguration`: `@ConditionalOnClass(RabbitTemplate)` + `@ConditionalOnBean(RabbitTemplate)` + `@ConditionalOnEnabledHealthIndicator`.

La cadena es automática y nadie la pide: **starter en el classpath → `RabbitAutoConfiguration` crea un `RabbitTemplate` → el health indicator se activa.** Como `application.yml` expone `health`, `catalog-service` empieza a pinguear RabbitMQ en `/metrics/health` sin usarlo para nada.

**Mitigación**, en `catalog-service/src/main/resources/application.yml`:

```yaml
management:
  health:
    rabbit:
      enabled: false   # TODO: borrar cuando catalog publique o consuma de verdad (D6)
```

**Severidad real: baja.** Ningún compose declara healthcheck sobre los servicios de aplicación — solo sobre `postgresql`, `rabbit` y `keycloak`, y el único `depends_on: condition: service_healthy` es keycloak sobre rabbit. Un `DOWN` de catálogo no bloquea ningún arranque hoy; el daño es de observabilidad.

**No se abre conexión al arranque**: sin ningún `@RabbitListener`, no hay `SimpleMessageListenerContainer` y la `CachingConnectionFactory` es perezosa. La única conexión la dispara el propio health endpoint.

### C9. Deriva de versiones entre los dos `pom.xml`

Costo directo de **D7**. Estas versiones quedan declaradas dos veces:

| Qué | Dónde |
| :--- | :--- |
| `spring-boot-starter-parent` **4.1.1** | `<parent>` de ambos POM |
| `spring-ai-bom` **2.0.1** | `<properties>` de ambos POM |
| `springdoc-openapi-starter-webmvc-ui` **3.1.1** | `<dependencies>` de ambos POM |
| `java.version` **25** | `<properties>` de ambos POM |

**Falla silenciosa:** alguien bombea Spring Boot en un servicio y no en el otro. Nada rompe de inmediato — los dos compilan, los dos arrancan — pero empiezan a diferir en comportamiento de Jackson, de Security o del MCP server, y el síntoma aparece lejos de la causa.

Mitigación barata, sin volver a multi-módulo:
```bash
# Chequeo manual antes de cada entrega
diff <(rg -o '<version>[^<]+' catalog-service/pom.xml) \
     <(rg -o '<version>[^<]+' membership-service/pom.xml)
```

Cuando el equipo esté listo para Maven multi-módulo, **este es el problema que lo justifica**. No la elegancia: la deriva.

### C10. Carga de `.env` al ejecutar desde la subcarpeta

Al ejecutar `./mvnw spring-boot:run` desde `catalog-service/` o `membership-service/`, Spring Boot toma esa subcarpeta como directorio de trabajo. Si `application.yml` solo declara `optional:file:.env[.properties]`, no encuentra el `.env` ubicado en la raíz del repositorio.

**Solución**: En el `application.yml` de ambos proyectos, declarar la búsqueda en cascada:
```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
```

---

## Fases de Implementación

> [!IMPORTANT]
> **El borrado de la raíz va al final.** Si el E2E falla, hace falta el monolito funcionando para comparar.

1. **Fase 1 — Los dos proyectos vacíos**
   - Crear `catalog-service/` y `membership-service/` con su `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/` y `.dockerignore`.
   - Verificación: `cd catalog-service && ./mvnw validate` (ídem el otro).

2. **Fase 2 — `catalog-service`**
   - Migrar dominio, DTOs, service, `MovieResource` y `MovieMcpTools` (**las 4 tools**).
   - Copiar las clases cross-cutting (D1) y recortar `NativeRuntimeHints` a `MovieDTO`.
   - Migrar `MovieServiceSearchTest` y la porción de catálogo de los dos tests de MCP.
   - Verificación: `cd catalog-service && ./mvnw test`.

3. **Fase 3 — `membership-service`**
   - Migrar dominio de socios, cliente Keycloak, RabbitMQ, SSE y `SocioMcpTools`.
   - Copiar las clases cross-cutting (D1) y quedarse con el resto de `NativeRuntimeHints`.
   - Migrar los 3 tests propios y la porción de socios de los dos tests de MCP.
   - Verificación: `cd membership-service && ./mvnw test`.

4. **Fase 4 — Infraestructura de datos**
   - Crear `docker/postgresql/init.sql` y montarlo en `docker/postgresql.yaml`.
   - Verificación: `docker compose -f docker/services.yaml down -v && docker compose -f docker/services.yaml up -d postgresql` y `\l` muestra las dos bases.

5. **Fase 5 — Docker de los servicios**
   - `Dockerfile` por proyecto (D2), `docker-compose.yml` con dos servicios dev (D4), `docker-compose.prod.yml`, `.env*` desdoblados.
   - Verificación: ambas imágenes JVM buildean y ambos contenedores quedan sanos.

6. **Fase 6 — API Gateway**
   - Cambiar **solo las 4 líneas `uri:`**, conservando `globalcors` y los filtros `DedupeResponseHeader`.
   - Verificación: `curl http://localhost:9500/movies` y `/api/socios` responden desde el servicio correcto.

7. **Fase 7 — Desacoplamiento MCP en `videoclub-agent`**
   - Los 5 beans de `McpClientConfiguration` (D3), los `@Qualifier` en los dos sub-agentes, las URLs en `application.yml` y en los compose del agente.
   - Verificación: `GET /api/agent/tools` lista las 6 tools de ambos servidores.

8. **Fase 8 — Verificación End-to-End**
   - Flujo completo desde el frontend: catálogo, alta de socio, notificación SSE, consulta al agente sobre películas y sobre socios.

9. **Fase 9 — Limpieza (solo si la Fase 8 pasó)**
   - Tag de rescate antes de borrar: `git tag pre-split`.
   - Remover de la raíz: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `Dockerfile`, `target/`.
   - Actualizar `README.md` y `docs/` con la nueva estructura, puertos y comandos.

---

## Verification Plan

### Automated Tests

**No hay comando único**: son dos proyectos independientes (D7).

```bash
(cd catalog-service    && ./mvnw clean test)
(cd membership-service && ./mvnw clean test)
(cd ../videoclub-agent && ./mvnw test-compile)
```

Empaquetado: `cd catalog-service && ./mvnw clean package`.
Imagen nativa (opcional, fase posterior): `cd catalog-service && ./mvnw -Pnative native:compile`.

### Manual Verification

1. **Bases de datos**
   ```bash
   docker exec -it video-postgresql psql -U postgres -c '\l'
   # Debe listar video_catalog y video_membership
   ```

2. **REST vía Gateway**
   ```bash
   curl http://localhost:9500/movies                                        # -> catalog-service (:8081)
   curl -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/socios  # -> membership-service (:8082)
   curl -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/users   # -> membership-service
   ```

3. **CORS no se rompió** (regresión más probable de la Fase 6)
   ```bash
   curl -i -X OPTIONS http://localhost:9500/movies \
     -H "Origin: http://localhost:5173" \
     -H "Access-Control-Request-Method: GET"
   # Access-Control-Allow-Origin una sola vez (no duplicado)
   ```

4. **SSE sigue vivo**
   ```bash
   curl -N -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/notifications
   # Debe quedar abierto y recibir eventos al dar de alta un socio
   ```

5. **Descubrimiento MCP por servicio**
   ```bash
   curl http://localhost:8081/.well-known/oauth-protected-resource   # resource -> .../8081/mcp
   curl http://localhost:8082/.well-known/oauth-protected-resource   # resource -> .../8082/mcp
   ```

6. **Tools del agente**
   ```bash
   curl -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/agent/tools
   # 6 tools: list_movies, get_movie, search_movies, create_movie, list_socios, get_socio
   ```

7. **Aislamiento por sub-agente (fail-fast)**
   ```bash
   docker stop videoclub-catalog-dev
   # Consulta de catálogo al agente -> IllegalStateException con mensaje explícito,
   #   NO una respuesta inventada.
   # Consulta de socios al agente -> sigue funcionando normalmente.
   ```

8. **Aislamiento del secreto de Keycloak** (D5 / C7)
   ```bash
   docker exec videoclub-catalog-dev env | grep -i keycloak
   # NO debe aparecer KEYCLOAK_BACKEND_CLIENT_SECRET.
   # Solo KEYCLOAK_ISSUER_URI y KEYCLOAK_JWK_SET_URI.
   ```

9. **El health de catálogo no miente** (D6 / C8)
   ```bash
   docker stop video-rabbitmq
   curl -s http://localhost:8081/metrics/health | jq .status   # debe seguir "UP"
   curl -s http://localhost:8082/metrics/health | jq .status   # este SI puede dar "DOWN"
   ```

10. **Versiones alineadas entre proyectos** (D7 / C9)
    ```bash
    diff <(rg -o '<version>[^<]+' catalog-service/pom.xml) \
         <(rg -o '<version>[^<]+' membership-service/pom.xml)
    ```

11. **Token Relay end-to-end**
    - Invocación a `CatalogSubAgent` con el JWT del usuario -> `catalog-service` valida el token propagado.
    - Invocación a `MembershipSubAgent` -> ídem contra `membership-service`.
    - Sin token: la llamada falla, **no** degrada a la cuenta de servicio.
