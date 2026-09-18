# Plan de Arquitectura: Human-in-the-Loop (HITL) en VideoClub UNRN

**Estado:** Propuesta de arquitectura técnica  
**Componentes involucrados:** `videoclub-agent` (:8085), `catalog-service` (:8081), `membership-service` (:8082), `apigateway` (:9500), `react-sso` (:5173), RabbitMQ (:5672) y Keycloak (:9090)

---

## 1. Motivación y Objetivos

Actualmente, el sistema opera bajo un modelo de **conversación autónoma cerrada**: el cliente interactúa exclusivamente con el Asistente AI (Agente Orquestador + Sub-agentes especializados).

El objetivo de este plan es incorporar el patrón **Human-in-the-Loop (HITL)** para permitir la intervención humana de usuarios del grupo `administrador` según sus permisos asignados. Esto resuelve dos problemáticas críticas en aplicaciones empresariales con IA:

1. **Límites de competencia o frustración del usuario:** el usuario solicita hablar con una persona real o el agente no logra resolver su consulta.
2. **Operaciones críticas o destructivas:** acciones de alto impacto (bajas de socios, modificaciones de precios, condonación de deudas) requieren supervisión y autorización humana antes de persistirse.

---

## 2. Alternativas de Implementación

Evaluamos dos patrones arquitectónicos principales según el caso de uso deseado:

```text
                  ┌───────────────────────────────────────────────────────────┐
                  │                 CASOS DE USO HUMAN-IN-THE-LOOP            │
                  └─────────────────────────────┬─────────────────────────────┘
                                                │
                 ┌──────────────────────────────┴──────────────────────────────┐
                 ▼                                                             ▼
    ┌─────────────────────────┐                                   ┌─────────────────────────┐
    │  PATRÓN 1: HAND-OFF     │                                   │ PATRÓN 2: APROBACIÓN    │
    │  Live Operator Takeover │                                   │ Action Guardrail Gate   │
    ├─────────────────────────┤                                   ├─────────────────────────┤
    │ • Cliente pide humano   │                                   │ • Acción sensible       │
    │ • Pausa del LLM         │                                   │ • Intercepta tool call  │
    │ • Chat bidireccional    │                                   │ • Admin aprueba/rechaza │
    │ • Admin toma control    │                                   │ • Reanuda ejecución     │
    └─────────────────────────┘                                   └─────────────────────────┘
```

---

### Patrón 1: Escalación y Derivación a Operador Humano (Live Hand-off)

Permite transferir una sesión activa de chat desde el Asistente AI a un operador humano en vivo.

#### Flujo del Hand-off a Operador

1. **Disparador (Trigger)**:
   - *Explícito:* El cliente escribe *"Quiero hablar con una persona"* o *"Comunícame con soporte"*.
   - *Automático:* El supervisor detecta baja confianza, bucles de consulta o errores consecutivos.

2. **Herramienta en el Orquestador (`escalateToHumanSupport`)**:
   - Spring AI invoca una `@Tool` en `OrchestratorTools`:

     ```java
     @Tool(description = "Deriva la conversación a un operador humano cuando el usuario lo solicita explícitamente o la consulta excede la capacidad del asistente.")
     public String escalateToHumanSupport(
         @ToolParam(description = "Motivo de la derivación") String reason,
         @ToolParam(description = "Resumen conciso del estado de la consulta") String summary
     )
     ```

3. **Publicación del Evento**:
   - Se publica un mensaje en **RabbitMQ** (exchange `videoclub.support.exchange`, routing key `support.ticket.new`):

     ```json
     {
       "ticketId": "ticket-1024",
       "conversationId": "session-178967711",
       "userId": "usuariocliente",
       "fullName": "Juan Perez",
       "reason": "Consulta sobre saldo no resuelta",
       "summary": "El usuario reclama un cobro indebido en su alquiler",
       "timestamp": "2026-09-17T20:50:00Z"
     }
     ```

4. **Notificación en Tiempo Real al Operador**:
   - A través del microservicio de notificaciones o un canal SSE de administración (`/api/agent/admin/events`), los usuarios conectados que cuenten con el permiso `support-takeover` (asignado al grupo `administrador`) reciben una alerta en su barra de navegación.

5. **Pausa del LLM (`session_status`)**:
   - La sesión se marca en estado `HUMAN_TAKEOVER`. En este estado, los nuevos mensajes del cliente no se envían a OpenAI, sino a una cola de mensajes bidireccional vinculada a la sesión.

6. **Toma de Control (Takeover)**:
   - El operador abre el panel de soporte, revisa el historial conversacional (extraído de `ChatMemory`) y escribe una respuesta.
   - El mensaje del operador se transmite al cliente vía SSE (`event: operator_message`), indicando claramente el nombre del operador que lo está atendiendo.

---

### Patrón 2: Supervisión y Aprobación de Acciones Críticas (Approval Guardrails)

El agente continúa conversando normalmente con el cliente, pero detiene la ejecución de herramientas sensibles hasta que un operador con permisos otorga su aprobación.

#### Flujo del Guardrail de Aprobación

1. **Detección de Acción Crítica**:
   - Por ejemplo, en `MembershipSubAgent`, si una herramienta sensible como `cancel_membership` o `waive_late_fees` es solicitada.

2. **Creación de Solicitud de Aprobación**:
   - En lugar de ejecutar directamente la herramienta MCP, se genera una orden pendiente en estado `PENDING_APPROVAL`:

     ```java
     public record ApprovalRequest(
         String requestId,
         String conversationId,
         String requestedBy,
         String toolName,
         Map<String, Object> parameters,
         Instant expiresAt
     )
     ```

