# Observability Stack (LGTM + Prometheus + RabbitMQ)

**Locator:** `springboot-sso/odd/tasks/observability-stack.md`

## Objective

Provide an optional observability stack (Prometheus, Grafana, Loki, Promtail, Tempo) for centralized logging, metrics, RabbitMQ dashboard, and distributed tracing without penalizing developers who don't need it.

## Problem

- No centralized container log inspection (developers have to run `docker logs` per container).
- No unified dashboard for service metrics or RabbitMQ queue/connection states.
- Tracing is not yet collected or visualized in a distributed fashion.
- The stack must NOT consume host resources (CPU/RAM) unless explicitly requested.

## Why

Observability is essential for diagnosing distributed microservice interactions, queue latency, and JVM performance, but full stacks are resource-intensive. Making it modular and opt-in via Docker Compose Profiles guarantees zero runtime overhead by default.

## Scope

- Independent Compose file `docker/observability.yaml` leaving `docker/services.yaml` clean.
- Prometheus scraping Actuator endpoints (`/metrics/prometheus`) and RabbitMQ metrics.
- RabbitMQ `rabbitmq_prometheus` plugin enabled on port 15692.
- Loki + Promtail collecting Docker container logs automatically via Docker daemon socket.
- Tempo receiving OTLP (gRPC 4317, HTTP 4318) and Zipkin (9411) traces.
- Grafana with provisioned datasources (cross-linking Trace ID -> Loki logs <-> Tempo traces <-> Prometheus metrics) and pre-packaged dashboards for RabbitMQ and Spring Boot JVM.

## Constraints

- Default `docker compose -f docker/services.yaml up` must remain unaffected and start 0 observability containers.
- Actuator endpoints remain secured/exposed as currently configured in Spring Boot.

## Tasks

- [x] T1: Create `docker/enabled_plugins` enabling `rabbitmq_prometheus` and update `docker/rabbit.yaml`.
- [x] T2: Create Prometheus configuration (`docker/observability/prometheus/prometheus.yml`).
- [x] T3: Create Loki configuration (`docker/observability/loki/loki-config.yml`) and Promtail configuration (`docker/observability/promtail/promtail-config.yml`).
- [x] T4: Create Tempo configuration (`docker/observability/tempo/tempo.yaml`).
- [x] T5: Provision Grafana datasources with Trace-to-Log navigation and dashboards for RabbitMQ and Spring Boot (`docker/observability/grafana/...`).
- [x] T6: Define modular `docker/observability.yaml` and integrate into `docker/services.yaml` under profile `observability`.
- [x] T7: Configure Spring Boot tracing dependencies (`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`) in `catalog-service`, `membership-service`, and `apigateway`.

## Acceptance Criteria

- `docker compose -f docker/services.yaml config` outputs only core services.
- `docker compose -f docker/services.yaml --profile observability config` outputs core + observability services.
- Grafana provisioned with Prometheus, Loki, and Tempo.
