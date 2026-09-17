# Arquitectura de Dos Servicios: Decisiones y Trampas

`springboot-sso` se separó en dos proyectos Maven **independientes** —`catalog-service` (`:8081`) y `membership-service` (`:8082`)—, cada uno con su propia base de datos, su propio servidor MCP y su propio `Dockerfile`. No hay POM agregador ni módulos Maven.

Este documento es el registro **permanente** de por qué está armado así y de qué cosas fallan en silencio si se tocan. Reemplaza al plan de migración (`implementation_plan.md`), que se borró una vez ejecutado: un plan describe trabajo por hacer y envejece mal, mientras que las decisiones y las trampas siguen siendo ciertas mientras el código siga en pie.

> Los comentarios `Duplicated by design (see docs/... , decisions D1/D7)` que aparecen en las clases duplicadas de ambos servicios apuntan acá.

---

## Decisiones tomadas

| # | Decisión | Consecuencia asumida |
| :--- | :--- | :--- |
| **D7** | **Sin multi-módulo Maven.** Dos proyectos independientes, cada uno con su `pom.xml`, su `mvnw` y su `.mvn/`. | Cero conceptos de Maven multi-módulo para enseñar. **Costo: las versiones de Spring Boot y Spring AI quedan declaradas dos veces** y pueden derivar. Ver [C9](#c9-deriva-de-versiones-entre-los-dos-pomxml). *Esta decisión se tomó después de D1–D6 y simplifica D1 y D2.* |
| **D1** | **No hay código compartido entre los dos servicios.** Las clases cross-cutting se **duplican**. | Con D7 esto deja de ser una elección: proyectos independientes no pueden compartir un módulo. **Costo aceptado: todo cambio de seguridad, CORS o manejo de errores se aplica dos veces.** Las copias llevan un comentario `Duplicated by design` que las señala en el código. |
| **D2** | **Un `Dockerfile` por proyecto**, con las 5 etapas (JVM + GraalVM Native). | **Simplificado por D7:** el build context es la carpeta del servicio, no la raíz del repo. El `Dockerfile` pasa a ser copia casi literal del actual. |
| **D3** | En `videoclub-agent`: **dos providers MCP por dominio (`@Qualifier`) + uno agregado (`@Primary`)**. | Aislamiento real por sub-agente, y `AgentService` sigue funcionando sin tocar su constructor. ~~Se conserva el filtro por nombre de `AbstractDomainSubAgent` como defensa en profundidad.~~ **Revertido después:** los sub-agentes ya no tienen lista de nombres de tools; cada uno usa todas las tools de su provider dedicado. Ver la sección 10.5 de [MCP-resoruce-prompt-plan.md](MCP-resoruce-prompt-plan.md#105-decisión-los-sub-agentes-ya-no-tienen-una-lista-de-tools-escrita-a-mano). **Control de regresión:** los tests `shouldResolveAllToolsFromDedicatedProviderWithoutFiltering` de `CatalogSubAgentTest` y `MembershipSubAgentTest`, en `videoclub-agent`, fallan si se vuelve a filtrar tools por nombre. |
| **D4** | El `docker-compose.yml` de desarrollo levanta **los dos servicios siempre**. | Paridad con producción: el gateway rutea de verdad. **Costo aceptado: ~2x RAM y 2x tiempo de arranque.** |
| **D5** | **Los clients de Keycloak se comparten, no se duplican.** | `videoclub-backend` (secreto + service account) queda **exclusivo de membership-service**. El acoplamiento de roles al client del frontend se documenta como deuda. Ver [sección 9](#keycloak-reparto-de-clients-decisión-d5). |
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


---

## Keycloak: reparto de clients (decisión D5)

**No se crea ningún client nuevo.** Los tres clients del realm `videoclub` se reparten tal cual están.

| Pregunta | Respuesta |
| :--- | :--- |
| ¿Cada backend necesita un client para **validar** tokens? | **No.** Un OAuth2 Resource Server no necesita registro en Keycloak: le alcanza con `issuer-uri` + `jwk-set-uri` para bajar las claves públicas y verificar la firma. |
| ¿Cada backend necesita un client para **llamar** a Keycloak? | **Solo membership-service**, porque es el único con `KeycloakAdminClient` (client credentials para crear usuarios). |

### Reparto

| Client | catalog-service | membership-service | Para qué |
| :--- | :---: | :---: | :--- |
| `videoclub-frontend` (public) | compartido | compartido | Login del frontend. **Define los 7 roles de permiso** y se los asigna a los subgrupos `administrador` y `cliente`. |
| `videoclub-backend` (confidential, `serviceAccountsEnabled=true`) | **NO** | exclusivo | Admin REST de Keycloak. Service account con `realm-management: manage-users, view-users, query-users, query-groups`. |
| `videoclub-mcp` (public) | compartido | compartido | MCP Inspector y clientes MCP externos (redirect a `localhost:6274` / `:8090`). |

### Configuración por servicio

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

### Deuda documentada: los permisos cuelgan del client del frontend

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

---

## Trampas de configuración

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

`KEYCLOAK_BACKEND_CLIENT_SECRET` aparece hoy como valor literal en `application.yml` y como default en los dos compose. Al duplicar la configuración (D1), la copia de `catalog-service` se lo lleva puesto aunque nunca use `KeycloakAdminClient`. **Falla silenciosa:** nada rompe, el servicio arranca igual, y el secreto queda expuesto en un contenedor de más. Hay que borrarlo a mano de la copia de catálogo. Ver [sección 9](#keycloak-reparto-de-clients-decisión-d5).

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
