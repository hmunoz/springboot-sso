# Actualizar Java y Spring Boot

## Versiones objetivo

| Componente | Actual | Objetivo | Nota |
| --- | --- | --- | --- |
| Java | 21 (`21.0.1-tem`) | **25** | LTS, compatible con Spring Boot 4.x |
| Spring Boot | 3.5.5 | **4.1.1** | 4.1.5 no existe aún — usar 4.1.1 (última GA) |
| Spring Framework | 6.x | **7.x** | Actualización transitiva vía Spring Boot |

> [!CAUTION]
> Spring Boot 4.x es una actualización **mayor** — hay breaking changes reales.
> Seguir el orden del plan para no perderse nada.

---

## 1. SDK — Java 25

Actualizar `.sdkmanrc`:

```text
java=25.0.1-tem
```

Instalar con SDKMAN:

```bash
sdk install java 25.0.1-tem
sdk use java 25.0.1-tem
```

Verificar:

```bash
java -version
```

---

## 2. `pom.xml` — Spring Boot 4.1.1 + Java 25

```xml
<!-- Parent -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
</parent>

<!-- Properties -->
<properties>
    <java.version>25</java.version>
</properties>
```

---

## 3. Dependencias de terceros — revisar compatibilidad

| Dependencia | Versión actual | Acción |
| --- | --- | --- |
| `springdoc-openapi-starter-webmvc-ui` | 2.8.13 | **3.1.1** (compatible con Spring Boot 4) |
| `error-handling-spring-boot-starter` | 4.6.0 | **5.1.1** (compatible con Spring Boot 4) |
| `micrometer-registry-prometheus` | BOM | Sin cambios (gestionado por parent) |
| `lombok` | BOM | Requiere configurar `annotationProcessorPaths` en `maven-compiler-plugin` |
| `postgresql` | BOM | Sin cambios esperados |

---

## 3.1. Maven Wrapper — Maven 3.9.16

En `.mvn/wrapper/maven-wrapper.properties`:

```properties
wrapperVersion=3.3.4
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

Maven 3.6.3 dispara warnings de métodos restringidos/deprecados en Java 25. Con Maven 3.9.16 la ejecución es limpia y compatible.

---

## 4. Breaking changes en Spring Boot 4.x

### Jakarta EE 11

Spring Framework 7 (base de SB 4) requiere **Jakarta EE 11**. Los namespaces ya fueron migrados en SB 3 (`jakarta.*`), por lo que este punto no aplica si ya se migró de SB 2.

### Repositorio y Entidades JPA

- `EntityScan` se movió del paquete `org.springframework.boot.autoconfigure.domain` a `org.springframework.boot.persistence.autoconfigure.EntityScan`.

### Jackson 3

- Spring Boot 4 adopta Jackson 3 (`tools.jackson.*`).
- `Jackson2ObjectMapperBuilderCustomizer` fue reemplazado por `JsonMapperBuilderCustomizer` (`org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer`).
- Las features de serialización de fechas pasan a `tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS`.

### Lombok en Java 25

- Requiere registrar explícitamente `annotationProcessorPaths` en `maven-compiler-plugin` dentro del `pom.xml`.

### Security

- El bean `SecurityFilterChain` y las lambdas de configuración no cambian en forma significativa entre SB 3.5 y 4.x.
- Revisar deprecaciones en `HttpSecurity` si se usan métodos marcados como deprecated en SB 3.

### Actuator

- Endpoint `/actuator/prometheus` continúa disponible.
- Revisar si cambia el formato de alguna métrica.

### Properties renombradas

Ejecutar el migration assistant:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.application.admin.enabled=true"
```

O usar el **Spring Boot Migrator** para detectar propiedades renombradas automáticamente.

---

## 5. `keycloak-spi/pom.xml`

El módulo SPI es independiente — no hereda del parent de Spring Boot.
No requiere cambios de versión de Spring. Solo actualizar Java si se desea consistencia:

```xml
<properties>
    <java.version>25</java.version>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>
```

---

## 6. Verificación

```bash
# Compilar
./mvnw clean package -DskipTests

# Tests
./mvnw test

# Levantar el stack completo
docker compose -f docker/services.yaml up

# Verificar endpoint de salud
curl http://localhost:8080/actuator/health
```

---

## Referencias

- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)
- [springdoc-openapi compatibility matrix](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