3. **Emisión de Artefacto Generativo UI**:
   - El agente emite un evento `artifact` con `kind: "approval_pending"`.
   - En la pantalla del cliente se muestra una tarjeta: *"Esta operación requiere autorización de un supervisor. Solicitud #REQ-882 en proceso..."*.

4. **Acción del Administrador**:
   - En su panel, el operador ve el detalle de la solicitud y hace click en **[Aprobar]** o **[Rechazar]**.
   - El frontend llama a:
     `POST /api/agent/approvals/{requestId}/decision` con `{ "approved": true, "notes": "Autorizado por promoción" }`.

5. **Reanudación del Agente**:
   - Al validarse la aprobación con el token del operador portador del permiso correspondiente, la herramienta MCP se ejecuta y el agente reanuda su respuesta hacia el cliente informándole el resultado exitoso.

---

## 3. Matriz Comparativa de Alternativas

| Criterio | Patrón 1: Live Hand-off (Operador) | Patrón 2: Aprobación de Acciones (Guardrails) |
| :--- | :--- | :--- |
| **Complejidad de Implementación** | **Media - Alta** (requiere chat bidireccional y presencia de operadores). | **Baja - Media** (mantiene el ciclo request/response existente y la arquitectura MCP). |
| **Disponibilidad Humana Requerida** | **Síncrona**: requiere que haya un operador conectado en ese mismo instante. | **Asíncrona**: el operador puede aprobar en diferido o dentro de una ventana de tiempo. |
| **Impacto en el Frontend React** | Nueva vista de bandeja de entrada para operadores + chat multiparte. | Nuevas tarjetas interactivas de aprobación en la UI actual. |
| **Infraestructura Requerida** | RabbitMQ + WebSocket/SSE bidireccional + persistencia de tickets. | Endpoint REST de decisión + almacenamiento temporal (PostgreSQL/Redis). |
| **Riesgo Operativo Cubierto** | Atención al cliente deficiente o límites del LLM. | Daño a datos, errores transaccionales o desvío de reglas de negocio. |

---

## 4. Arquitectura de Integración con el Stack Actual

### A. Keycloak SSO y Control de Acceso por Permisos (PBAC / Authorities)

- **Autorización por Permisos Granulares (Authorities)**:
  - Siguiendo el estándar establecido en `springboot-sso` (ver `docs/seguridad-oauth2-openid-connect-keycloak.md`), la **autorización** se rige por permisos específicos (`clientRoles` del cliente `videoclub-frontend`) mapeados como authorities en Spring Security:

    ```java
    // Autorización por permiso específico del dominio del agente (Human-in-the-Loop):
    @PreAuthorize("hasAuthority('agent-permission-hitl')")
    // o para autorizaciones sobre datos sensibles de socios:
    @PreAuthorize("hasAuthority('socio-permission-write')")
    ```

- **Identidad vs. Autorización**:
  - **Identidad (Grupos):** El grupo `administrador` (consultado vía `hasGroup('administrador')`) define a qué colectivo organizativo pertenece el usuario.
  - **Autorización (Permisos):** La capacidad de operar o intervenir el chat se evalúa siempre mediante el permiso granular **`agent-permission-hitl`** (Client Role en el cliente `videoclub-frontend`), asignado al grupo `administrador` en el realm de Keycloak.
  - En la UI de React (`usePermissions()`):

    ```typescript
    // Se valida el permiso específico para la acción, no el rol global:
    const canOperateHITL = hasPermission('agent-permission-hitl');
    ```

### B. Spring Cloud Gateway (:9500)

- Rutas a exponer en `application.yml` del Gateway:

  ```yaml
  - id: agent-support-stream
    uri: http://videoclub-agent:8085
    predicates:
      - Path=/api/agent/support/**, /api/agent/approvals/**
    filters:
      - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE
  ```

### C. RabbitMQ (Mensajería Asíncrona)

- Aprovechamiento del contenedor `videoclub-rabbit-1` ya configurado en la red Docker:
  - Exchange: `videoclub.agent.events` (Topic Exchange).
  - Colas: `q.agent.support.requests`, `q.agent.action.approvals`.

---

## 5. Roadmap de Implementación Recomendado

Para maximizar el valor sin sobredimensionar la complejidad de entrada, se recomienda un enfoque iterativo en 3 fases:

### Fase 1: Guardrail de Aprobación de Acciones (Patrón 2)

- Extender `OrchestratorTools` o sub-agentes para generar tickets de aprobación antes de ejecutar operaciones críticas.
- Agregar componente React interactivo `ApprovalCard.tsx` en `react-sso`.
- Endpoint seguro `POST /api/agent/approvals/{id}` con `@PreAuthorize("hasAuthority('...')")`.

### Fase 2: Registro de Solicitud de Soporte Asíncrona

- Agregar `@Tool requestHumanAssistance(...)` en el Orquestador.
- Cuando el cliente pide un humano, el agente recopila los datos y publica el ticket en RabbitMQ.
- El agente responde amablemente: *"Tu solicitud de asistencia #1024 fue enviada al equipo de soporte. Un operador te contactará a la brevedad."*

### Fase 3: Live Operator Takeover (Patrón 1 Completo)

- Desarrollo del panel `/admin/soporte` en React protegido con `hasPermission('support-takeover')`.
- Transición en caliente del WebSocket/SSE del cliente de modo `AI` a modo `HUMAN`.
