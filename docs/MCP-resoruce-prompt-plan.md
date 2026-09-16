# Incorporación de MCP Resources y MCP Prompts

Hoy el ecosistema usa una sola de las tres primitivas de MCP: **Tools** (`@McpTool`). Este plan incorpora las otras dos —**Resources** (`@McpResource`) y **Prompts** (`@McpPrompt`)— sobre el dominio **que hoy existe**, y deja el patrón montado para los casos de uso con más negocio que vengan después.

> [!IMPORTANT]
> **Este proyecto es una base didáctica.** El objetivo no es resolver el dominio del videoclub, sino dejar el patrón lo bastante claro como para que quien después modele préstamos, reservas o sanciones sepa exactamente dónde va cada cosa. Por eso todo lo que se expone acá sale de datos reales: un recurso que devuelve texto inventado no enseña el patrón, enseña a inventar.

---

## 1. Por qué tres primitivas y no una

La diferencia no es de implementación, es de **quién decide**:

| Primitiva | Quién la invoca | Costo | Para qué sirve |
| :--- | :--- | :--- | :--- |
| **Tool** | El **modelo**, cuando razona que la necesita | Un round trip de tool-calling | Ejecutar una acción o traer datos que el modelo pidió |
| **Resource** | La **aplicación**, antes de hablarle al modelo | Ninguno: se inyecta como contexto | Dar contexto que el modelo no tiene por qué salir a buscar |
| **Prompt** | El **usuario** o la aplicación, eligiendo un flujo | Ninguno | Reutilizar un procedimiento que el dueño del dominio definió |

Hoy todo pasa por Tools. Eso obliga al modelo a gastar una llamada para traer datos que la aplicación ya sabe que va a necesitar, y deja los procedimientos escritos en el prompt del agente en vez de en el servicio que es dueño del dominio.

> [!NOTE]
> **La infraestructura ya está montada.** Los logs de arranque de ambos servicios ya muestran los providers escaneando y encontrando cero:
> ```
> SyncStatelessMcpResourceProvider : No resource methods found in the provided resource objects: []
> SyncStatelessMcpPromptProvider   : No prompt methods found in the provided prompt objects: []
> ```
> No hay que agregar dependencias ni autoconfiguración: sólo faltan los `@Component` con los métodos anotados.

---

## 2. Compatibilidad con el stack actual

