# Plan: Asesor Cinéfilo — conocimiento de mundo abierto vía un servidor MCP propio (TMDB)

> [!NOTE]
> **Estado: plan, nada implementado.** Este documento registra la investigación ya hecha, la decisión recomendada y el orden de trabajo. No hay código escrito para esto en ningún repositorio.

## 1. Qué problema resuelve

Los dos sub-agentes actuales viven en un **mundo cerrado**: `CatalogSubAgent` conoce el inventario del VideoClub y `MembershipSubAgent` el padrón de socios. Ninguno sabe nada de cine que no esté ya cargado en la base.

Eso deja afuera dos usos concretos:

| Uso | Ejemplo | Quién lo pide |
| :--- | :--- | :--- |
| **Asesoramiento** | *"¿Qué me recomendás parecido a Interstellar?"*, *"¿de qué trata Oppenheimer?"* | Cualquier usuario |
| **Alta asistida** | *"Cargá Oppenheimer al catálogo a 200 pesos"*, completando título exacto, género y póster sin tipear nada | Un admin |

El segundo uso es el que motiva la colaboración entre sub-agentes de la sección 5, y el que depende de ampliar el modelo de `Movie` (sección 6).

## 2. La fuente elegida: TMDB

[TMDB](https://developer.themoviedb.org) es una API de verdad, con credencial propia y pensada para ser consumida. Verificado contra la API real:

| Endpoint | Resultado observado |
| :--- | :--- |
| `GET /3/search/movie?query=Matrix&language=es-ES` | 92 resultados. El primero: `id: 603`, `title: "Matrix"`, `release_date: "1999-03-31"`, `genre_ids: [28, 878]`, `poster_path`, y **`overview` en español** |
| `GET /3/genre/movie/list?language=es-ES` | La lista de géneros de TMDB, también en español: Acción, Aventura, Animación, Comedia, Crimen, Documental… |

Dos consecuencias que definen el diseño:

* **El póster entra directo en nuestro modelo.** `https://image.tmdb.org/t/p/w500` + `poster_path` es exactamente lo que espera el campo `imageUrl` de `MovieDTO`.
* **Los géneros de TMDB no son los nuestros.** TMDB tiene *Crimen*, *Aventura* y *Familia*; el enum `Genre` de `catalog-service` no. Hace falta una traducción explícita, y lo que no mapea tiene que quedar sin género, nunca adivinado. **Esa traducción es una decisión de nuestro dominio**, y por eso la sección 4 la ubica del lado del catálogo.

### 2.1. Credenciales

El token de lectura de TMDB va **siempre** en una variable de entorno, nunca en un `application.yml` que se commitea. Es el mismo criterio que ya se aplica al resto de los secretos del proyecto.

## 3. Decisión recomendada: un proyecto nuevo, servidor MCP propio

**Un dominio, un servidor MCP, un sub-agente.** Es el patrón que el proyecto ya sostiene con `catalog-service` y `membership-service`, y es lo que se recomienda acá: un proyecto Maven independiente —trabajado con el nombre `cinephile-service`— que exponga el conocimiento de cine como tools MCP, y un `CinephileSubAgent` de **solo lectura** que las consuma.

Forma propuesta:

| Aspecto | Decisión | Por qué |
| :--- | :--- | :--- |
| **Proyecto** | Maven independiente, con su propio `Dockerfile`, como los otros dos servicios | No hay POM agregador en este repositorio (ver [arquitectura-dos-servicios.md](arquitectura-dos-servicios.md)) |
| **Transporte** | Streamable HTTP, igual que los otros dos servidores | Evita transportes SSE deprecados y unifica el protocolo con el resto del sistema |
| **Credencial de TMDB** | Vive **solo** en este servicio, como variable de entorno | El agente nunca ve el token. Si mañana hay otro cliente MCP, tampoco |
| **Cliente HTTP hacia TMDB** | Interfaz `@HttpExchange` + `RestClient` + `HttpServiceProxyFactory`, con un interceptor que agrega el Bearer | Es el patrón que ya usa `KeycloakAdminClient` en `membership-service` |
| **Autorización** | Sin `@PreAuthorize`: no hay datos de usuario ni operaciones de escritura | El `@PreAuthorize` de los otros servidores protege datos del VideoClub. Acá no hay nada propio que proteger. **Consecuencia a aceptar:** cualquier usuario autenticado del agente puede consultar cine |
| **Tools** | `search_movies_tmdb`, `get_movie_tmdb`. Nombres en `snake_case`, con `readOnlyHint = true` | Convención de nombres y metadatos de la tarea T1 del plan de MCP |
| **Qué devuelven** | Pocos campos, ya masticados: título, año, géneros de TMDB, URL del póster armada | Ver sección 5: el dato tiene que viajar entre sub-agentes dentro de un texto |
| **Errores** | Una falla de TMDB se propaga como error de la tool, con mensaje | Nunca devolver vacío cuando en realidad falló la llamada externa |

### 3.1. Alternativas descartadas

| Alternativa | Por qué no |
| :--- | :--- |
| **Tools de TMDB dentro de `catalog-service`** | Las sinopsis de TMDB y `create_movie` quedarían en el mismo servidor MCP, o sea en el mismo sub-agente. Es exactamente el riesgo de inyección de la sección 5 |
| **Tools `@Tool` en el agente, sin MCP** | Es lo más corto —`ToolCallbacks.from(...)` existe en `spring-ai-model 2.0.0` y el patrón `@HttpExchange` ya está en el proyecto—, pero saltea MCP para un dominio entero y mete la lógica de un servicio externo, incluida la traducción de géneros, dentro del agente. Queda como plan B si se necesita algo andando rápido |

## 4. Dónde vive la traducción de géneros

TMDB devuelve sus propios géneros; el catálogo acepta solo su enum `Genre`. La recomendación es que **la traducción sea del catálogo, no del asesor cinéfilo ni del agente**, porque `catalog-service` es el dueño del enum y ya es quien publica `catalog://genres`.

Forma propuesta: un recurso nuevo en `catalog-service`, del estilo `catalog://genres/tmdb-mapping`, que publique la correspondencia y deje explícito qué géneros de TMDB **no** tienen equivalente. Así el agente no decide nada: lee y aplica.

Es el mismo movimiento que resolvió la duplicación del procedimiento de alta (sección 10.2 de [mcp-resources-prompts.md](../mcp-resources-prompts.md)): el conocimiento del dominio se publica desde el servidor que lo posee, en un solo lugar, con un test que ancla que no se duplique.

**Costo a aceptar:** `catalog-service` pasa a conocer la existencia de TMDB. Es conocimiento de integración dentro de un servicio de dominio. La alternativa —que el mapeo viva en `cinephile-service`— le haría conocer nuestro enum, que es peor: ahí sí se acopla a un detalle interno de otro servicio.

## 5. Colaboración entre sub-agentes

**Sí es posible, y el mecanismo ya está.** El orquestador es el supervisor y tiene permiso explícito de encadenar: su system prompt (`AgentService.java`) dice *"Si una consulta requiere ambos dominios, podés invocar a ambos sub-agentes y consolidar una respuesta integrada."*

Flujo para *"cargá Oppenheimer al catálogo a 200 pesos"*:

1. El orquestador delega en el **cinéfilo**: *"buscá la ficha de Oppenheimer"*.
2. El cinéfilo devuelve los campos de TMDB.
3. El orquestador delega en el **catálogo**, pasando esos campos dentro de la consulta.
4. El catálogo sigue el procedimiento que ya publica `catalog://procedures/movie-creation`: **busca primero** con `search_movies`, porque el título es único, y solo después llama a `create_movie`.

### 5.1. La restricción que manda: no hay memoria compartida

La regla crítica de delegación del orquestador es que **los sub-agentes no tienen acceso a la memoria conversacional** y cada delegación debe ser auto-contenida. Todo lo que el catálogo necesita viaja dentro del texto que le arma el orquestador.

Consecuencia de diseño: el cinéfilo tiene que devolver **campos identificables, no prosa**. Si devuelve un párrafo libre, el orquestador copia mal, trunca o completa lo que falta por su cuenta.

### 5.2. El riesgo que la colaboración reintroduce

> [!WARNING]
> **El aislamiento del sub-agente no alcanza si el orquestador hace de correo.** Las sinopsis de TMDB son texto de terceros. Si el orquestador las copia dentro de una delegación al catálogo, ese texto llega igual a un modelo que tiene `create_movie` a mano. La precaución de la sección 10.4 de [mcp-resources-prompts.md](../mcp-resources-prompts.md) aplica de lleno.

Tres contenciones, de la más débil a la más fuerte:

1. **Que el cinéfilo devuelva campos, no relato.** Para dar de alta hoy no se necesita la sinopsis.
2. **Que el texto externo viaje en un bloque delimitado**, marcado como datos y no como instrucciones.
3. **Que el alta la confirme el usuario** antes de ejecutarse. Es la única que no depende de que el modelo se porte bien.

## 6. El límite real está en el modelo de datos

`MovieDTO` tiene `id`, `title`, `genre`, `price` e `imageUrl`. Con eso, la colaboración puede completar hoy:

* el **título exacto** que usa TMDB,
* el **póster**, armando la URL con `poster_path`,
* el **género nuestro**, traducido según la sección 4.

Año, sinopsis, director y duración **no tienen dónde guardarse**. Sumarlos toca la entidad `Movie`, el esquema de la base, `MovieDTO`, los parámetros de `create_movie`, las validaciones, y la tarjeta del frontend que dibuja `json:movies`.

De ahí el orden de trabajo: **primero se amplía la ficha de película, después la colaboración tiene algo que completar.** Al revés, el cinéfilo trae datos que se descartan.

## 7. Orden de trabajo propuesto

| Fase | Qué | Depende de |
| :--- | :--- | :--- |
| **F1** | Ampliar la ficha de `Movie`: año, sinopsis, director, duración. Entidad, esquema, DTO, `create_movie`, tarjeta del frontend | — |
| **F2** | `cinephile-service`: proyecto nuevo, cliente `@HttpExchange` hacia TMDB, tools MCP, `Dockerfile`, compose | — (se puede hacer en paralelo con F1) |
| **F3** | `CinephileSubAgent` de solo lectura, su cliente y provider en `McpClientConfiguration`, y la delegación nueva en `OrchestratorTools` con su test | F2 |
| **F4** | Recurso de traducción de géneros en `catalog-service` (sección 4) | F2 |
| **F5** | Alta asistida: la colaboración de la sección 5, con confirmación del usuario antes de crear | F1, F3, F4 |
| **F6** | Sugerencias nuevas en la pantalla del agente del frontend, y atribución de TMDB (sección 8) | F3 |

Una nota sobre `AbstractDomainSubAgent`: su fail-fast actual asume MCP —cero tools significa que el servidor está caído— y para el catálogo es correcto, porque sin datos reales el modelo inventa. Para un asesor **opcional**, tirar abajo la consulta entera porque TMDB no responde es demasiado. F3 tiene que decidir explícitamente si el cinéfilo falla o degrada.

## 8. Obligaciones y límites de TMDB

* **Atribución obligatoria.** El FAQ de desarrolladores de TMDB exige mostrar de forma visible el texto *"This product uses the TMDB API but is not endorsed or certified by TMDB."* y usar el logo de TMDB sin modificar, menos prominente que el logo propio y en una sección de *Acerca de* o *Créditos*. Esto le corresponde al frontend, no al servicio: entra en F6.
* **Límites de uso.** El FAQ consultado no los detalla. Antes de F2 hay que confirmarlos en el panel de desarrollador y decidir si el servicio cachea las respuestas.
* **Idioma.** `language=es-ES` devuelve títulos y sinopsis en español. Conviene fijarlo en el servicio, no dejarlo a criterio del modelo.

## 9. Preguntas abiertas

1. ¿El cinéfilo **falla o degrada** cuando TMDB no responde? (sección 7)
2. ¿`cinephile-service` **cachea** las respuestas de TMDB, y con qué vencimiento? (sección 8)
3. ¿La confirmación del usuario antes del alta se resuelve en el frontend o con una tool de confirmación explícita? (sección 5.2)
4. ¿Qué campos nuevos entran realmente en F1, y qué muestra la tarjeta del frontend?
