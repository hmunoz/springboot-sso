# Plan de Arquitectura: Evolución de Orquestación Multi-Agente y Ecosistema MCP en VideoClub UNRN

**Estado:** Propuesta de arquitectura técnica y roadmap de evolución  
**Última verificación contra el código:** 2026-09-24 (`catalog-service`, `membership-service`, `videoclub-agent`, SDK `mcp-core` 2.0.0)  
**Componentes involucrados:** `videoclub-agent` (:8085), `catalog-service` (:8081), `membership-service` (:8082), `apigateway` (:9500), `react-sso` (:5173)  
**Referencias teóricas:**
- Ashish Choudhary: *Building an MCP Server in Spring Boot: The Definitive Java Guide* (Medium, 2026)
- Jitin Kayyala: *Multi-Agent Orchestration on the JVM — When to Leave Spring AI* (GitConnected, 2026)

---

## 1. Diagnóstico y Punto de Partida

VideoClub UNRN ya cuenta con una arquitectura de IA empresarial muy avanzada respecto al estado del arte habitual en Spring Boot:

1. **Servidor MCP Completo en el Backend (`catalog-service` / `membership-service`):**
   - Primitivo **Tools**: [`MovieMcpTools`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieMcpTools.java) con hints semánticos (`readOnlyHint`, `openWorldHint`), tipado estricto y resolución de conflictos entre `@PreAuthorize` (proxies CGLIB) y la reflexión de Spring AI.
   - Primitivo **Resources**: [`MovieMcpResources`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieMcpResources.java) para inyección preventiva de contexto en el agente (`catalog://genres`, `catalog://procedures/movie-creation`, `catalog://movies/{id}`) antes de que el LLM delibere, mitigando alucinaciones.
   - Primitivo **Prompts**: [`MovieMcpPrompts`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieMcpPrompts.java) exponiendo procedimientos estandarizados (`catalog-alta-pelicula`).
   - Transporte **Streamable HTTP stateless** sobre `/mcp`, con validación de tokens JWT en cada llamada.

2. **Agente Orquestador y Sub-Agentes de Dominio (`videoclub-agent`):**
   - Patrón Supervisor / Especialistas con [`OrchestratorTools`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/orchestrator/OrchestratorTools.java), [`CatalogSubAgent`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/subagents/CatalogSubAgent.java) y [`MembershipSubAgent`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/subagents/MembershipSubAgent.java).
   - Consumo dinámico de recursos vía [`McpKnowledgeService`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/mcp/McpKnowledgeService.java) — hoy **solo `CatalogSubAgent`** lo usa (ver tensión de asimetría más abajo).
   - Observabilidad nativa con OpenTelemetry / LangSmith y captura de Generative UI mediante [`GenerativeUiExtractor`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/generativeui/GenerativeUiExtractor.java).

### Tensiones Identificadas según los Artículos Analizados

A pesar de la solidez técnica actual, surgen los límites estructurales típicos al escalar en Spring AI:

| Dimensión | Estado Actual en VideoClub | Tensión / Riesgo Detectado |
| :--- | :--- | :--- |
| **Primitivo MCP Prompts** | Definido en ambos backends (`catalog-alta-pelicula`, `membership-alta-socio`). | **No consumido por el cliente**: `McpKnowledgeService` solo expone `readResource(String)`. El impacto real es acotado: las reglas de negocio del alta ya llegan al agente por el recurso `catalog://procedures/movie-creation`, que comparte literalmente `MovieCreationProcedure.steps()` con el prompt. Lo único que el prompt agrega es una intro parametrizada por `titulo`, que el recurso —sin argumentos— no puede dar. |
| **Recursos MCP en el dominio de socios** | `membership-service` publica `membership://socios/{id}` y `membership-alta-socio`. | **Asimetría real**: `MembershipSubAgent` no lee ningún recurso MCP; todo su system prompt es texto fijo en Java. `CatalogSubAgent` ya aplica el patrón contrario e inyecta dos recursos en cada llamada. |
| **Recursos parametrizados** | `catalog://movies/{id}` y `membership://socios/{id}` son *resource templates*. | **No inyectables como contexto previo**: el `{id}` recién se conoce durante la conversación, así que no pueden precargarse en el system prompt como `catalog://genres`. Su consumo natural es bajo demanda, camino que hoy ya cubren las Tools. |
| **Canal de Estado Inter-Agentes** | `String`; `OrchestratorTools.captureArtifacts()` extrae el JSON y entrega al supervisor solo la prosa. | **Contención, no solución**: el supervisor ya no puede romper el payload porque nunca lo ve, pero el contrato entre agentes sigue siendo texto y la extracción depende de que el LLM del subagente respete un fence de markdown pedido en prosa. |
| **Orquestación Multi-Paso** | Resuelta por el modelo orquestador via `@Tool`. | **Fragilidad no determinística**: si el flujo crece a transacciones compuestas (alquiler, cálculo de mora, cobro), coordinarlo mediante prompts produce bifurcaciones impredecibles. |
| **Traza distribuida** | Cliente MCP sobre `HttpClientStreamableHttpTransport`, con relay de JWT por `McpTransportContext`. | **Trace cortado**: no se propaga `traceparent` en las llamadas salientes del cliente MCP, así que el span del agente y el del microservicio no se correlacionan en OpenTelemetry / LangSmith. |
| **Trazabilidad y Auditoría** | `ExecutionTracker` registra agentes y tools invocadas. | **Falta de linaje tipado**: no se almacena formalmente la cadena de razonamiento y precondiciones que justifican una acción de negocio. |

---

## 2. Mapa de Ruta de la Evolución (3 Fases)

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                   EVOLUCIÓN DE ORQUESTACIÓN Y PROTOCOLOS MCP                           │
└────────────────────────────────────────────────────────────────────────────────────────┘

    FASE 1: CERRAR HUECOS MCP REALES (Inmediato - Sin cambiar de stack)
    ┌───────────────────────────┐         ┌───────────────────────────┐
    │  videoclub-agent          │         │  catalog / membership     │
    │  • MembershipSubAgent     │◄───────►│  • Recurso de dominio     │
    │    inyecta recursos MCP   │  HTTP   │    de socios (a publicar) │
    │  • traceparent saliente   │ Stream  │  • /mcp ya estandarizado  │
    └───────────────────────────┘         └───────────────────────────┘
                  │
                  ▼
    FASE 2: CANAL DE ESTADO TIPADO (Mediano Plazo - Arquitectura Limpia)
    ┌─────────────────────────────────────────────────────────────────┐
    │  DomainAgentResult<T>(conversationalText, payload, auditTrace)   │
    │  • El contrato entre agentes deja de ser String                 │
    │  • Sub-agentes devuelven State Records inmutables               │
    │  • Requiere salida estructurada (ChatClient.entity)             │
    └─────────────────────────────────────────────────────────────────┘
                  │
                  ▼
    FASE 3: ORQUESTACIÓN HÍBRIDA / TRANSACCIONAL (Largo Plazo / Nuevos Casos de Uso)
    ┌─────────────────────────────────────────────────────────────────┐
    │  ¿Cuándo dejar el Router LLM de Spring AI?                      │
    │  • Flujos transaccionales (Alquiler = Mora + Stock + Pago)      │
    │  • Adopción de Embabel (GOAP) sobre Spring Boot:                │
    │      Records + @Action(pre = "...") determinístico              │
    │  • Opcional A2A (Agent2Agent) si los agentes se vuelven remotos │
    └─────────────────────────────────────────────────────────────────┘