| Tema | Estado |
| :--- | :--- |
| **Transporte** | `protocol: stateless` sirve Resources y Prompts sin cambios. *Sampling* y *elicitation* sí exigirían stateful; no están en este plan. |
| **Seguridad** | Resources y Prompts viajan por el mismo `POST /mcp`, ya protegido por el Resource Server y alcanzado por el Token Relay. No hay endpoint nuevo que asegurar. |
| **Gateway** | Sin cambios: el agente habla MCP por la red interna de Docker, no por `:9500`. |
| **Autorización** | `@PreAuthorize` sobre los métodos, igual que en las Tools — con la misma apuesta sobre el descubrimiento proxy-aware. Ver [trampa R1](#r1-el-descubrimiento-proxy-aware-también-aplica-a-resources-y-prompts). |

---

## 3. Cambios propuestos — `catalog-service`

### [NEW] `catalog-service/.../mcp/MovieMcpResources.java`

```java
@McpResource(
    uri = "catalog://movies/{id}",
    name = "movie_card",
    title = "Ficha de película",
    description = "Ficha de una película del catálogo en Markdown, lista para inyectar como contexto.",
    mimeType = "text/markdown")
@PreAuthorize("hasAuthority('movie-permission-read')")
public String movieCard(String id) { ... }
```

**Qué devuelve:** los campos que la entidad `Movie` realmente tiene — `title`, `genre`, `price`, `imageUrl` — renderizados en Markdown.

**Por qué no es un duplicado de `get_movie`:** es el mismo dato en otra representación y con otro consumidor. `get_movie` devuelve `MovieDTO` en JSON para que el modelo lo parsee cuando decidió pedirlo. El recurso devuelve prosa que la aplicación inyecta como contexto sin gastar un tool call. **Esa distinción es el contenido de la clase.**

### [NEW] Recurso de géneros

```java
@McpResource(
    uri = "catalog://genres",
    name = "catalog_genres",
    title = "Géneros del catálogo",
    mimeType = "text/markdown")
@PreAuthorize("hasAuthority('movie-permission-read')")
public String genres() { ... }
```

Devuelve los 10 valores del enum `Genre`: `ACTION`, `COMEDY`, `DRAMA`, `HORROR`, `SCIENCE_FICTION`, `ROMANCE`, `THRILLER`, `ANIMATION`, `DOCUMENTARY`, `FANTASY`.

**Este es el caso más limpio del plan.** Es conocimiento estático, real y hoy invisible para el agente: si el usuario pide "algo de ciencia ficción", el modelo tiene que adivinar que la constante se escribe `SCIENCE_FICTION` y no `Sci-Fi`. Además la base lo valida con un `CHECK` constraint, así que equivocarse no es un detalle estético: es un `500`.

### [NEW] `catalog-service/.../mcp/MovieMcpPrompts.java`

```java
@McpPrompt(
    name = "catalog-alta-pelicula",
    title = "Alta de película",
    description = "Procedimiento para dar de alta una película sin chocar contra las validaciones del catálogo.")
public GetPromptResult altaPelicula(
        @McpArg(name = "titulo", description = "Título tentativo", required = true) String titulo) { ... }
```

El prompt codifica **reglas que el servicio ya impone hoy**:

1. Buscar con `search_movies` antes de crear: el título tiene `UNIQUE`, y repetirlo devuelve `400` con `{Exists.movie.title}`.
2. El género debe ser uno de los 10 del enum — leer `catalog://genres` antes de elegir.
3. `price` es decimal con dos posiciones; `imageUrl` admite `null`.

Eso no es política inventada: es el contrato real de `MovieResource` y `MovieTitleUnique`.

---

## 4. Cambios propuestos — `membership-service`

### [NEW] `membership-service/.../mcp/MembershipMcpResources.java`

```java
@McpResource(
    uri = "membership://socios/{id}",
    name = "socio_card",
    title = "Ficha de socio",
    mimeType = "text/markdown")
@PreAuthorize("hasAuthority('socio-permission-read')")
public String socioCard(String id) { ... }
```

Campos reales de `Socio`: `nombre`, `apellido`, `username`, `email`, `activo`, `fechaAlta`, `fechaBaja`.

> [!WARNING]
> **`activo` es un `boolean`, no un estado.** Hoy no existen "Suspendido" ni "Moroso": el modelo sólo distingue alta y baja lógica. La ficha debe decir *activo / dado de baja* y nada más. Inventar una categoría en el Markdown la vuelve real para el modelo, que después la va a citar con total seguridad.

### [NEW] `membership-service/.../mcp/MembershipMcpPrompts.java`

```java
@McpPrompt(
    name = "membership-alta-socio",
    title = "Alta de socio",
    description = "Flujo de alta y por qué el socio no aparece de inmediato.")
```

**Este es el prompt con más valor real de todo el plan**, porque encapsula la parte contraintuitiva del sistema:

1. El alta se hace contra `POST /api/users`, que crea el usuario en **Keycloak**, no en la base local.
2. Keycloak emite el evento, el SPI lo publica en `keycloak.events`, el ACL lo normaliza y lo republica en `videoclub.events`, y recién ahí `SocioEventListener` crea el `Socio`.
3. **Es consistencia eventual: entre el `201` del alta y el socio visible en `list_socios` pasan milisegundos, pero pasan.** Un agente que consulte inmediatamente después de crear y no encuentre nada no debe concluir que el alta falló.

Ese conocimiento hoy no está escrito en ningún lado que el agente pueda leer, y es exactamente la clase de regla que el dueño del dominio debe publicar en vez de que cada cliente la descubra a los golpes.

---

## 5. Cambios propuestos — `videoclub-agent`

### [NEW] `McpKnowledgeService.java`

Fachada sobre los dos `McpSyncClient` que ya existen. Rutea por el *scheme* de la URI: `catalog://` al cliente de catálogo, `membership://` al de membresía.

```java
String readResource(String uri);                                   // rutea por scheme
GetPromptResult getPrompt(String name, Map<String, Object> args);  // rutea por servidor
List<Resource> listResources();                                    // introspección
```

> [!IMPORTANT]
> **Un scheme desconocido tiene que fallar fuerte, no elegir un cliente por descarte.** Es la misma lógica del fail-fast de `AbstractDomainSubAgent`: si mandás una URI mal escrita al servidor equivocado, el error que vuelve es "recurso no encontrado" y parece un problema de datos cuando en realidad es de ruteo.

### [MODIFY] `agent/rest/AgentController.java`

*(Nota: el controller está en `agent/rest/`, no en `agent/controller/`.)*

Endpoints de introspección, hermanos del `GET /api/agent/tools` que ya existe:

* `GET /api/agent/resources`
* `GET /api/agent/prompts`
* `GET /api/agent/resources/content?uri={uri}`

> [!NOTE]
> Estos endpoints hacen una llamada **viva** a los servidores MCP, igual que `getAvailableToolNames()`. Si un backend está caído, devuelven error — y eso está bien. El frontend debe mostrar ese fallo, no un conteo viejo: es la misma lección del indicador de tools.

### [MODIFY] `CatalogSubAgent.java` y `MembershipSubAgent.java`

Inyectar `catalog://genres` en el system prompt del sub-agente de catálogo, en lugar de dejar que el modelo adivine los nombres de los géneros.

> [!CAUTION]
> **No leer recursos en el constructor.** Los sub-agentes se construyen al arrancar el contexto, cuando todavía no hay ningún usuario y el Token Relay no tiene token que propagar. La lectura va en el momento de la consulta, bajo la identidad de quien pregunta — que es justamente lo que hace confiable la recuperación perezosa de los clientes MCP.

---

## 6. Hints nativos (GraalVM)

### [MODIFY] `NativeRuntimeHints.java` de ambos servicios

Los nuevos `@Component` de recursos y prompts se descubren por reflexión y devuelven tipos del SDK de MCP (`GetPromptResult`, `PromptMessage`) que Jackson serializa.

> [!WARNING]
> **Ningún test en JVM puede fallar por un hint faltante.** Ya nos pasó con `MovieTitleUniqueValidator`: `./mvnw test` en verde, contenedor `healthy`, y `500` en la primera llamada real sobre imagen nativa.
>
> El patrón a seguir está en `catalog-service/src/test/java/ar/unrn/video/catalog/NativeRuntimeHintsTest.java`: afirmar los hints con `RuntimeHintsPredicates` en el test normal. Eso mueve el diagnóstico de un build nativo de varios minutos a un `mvnw test` de segundos. **Escribir ese test antes que el hint**, y verificar que falla sin él.

---

## 7. Trampas

### R1. El descubrimiento proxy-aware también aplica a Resources y Prompts

Poner `@PreAuthorize` sobre un método `@McpResource` proxifica el bean, y el descubrimiento sólo lo encuentra porque `SyncMcpAnnotationProviders` resuelve `AopUtils.getTargetClass(bean)` antes de leer los métodos. Es la misma apuesta documentada en `MovieMcpTools` y en `docs/mcp-server.md` §4.

**Falla silenciosa:** si esa resolución desaparece en una versión futura, el servidor arranca anunciando **cero** recursos, sin ningún error en el log. Replicar el test de descubrimiento que ya existe para tools (`McpToolsSecurityTest#everyToolIsDiscoverable`) para recursos y prompts.

### R2. Un recurso que inventa datos es peor que no tenerlo

Un `500` se ve. Un recurso que afirma que existen "socios morosos" no se ve: el modelo lo repite con seguridad y el usuario le cree. **Todo lo que devuelva un recurso tiene que poder rastrearse hasta un campo de una entidad o una constante del código.**

### R3. Los recursos no llevan la autorización puesta

Un Resource se inyecta como contexto **antes** de que el modelo razone, así que es la aplicación la que decide leerlo. Si el `@PreAuthorize` queda afuera, cualquier consulta de cualquier usuario termina inyectando datos que ese usuario no puede ver por REST. La autoridad tiene que ser la misma que la del endpoint equivalente: `movie-permission-read` para catálogo, `socio-permission-read` para socios.

> [!CAUTION]
> **Las authorities de este realm son de grano fino y no hay roles de realm.** El realm `videoclub` define `movie-permission-read/create/update/delete`, `socio-permission-read` y `user-permission-read/create` como *client roles* de `videoclub-frontend`. **No existen `ROLE_USER`, `ROLE_ADMIN` ni `SCOPE_read`**, y además `GrantedAuthorityDefaults("")` elimina el prefijo `ROLE_`. Un `hasAnyAuthority('ROLE_USER', ...)` deniega al 100% de los usuarios.

### R4. `uri` es un template, y el parámetro se liga por posición

`catalog://movies/{id}` liga `{id}` al parámetro del método. Un nombre que no coincida no rompe la compilación: falla al resolver, en runtime.

---

## 8. Cómo extender esto cuando el dominio crezca

Lo que hace a este plan una **base** y no un ejercicio cerrado:

| Cuando aparezca… | El recurso o prompt que lo acompaña |
| :--- | :--- |
| Entidad `Prestamo` con plazos y vencimientos | `membership://policies/loans` deja de ser texto inventado y pasa a derivarse de la configuración real (plazo, tope de préstamos simultáneos) |
| Estados de socio más allá de `activo` | `membership://tiers` con los valores reales del enum, igual que `catalog://genres` hoy |
| Reglas de sanción por demora | Prompt `membership-debt-triage` con el procedimiento que el dominio implemente |
| Campos nuevos en `Movie` (año, sinopsis, director) | La ficha de `catalog://movies/{id}` los suma sin cambiar su firma |

**La regla que conviene sostener:** un recurso nace cuando existe el dato, no antes. Mientras tanto, el patrón queda demostrado con `catalog://genres`, que es pequeño, verdadero y suficiente para explicar la primitiva.

---

## 9. Plan de verificación

### Tests automatizados

1. **Descubrimiento** (`catalog-service` y `membership-service`): que los providers encuentren los recursos y prompts esperados a través del camino real —`SyncMcpAnnotationProviders`— y no del API público proxy-blind. Espejo de `McpToolsSecurityTest#everyToolIsDiscoverable`.
2. **Autorización**: cada recurso denegado sin la authority correspondiente y permitido con ella, más `AuthenticationCredentialsNotFoundException` con contexto vacío. Espejo de los casos que ya existen para tools.
3. **Hints nativos**: extender `NativeRuntimeHintsTest` con los tipos nuevos. Verificar que falla al quitar el hint.
4. **Agente**: `McpKnowledgeServiceTest` — ruteo por scheme, y que un scheme desconocido lance en vez de elegir un cliente.

### Verificación manual

```bash
TOKEN=...   # usuarioadmin

curl -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/agent/resources
curl -H "Authorization: Bearer $TOKEN" http://localhost:9500/api/agent/prompts
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9500/api/agent/resources/content?uri=catalog://genres"
```

1. **Aislamiento por servicio:** `catalog://` sólo lo resuelve catálogo. Pedirle un `membership://` al cliente de catálogo debe fallar de forma explícita.
2. **Autorización real:** con el token de `usuariocliente` (que no tiene `socio-permission-read`), `membership://socios/{id}` debe dar `403`, no una ficha.
3. **Prueba conversacional:** preguntar por una película de un género y verificar en el `ExecutionTracker` que el género usado es una constante válida del enum.
4. **En imagen nativa:** repetir 1–3 sobre `BUILD_TARGET=native-runtime`. **Es el único modo que puede revelar un hint faltante.**
