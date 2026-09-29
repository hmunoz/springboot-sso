# Plan: Carrito de Compras en Membership — Event-Carried State Transfer (ECST) vía RabbitMQ

> [!NOTE]
> **Estado: Plan de diseño y guía pedagógica para clase.** Este documento detalla la arquitectura, el diseño técnico, la topología de mensajería y los pasos de implementación para introducir el patrón Event-Carried State Transfer (ECST) en el taller, contrastándolo con la integración previa de Keycloak.

---

## 1. Contexto y Objetivos Pedagógicos

El caso de uso a modelar en el taller consiste en permitir que los socios gestionen un **Carrito de Compras / Alquiler de Películas** dentro de `membership-service`, consumiendo información de películas proveniente de `catalog-service`.

### 1.1. Los Fundamentos Arquitectónicos

Este diseño se apoya en los ADRs vigentes del proyecto:

* **[ADR-013 (Database per Service)](../adr.md#adr-013-un-esquema-por-microservicio-database-per-service):** `membership-service` no tiene acceso al esquema `video_catalog`. No es posible hacer un `JOIN` relacional.
* **[ADR-014 (Comunicación Este-Oeste Asíncrona)](../adr.md#adr-014-comunicación-este-oeste-solo-por-bus-de-mensajes-no-por-http):** Está prohibido hacer llamadas HTTP sincrónicas (`GET http://catalog:8081/movies/{id}`) desde el carrito. Hacerlo crearía acoplamiento temporal y transformaría los microservicios en un *monolito distribuido*.
* **[ADR-015 (Event-Carried State Transfer y Proyecciones Locales)](../adr.md#adr-015-sincronización-entre-verticales-mediante-event-carried-state-transfer-ecst-y-réplica-de-proyecciones):** Patrón canónico de integración para la sincronización entre dominios.

### 1.2. La Gran Diferencia con Keycloak: El Dilema del Dual-Write

En la integración con Keycloak ([`keycloak-rabbitmq-integration.md`](../keycloak-rabbitmq-integration.md)), el productor era una caja negra externa: Keycloak hacía `COMMIT` en su base y luego su SPI intentaba publicar en RabbitMQ. Si el broker fallaba, el evento se descartaba; por eso en [ADR-002](../adr.md#adr-002-por-qué-no-aplican-saga-ni-transactional-outbox-en-la-sincronización-de-socios) se justificó la necesidad de un endpoint de reconciliación.

En `catalog-service`, **nosotros somos los dueños de la transacción en Spring Boot**. Si mezclamos base de datos y broker ingenuamente dentro de `@Transactional`, caemos en la trampa del **evento fantasma**:

```mermaid
flowchart TD
    subgraph BAD ["Antipatrón: Publicación dentro de @Transactional"]
        direction TB
        B1["MovieService.save()"] --> B2["INSERT INTO movie (PostgreSQL)"]
        B2 --> B3["rabbitTemplate.convertAndSend()"]
        B3 --> B4{"COMMIT en PostgreSQL"}
        B4 -- "Falla commit (timeout/lock)" --> B5["Rollback en PostgreSQL"]
        B5 -.-> B6["PELIGRO: ¡El evento fantasma ya fue emitido al broker!"]
    end

    subgraph GOOD ["Solución para el Taller: @TransactionalEventListener"]
        direction TB
        G1["MovieService.save()"] --> G2["INSERT INTO movie (PostgreSQL)"]
        G2 --> G3["applicationEventPublisher.publishEvent()"]
        G3 --> G4{"COMMIT en PostgreSQL"}
        G4 -- "Falla commit" --> G5["Rollback en BD (Listener NUNCA se ejecuta)"]
        G4 -- "Commit exitoso" --> G6["AFTER_COMMIT dispara MovieEventPublisher"]
        G6 --> G7["rabbitTemplate.convertAndSend(videoclub.events)"]
    end
```

### 1.3. El "Por Qué" Real: Autonomía y Seguridad Financiera

Replicar una proyección mínima (`id`, `title`, `price`) en la base de datos de membresía no se hace principalmente para validar si un ID existe, sino para:

1. **Autonomía Operativa y Resiliencia:** Si `catalog-service` sufre una caída o mantenimiento, el carrito sigue operativo al 100% leyendo de su base local.
2. **Seguridad e Integridad Financiera:** El cliente frontend envía `{ movieId, quantity }`. El precio unitario **jamás se recibe del cliente**; se toma de la proyección local sincronizada, evitando manipulación maliciosa de precios.

---

## 2. Topología Estática y Flujo de Mensajería

### 2.1. Mecánica de Enrutamiento: Productor, Routing Key, Exchange, Binding y Consumidor

Este diagrama ilustra las piezas fundamentales de RabbitMQ y cómo viaja el mensaje desde el productor hasta el consumidor:

```mermaid
flowchart LR
    subgraph PROD_BOX ["1. Productor (catalog-service)"]
        direction TB
        P["MovieEventPublisher<br/><code>rabbitTemplate.convertAndSend()</code>"]
    end

    subgraph BROKER_BOX ["2. Broker RabbitMQ"]
        direction TB
        EX{{"Exchange de Dominio<br/><b>videoclub.events</b><br/><small>(Type: topic, durable)</small>"}}
        
        Q[["Cola del Consumidor<br/><b>membership.movie-events.queue</b><br/><small>(durable)</small>"]]
        
        EX -->|"<b>Binding Pattern:</b><br/><code>movie.#</code>"| Q
    end

    subgraph CONS_BOX ["3. Consumidor (membership-service)"]
        direction TB
        C["MovieEventListener<br/><code>@RabbitListener</code>"]
        DB[("PostgreSQL<br/><code>movie_projection</code>")]
        C -->|"upsert local"| DB
    end

    %% Flujo del mensaje
    P -->|"<b>Publicación:</b><br/>Routing Key: <code>movie.created</code><br/><i>Payload: {id, title, price}</i>"| EX
    Q -->|"Entrega (Push AMQP)"| C

    classDef comp fill:#f0fff4,stroke:#38a169,stroke-width:2px
    classDef exchange fill:#fff4e6,stroke:#dd6b20,stroke-width:2px
    classDef queue fill:#e6f7ff,stroke:#2b6cb0,stroke-width:2px
    classDef db fill:#edf2f7,stroke:#4a5568,stroke-width:2px
    class P,C comp
    class EX exchange
    class Q queue
    class DB db
```

#### Los 4 Conceptos Clave para el Aula

1. **El Productor NUNCA publica a una cola:** `catalog-service` no conoce colas ni sabe quién lo escucha. Publica al exchange (`videoclub.events`) con una etiqueta de enrutamiento: la **Routing Key** (`movie.created` o `movie.updated`).
2. **El Exchange es el router:** Al ser de tipo `topic`, examina la Routing Key y busca coincidencias con las reglas de enlace (**Bindings**) registradas por las colas.
3. **El Binding con comodín (`movie.#`):** La cola declara su interés mediante el patrón `movie.#`, donde `#` coincide con cero o más palabras (captura `movie.created`, `movie.updated`, `movie.deleted`).
4. **El Consumidor solo escucha su COLA:** `@RabbitListener` se conecta exclusivamente a `membership.movie-events.queue`. Si `membership-service` se apaga, la cola retiene los mensajes en disco y se los entrega al reiniciar sin perder estado.

---

### 2.2. Topología de Resiliencia y Dead Letter Queue (DLQ)

Estructura completa de aislamiento de mensajes envenenados para prevenir bucles infinitos:

```mermaid
flowchart TB
    %% Productores
    PROD["catalog-service<br/><i>MovieEventPublisher</i>"]

    %% Broker
    subgraph RMQ ["RabbitMQ (Exchange de Negocio y DLX)"]
        direction TB
        EX{{"videoclub.events<br/><small>topic · durable</small>"}}
        Q[["membership.movie-events.queue<br/><small>durable · x-dead-letter-exchange</small>"]]
        DLX{{"videoclub.events.dlx<br/><small>topic · durable</small>"}}
        DLQ[["membership.movie.dlq<br/><small>durable</small>"]]
    end

    %% Consumidores y Persistencia
    CONSUMER["membership-service<br/><i>MovieEventListener</i>"]
    DB_MEM[("PostgreSQL: video_membership<br/><b>tabla movie_projection</b>")]

    PROD -- "publica con routing key<br/>movie.created / movie.updated" --> EX
    EX -- "binding pattern:<br/>movie.#" --> Q
    Q -- "consume evento canónico" --> CONSUMER
    CONSUMER -->|"upsert idempotente"| DB_MEM

    Q -. "3 intentos fallidos<br/>defaultRequeueRejected = false" .-> DLX
    DLX -- "routing key:<br/>membership.movie.dlq" --> DLQ
```

---

### 2.3. Flujo de Interacción Temporal (End-to-End)

Secuencia temporal dividida en los tres momentos del ciclo de vida:

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Administrador
    participant CS as catalog-service (MovieService)
    participant Spring as Spring Event Publisher
    participant PG_CAT as PostgreSQL (video_catalog)
    participant PUB as MovieEventPublisher (@TransactionalEventListener)
    participant RMQ as RabbitMQ (videoclub.events)
    participant MS_LST as membership-service (MovieEventListener)
    participant PG_MEM as PostgreSQL (video_membership)
    actor Socio as Socio / Cliente
    participant Cart as membership-service (CartService)

    Note over Admin, RMQ: 1. Ciclo de Catálogo y Dual-Write Seguro
    Admin->>CS: POST /movies (Alta de Película)
    CS->>PG_CAT: INSERT INTO movie (Transacción ACID)
    CS->>Spring: publishEvent(MovieDomainEvent)
    CS->>PG_CAT: COMMIT confirmado
    Note over Spring, PUB: AFTER_COMMIT dispara el listener
    PUB->>RMQ: Publica en videoclub.events (movie.created, Publisher Confirm)

    Note over RMQ, PG_MEM: 2. Sincronización de Proyección Asíncrona (ECST)
    RMQ->>MS_LST: Consume de membership.movie-events.queue
    MS_LST->>PG_MEM: Upsert idempotente en movie_projection (id, title, price)

    Note over Socio, PG_MEM: 3. Operación Autónoma del Carrito (Cero HTTP este-oeste)
    Socio->>Cart: POST /api/cart/items { movieId: 10001, quantity: 2 }
    Cart->>PG_MEM: SELECT FROM movie_projection WHERE id = 10001
    alt Película no existe en proyección
        Cart-->>Socio: 404 Not Found ("Película inexistente")
    else Película encontrada
        Cart->>PG_MEM: Guarda CartItem con precio unitario local
        Cart-->>Socio: 200 OK (Carrito actualizado con total calculado)
    end
```

---

## 3. Modelo de Datos y Esquemas Aislados

Cumpliendo con [ADR-013](../adr.md#adr-013-un-esquema-por-microservicio-database-per-service), cada microservicio tiene su esquema privado. `membership-service` almacena la réplica desnormalizada en `movie_projection`:

```mermaid
classDiagram
    direction LR

    class SchemaCatalog {
        <<Database: video_catalog>>
    }
    class Movie {
        +Long id [PK]
        +String title
        +Genre genre
        +BigDecimal price
        +String imageUrl
    }
    SchemaCatalog .. Movie

    class SchemaMembership {
        <<Database: video_membership>>
    }
    class MovieProjection {
        +Long id [PK]
        +String title
        +BigDecimal price
        +LocalDateTime updatedAt
    }
    class Cart {
        +Long id [PK]
        +Long socioId
        +LocalDateTime createdAt
        +BigDecimal total
    }
    class CartItem {
        +Long id [PK]
        +Long movieId
        +Integer quantity
        +BigDecimal unitPrice
        +BigDecimal subtotal
    }

    SchemaMembership .. MovieProjection
    SchemaMembership .. Cart
    SchemaMembership .. CartItem

    Cart "1" *-- "many" CartItem : contiene
    CartItem ..> MovieProjection : "referencia lógica local<br/>(sin FK entre bases)"
```

---

## 4. Lógica de Negocio y Seguridad en el Carrito

Flujo de decisión en `CartService.addItem()` que demuestra la validación local y el cálculo de importes confiable:

```mermaid
flowchart TD
    START(["POST /api/cart/items<br/>{ movieId, quantity }"]) --> AUTH["Extraer socioId del JWT"]
    AUTH --> GET_CART["Buscar o inicializar Cart activo del socio"]
    GET_CART --> QUERY_PROJ["SELECT * FROM movie_projection WHERE id = :movieId"]
    QUERY_PROJ --> FOUND{"¿Existe en la proyección?"}
    
    FOUND -- "No" --> ERR["404 Not Found<br/>'Película inexistente en catálogo'"]
    FOUND -- "Sí" --> PRICE["Tomar unitPrice = movie_projection.price<br/><b>(Ignora cualquier precio del cliente)</b>"]
    
    PRICE --> CALC["subtotal = unitPrice * quantity"]
    CALC --> SAVE_ITEM["Guardar / Actualizar CartItem en PostgreSQL"]
    SAVE_ITEM --> RECALC["Recalcular Cart.total"]
    RECALC --> RESP(["200 OK<br/>Retornar CartDTO con ítems y total"])

    classDef ok fill:#f0fff4,stroke:#38a169
    classDef err fill:#fff5f5,stroke:#e53e3e
    class RESP ok
    class ERR err
```

---

## 5. Plan de Implementación por Fases

### Fase 1: Productor en `catalog-service`

* **Paso 1.1:** Crear el DTO/Record canónico del evento de dominio:

  ```java
  public record MovieDomainEvent(Long movieId, String title, BigDecimal price, String eventType) {}
  ```

* **Paso 1.2:** En `MovieService`, inyectar `ApplicationEventPublisher` y emitir el evento en memoria tras guardar en `movieRepository.save(movie)`.
* **Paso 1.3:** Crear `MovieEventPublisher` con `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` que reciba el evento de Spring y utilice `RabbitTemplate` para despachar a `videoclub.events` con routing keys:
  * `movie.created` (al dar de alta una película).
  * `movie.updated` (al modificar precio o título).

### Fase 2: Topología de RabbitMQ en `membership-service`

* **Paso 2.1:** Configurar en `RabbitMQConfig.java`:
  * Cola durable: `membership.movie-events.queue`.
  * Dead Letter Exchange: `videoclub.events.dlx` con routing key `membership.movie.dlq`.
  * Binding: asociar la cola al exchange `videoclub.events` con pattern `movie.#`.
  * Establecer `defaultRequeueRejected = false` para evitar bucles infinitos en mensajes fallidos.

### Fase 3: Réplica Local (Proyección) en `membership-service`

* **Paso 3.1:** Crear la entidad `MovieProjection`:
  * Tabla `movie_projection` en el esquema `video_membership`.
  * Columnas: `id` (mismo ID del catálogo, sin generación automática), `title` (`varchar(255)`), `price` (`numeric(10,2)`), `updated_at` (`timestamp`).
* **Paso 3.2:** Crear `MovieProjectionRepository extends JpaRepository<MovieProjection, Long>`.
* **Paso 3.3:** Crear `MovieEventListener` con `@RabbitListener(queues = "membership.movie-events.queue")`:
  * Deserializar el payload.
  * Ejecutar upsert idempotente en `movie_projection`.

### Fase 4: Modelo y Lógica de Negocio del Carrito

* **Paso 4.1:** Modelar entidades de Carrito (`Cart` y `CartItem`).
* **Paso 4.2:** Implementar `CartService.addItem(Long socioId, AddCartItemDTO dto)`.
* **Paso 4.3:** Exponer endpoints REST en `CartResource`:
  * `GET /api/cart`: obtiene el estado actual del carrito y sus totales.
  * `POST /api/cart/items`: agrega o actualiza ítems.
  * `DELETE /api/cart/items/{movieId}`: remueve un ítem.

---

## 6. Guía para la Demostración en Clase ("Show & Tell")

1. **Flujo Feliz Asíncrono:**
   * Crear una película desde Swagger o cURL en `catalog-service` (`:8081`).
   * Mostrar en la consola de RabbitMQ (`http://localhost:15672`) el paso del mensaje por el exchange `videoclub.events` y la entrega en `membership.movie-events.queue`.
   * Verificar en la base de datos `video_membership` (`SELECT * FROM movie_projection`) que el registro apareció de inmediato.
2. **Prueba de Fuego de Resiliencia (Desacoplamiento Temporal):**
   * Detener el contenedor de catálogo:

     ```bash
     docker stop catalog-service
     ```

   * Ejecutar una petición para agregar la película al carrito en `membership-service` (`:8082`).
   * **Resultado esperado:** La operación responde `200 OK` inmediatamente, calculando totales sin demoras ni errores.
   * **Lección para los alumnos:** Si hubiéramos usado HTTP este-oeste, el carrito estaría completamente caído. Con ECST y proyección local, la disponibilidad es del 100%.
3. **Validación de Seguridad Financiera:**
   * Mostrar cómo el contrato de la API solo pide `{ movieId, quantity }` y no permite enviar precios desde el frontend, protegiendo las reglas de negocio.