```

---

## 3. Fase 1: Cerrar los Huecos Reales del Ciclo MCP

### Objetivo
Corregir las asimetrías concretas entre lo que los servidores MCP publican y lo que el agente efectivamente consume, y unificar la traza distribuida. **No hay aquí un problema de duplicación de reglas de negocio: ese ya está resuelto** (ver 1.1).

### 1.1 Estado real de la fuente de verdad del alta de películas (verificado)
Conviene dejarlo asentado porque es fácil diagnosticarlo mal. Hoy, en el código:

- [`MovieCreationProcedure.steps()`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieCreationProcedure.java) es la **única** fuente de los pasos del alta.
- [`MovieMcpResources`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieMcpResources.java) la publica como recurso `catalog://procedures/movie-creation`.
- [`MovieMcpPrompts.altaPelicula(titulo)`](file:///home/horacio/proyectos/unrn/taller/springboot-sso/catalog-service/src/main/java/ar/unrn/video/catalog/mcp/MovieMcpPrompts.java) usa **ese mismo método**, agregando solo una intro que nombra el título concreto.
- [`CatalogSubAgent.buildSystemPrompt(...)`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/subagents/CatalogSubAgent.java) ya inyecta `catalog://procedures/movie-creation` y `catalog://genres` en **cada** llamada, vía su método privado `resourceSection(...)`, con degradación silenciosa (log de warning) si la lectura falla.

Es decir: **la fuente de verdad ya está unificada y ya llega al agente**. Lo que sigue escrito a mano en `CatalogSubAgent` es la regla de formato de Generative UI (el fence `json:movies`), que es un contrato con el frontend React, no una regla de negocio del catálogo; por eso no corresponde moverla al servidor MCP.

**Consecuencia para el roadmap:** consumir `catalog-alta-pelicula` vía `getPrompt` es **opcional y de ganancia marginal** (ver 1.4), no el primer paso.

### 1.2 Hueco real: `MembershipSubAgent` no consume ningún recurso MCP
`membership-service` publica `MembershipMcpResources` (`membership://socios/{id}`) y `MembershipMcpPrompts` (`membership-alta-socio`), pero [`MembershipSubAgent`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/subagents/MembershipSubAgent.java) construye todo su system prompt con texto fijo en Java: no recibe `McpKnowledgeService` ni lee recurso alguno. `CatalogSubAgent` ya demostró el patrón contrario en el mismo repositorio.

**Acción Propuesta:**
1. Definir qué contexto del dominio de socios conviene inyectar de forma preventiva (estados de socio, reglas de mora, categorías). Hoy **no existe** un recurso sin parámetros equivalente a `catalog://genres`, así que primero hay que publicarlo en `membership-service`.
2. Elevar `resourceSection(...)` a [`AbstractDomainSubAgent`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/subagents/AbstractDomainSubAgent.java), para que ambos subagentes compartan el mismo mecanismo de inyección y la misma política de degradación ante fallo de lectura.

> **Nota sobre recursos parametrizados:** `membership://socios/{id}` y `catalog://movies/{id}` son *resource templates*. No sirven como contexto previo al razonamiento, porque el `{id}` recién se conoce durante la conversación. Su consumo correcto es bajo demanda, y ese camino hoy ya lo cubren las Tools.

### 1.3 Hueco real: traza distribuida cortada en el cliente MCP
[`McpClientConfiguration`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/config/McpClientConfiguration.java) **ya usa** `HttpClientStreamableHttpTransport`, el cliente Streamable HTTP síncrono oficial, contra los endpoints `/mcp` de ambos servicios (que ya declaran `protocol: stateless` + `streamable-http`). Ese punto no requiere acción: está cubierto.

Lo que falta es la correlación. No se propaga `traceparent` en las llamadas salientes del cliente MCP, así que el trace del agente y el del microservicio quedan desacoplados en OpenTelemetry / LangSmith.

**Acción Propuesta:**
- Propagar el contexto de traza W3C en el transporte MCP, siguiendo el mismo mecanismo con el que hoy se relaya el JWT del usuario (`transportContextProvider` + `McpTransportContext`), que ya resuelve el problema análogo de llevar algo del hilo de la request al request HTTP saliente.

### 1.4 Opcional: consumo del primitivo Prompts
Si se decide implementarlo igual —tiene valor pedagógico, es el único de los tres primitivos que el cliente no ejercita— la extensión a [`McpKnowledgeService`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/mcp/McpKnowledgeService.java) sería:

```java
public String getPromptInstructions(final String promptName, final Map<String, Object> arguments) {
    final McpSyncClient client = clientForPrompt(promptName);
    final McpSchema.GetPromptResult result = client.getPrompt(
        McpSchema.GetPromptRequest.builder(promptName).arguments(arguments).build()
    );
    return result.messages().stream()
        .map(McpSchema.PromptMessage::content)
        .filter(McpSchema.TextContent.class::isInstance)
        .map(McpSchema.TextContent.class::cast)
        .map(McpSchema.TextContent::text)
        .collect(Collectors.joining("\n\n"));
}
```

> **Detalle de API (`mcp-core` 2.0.0, verificado con `javap`):** `PromptMessage.content()` devuelve `McpSchema.Content`, una interfaz que **solo** declara `type()`. **No existe `Content.text()`**: hay que filtrar y castear a `TextContent`, exactamente como `readResource(...)` ya hace con `TextResourceContents`. `GetPromptRequest.builder(name).arguments(map).build()` es la forma vigente; los constructores cortos del record están deprecados.

> **Detalle de ruteo:** `clientForUri(...)` rutea por el scheme de la URI (`catalog://` / `membership://`). Los prompts se identifican por **nombre** (`catalog-alta-pelicula`), sin scheme, así que `clientForPrompt(...)` necesita otro criterio: prefijo del nombre, o `listPrompts()` por cliente al inicio de la request.

## 4. Fase 2: Canal de Estado Tipado entre Agentes

### Objetivo
Convertir el contrato entre sub-agentes y orquestador en un **tipo**, en lugar de una convención de formato que depende de que el LLM del sub-agente respete un fence de markdown.

### 2.1 El Problema Actual (estado verificado)
Primero hay que ser precisos sobre qué está y qué no está resuelto. En [`OrchestratorTools`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/orchestrator/OrchestratorTools.java), la respuesta del sub-agente **ya** pasa por `captureArtifacts(...)`, que extrae los bloques estructurados, los manda al [`ExecutionTracker`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/tracker/ExecutionTracker.java) y al stream SSE (`AgentStreamEvent.artifact(...)`), y devuelve al supervisor **solo la prosa, ya sin el fence**:

```java
// Estado actual, no propuesto:
private String captureArtifacts(final String agentName, final String subAgentResponse) {
    final GenerativeUiExtractor.ExtractionResult extraction = generativeUiExtractor.extract(subAgentResponse);
    if (!extraction.artifacts().isEmpty()) {
        tracker.recordArtifacts(extraction.artifacts());       // -> auditoría
        eventConsumer.accept(AgentStreamEvent.artifact(...));  // -> SSE a React
    }
    return extraction.text();  // el orquestador SOLO ve esto
}
```

O sea: **el supervisor ya no puede romper el JSON, porque nunca lo ve.** Lo que queda pendiente está un escalón antes:

1. El contrato entre sub-agente y orquestador sigue siendo `String` (`AbstractDomainSubAgent.execute(...)` devuelve `String`), y nada en el compilador impide que un sub-agente cambie el formato.
2. La estructura depende de que el LLM del sub-agente respete un fence que le pedimos **en prosa**, dentro del system prompt.
3. La recuperación se hace con expresiones regulares en [`GenerativeUiExtractor`](file:///home/horacio/proyectos/unrn/taller/videoclub-agent/src/main/java/ar/unrn/video/agent/generativeui/GenerativeUiExtractor.java), que ya tuvieron que endurecerse ante casos reales (fence vacío que se filtraba a pantalla, segundo bloque no capturado por no ser global, `json:socios` colisionando con la rama `json`).

### 2.2 Solución Arquitectónica: `DomainAgentResult<T>`
Definir un canal de respuesta estructurado para los sub-agentes:

```java
public record DomainAgentResult<T>(
    String conversationalSummary,       // Lo único que lee el LLM orquestador
    T structuredPayload,                // Objeto tipado (ej: List<MovieItem>, SocioDTO)
    boolean requiresHumanApproval,      // Flag para guardrail HITL
    List<String> auditTrail             // Registro explicable de qué tools/resources usó
) {}
```

**Beneficios, con su alcance real:**
1. **Tipado en el borde del sub-agente:** el payload deja de ser una convención de prosa y pasa a formar parte de la firma. Un cambio de formato rompe la compilación, no la UI en runtime.
2. **Menos superficie de regex:** desaparece la extracción por fence en el camino feliz. Atención: **no elimina el parseo, lo mueve.** El sub-agente sigue siendo un LLM que produce texto; para poblar `T` hace falta salida estructurada (`ChatClient.entity(...)`) o devolver directamente el resultado de la tool MCP sin pasarlo por el modelo.
3. **Guardrail y auditoría explícitos:** `requiresHumanApproval` y `auditTrail` dejan de ser convenciones implícitas y pasan a ser campos del contrato.

**Lo que NO es un beneficio de esta fase**, porque ya lo resuelve `captureArtifacts`: impedir que el orquestador reformule o destruya el JSON, y el ahorro de tokens asociado.

**Alcance del refactor** (para dimensionar honestamente el esfuerzo): cambia la firma de `AbstractDomainSubAgent.execute(...)`, ambos sub-agentes, los dos `@Tool` de `OrchestratorTools`, y exige adoptar salida estructurada en el sub-agente. No es un retoque de interfaz.

---

## 5. Fase 3: Orquestación Determinística y Transaccional (Largo Plazo)

### Objetivo
Establecer un criterio claro y técnico de **cuándo dejar de usar el patrón Supervisor en lenguaje natural de Spring AI** y cómo evolucionar hacia orquestación determinística.

### 3.1 Señales de Salida ("Signals to Leave Spring AI")
Adoptando los criterios de Jitin Kayyala, deberemos desacoplar la orquestación de Spring AI cuando:
1. **Aparezcan flujos multi-paso transaccionales:**
   - Ejemplo: *Alquiler de película con tarjeta o saldo de socio*.
   - Paso 1: Consultar estado y mora en `membership-service`.
   - Paso 2: Si tiene mora $> 0$, bloquear o requerir confirmación.
   - Paso 3: Verificar disponibilidad física de copia en `catalog-service`.
   - Paso 4: Generar transacción y actualizar stock.
2. **El system prompt del orquestador supere 2 pantallas** detallando árboles de decisión `if/else`.
3. **Auditoría regulatoria exija justificar decisiones:** Si un socio pregunta *"¿Por qué me denegaron el alquiler?"*, el sistema debe responder con un trace de precondiciones evaluadas, no con *"porque el modelo así lo decidió"*.

### 3.2 Patrón Híbrido con Embabel (Goal-Oriented Action Planning - GOAP)
Si el VideoClub incorpora transacciones complejas, la alternativa más compatible con el ecosistema Spring Boot es **Embabel** (creado por Rod Johnson):

```java
// 1. Estado Tipado inmutable (Records)
public record RentalRequest(Long memberId, Long movieId) {}
public record MemberEligibilityChecked(RentalRequest request, boolean eligible, String reason) {}
public record MovieStockReserved(MemberEligibilityChecked memberCheck, String reservationId) {}
public record RentalCompleted(String rentalId, List<String> auditSteps) {}

// 2. Acciones del Agente con Precondiciones Tipadas
@Agent(description = "Gestor del proceso de alquiler de películas")
@Component
public class RentalWorkflowAgent {

    @Action(description = "Valida habilitación y deuda del socio")
    public MemberEligibilityChecked verifyMember(RentalRequest req, OperationContext ctx) {
        // Ejecución determinística contra membership-service via MCP
        return ...;
    }

    @Action(
        description = "Reserva la película si el socio está habilitado",
        pre = "eligible == true"
    )
    public MovieStockReserved reserveMovie(MemberEligibilityChecked check, OperationContext ctx) {
        // Ejecución contra catalog-service via MCP
        return ...;
    }

    @Action(pre = "reservationId != null")
    @AchievesGoal(description = "Alquiler formalizado y comprobante emitido")
    public RentalCompleted finalizeRental(MovieStockReserved reserved) {
        return new RentalCompleted(...);
    }
}
```

> **Pendiente de verificación:** la firma exacta de `@Action(pre = ...)` en Embabel **no** se validó contra la documentación oficial. El snippet ilustra el patrón GOAP, no una API confirmada; verificarla antes de usarlo como referencia.

* **Por qué funciona:** El planificador GOAP calcula el camino determinístico desde `RentalRequest` hasta `RentalCompleted`. No hay un prompt gigante coordinando; hay tipos Java y precondiciones formales.
* **Patrón Híbrido:** **Spring AI** sigue gestionando las conexiones LLM y los clientes MCP; **Embabel** gestiona el grafo de ejecución y la máquina de estados.

### 3.3 Protocolo Agent2Agent (A2A)
Si en el futuro `catalog-service` o `membership-service` se transforman en servicios completamente autónomos desplegados en diferentes stacks o clusters (ej: un servicio en Python y otro en Java), se adoptará el protocolo **Agent2Agent (A2A)** para que el orquestador descubra y delegue tareas a agentes remotos a través del estándar de red, superando la limitación de agentes compilados en el mismo monolito `videoclub-agent`.

---

## 6. Matriz de Decisión y Criterios de Transición

| Criterio | Enfoque Actual (Spring AI Router) | Fase 2 (Estado Tipado Propio) | Fase 3 (Embabel GOAP Híbrido) |
| :--- | :--- | :--- | :--- |
| **Complejidad del flujo** | Consultas simples y altas directas. | Consultas con Generative UI rica y datos estructurados. | Procesos multi-paso con rollback, mora y cobros. |
| **Determinismo** | Bajo (el LLM decide cuándo rutear). | Medio (el ruteo es LLM, pero los datos son inmutables). | **Alto (100% determinístico por tipos y precondiciones).** |
| **Costo en Tokens** | **Bajo**: `captureArtifacts` ya quita el JSON antes de que el supervisor lo lea. | Igual o levemente menor (resumen explícito en lugar de prosa residual). | **Óptimo** (solo llama al LLM donde hace falta razonamiento). |
| **Explicabilidad / Auditoría** | Logs de herramientas en `ExecutionTracker`. | Trazas estructuradas de negocio por agente. | **Audit Trail formal por objetivo alcanzado.** |
| **Esfuerzo de Adopción** | 0 (Ya implementado). | **Medio**: cambia `AbstractDomainSubAgent.execute`, ambos sub-agentes y los `@Tool` de `OrchestratorTools`, y exige salida estructurada en el sub-agente. | Medio (Incorporación de dependencia Embabel). |

---

## 7. Próximos Pasos Recomendados

Ordenados por valor verificado contra el código, no por número de fase:

1. **Propagar `traceparent` en el cliente MCP** (§1.3). Menor esfuerzo, mayor retorno: hoy el trace se corta entre el agente y los microservicios.
2. **Cerrar la asimetría de `MembershipSubAgent`** (§1.2). Publicar en `membership-service` un recurso de dominio sin parámetros y consumirlo desde el sub-agente, elevando `resourceSection(...)` a `AbstractDomainSubAgent` para no duplicar el mecanismo.
3. **Evaluar `DomainAgentResult<T>` junto con salida estructurada** (§4). Tiene sentido solo si se adopta `ChatClient.entity(...)` o se devuelve el resultado de la tool sin intermediación del LLM; sin eso se cambia una forma de parseo por otra.
4. **Opcional, de bajo valor: consumir `catalog-alta-pelicula`** (§1.4). La fuente de verdad ya está unificada vía `MovieCreationProcedure`; el aporte se limita a la intro parametrizada y al valor pedagógico de ejercitar el tercer primitivo desde el cliente.
5. **Mantener este documento en `docs/plan`.** Servirá de guía arquitectónica cuando aparezcan requerimientos transaccionales reales (alquiler con mora, stock y cobro), que son el disparador formal de la Fase 3.
