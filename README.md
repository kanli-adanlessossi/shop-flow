# shop-flow

An order-processing backend built with Spring Boot 4.1 and Java 25. Orders are placed through a REST API, stock is reserved in a separate service, and order events flow through Kafka.

## Modules

| Module | Purpose |
|---|---|
| `order-platform` | Orders API, payment webhook, event publishing (Kafka, outbox), OAuth2 resource server. |
| `inventory-service` | Stock and reservations (port 8081). Consumes order events. See [its README](inventory-service/README.md). |
| `shopflow-starter` | Placeholder skeleton kept in the build. See [its README](shopflow-starter/README.md). |

Supporting directories:

- `docs/`: [user stories](docs/user-stories.md), [technical notes](docs/NOTES.md), [ADRs](docs/adr).
- `load-tests/`: k6 script (`place-orders.js`) and a virtual-thread comparison script.
- `deploy/wiremock/`: stub for the external payment provider.
- `order-platform/http/`: `.http` requests and a payment webhook script.

## Stack

PostgreSQL (Flyway, JPA), Kafka, Keycloak (realm `shopflow`), Testcontainers, GitHub Actions.

## Prerequisites

- JDK 25
- Docker (compose and Testcontainers)

The Maven wrapper in the repository root builds every module; no separate Maven install is needed.

## Build and test

```powershell
./mvnw verify                      # all modules
./mvnw verify -pl order-platform   # one module
```

Tests use Testcontainers (PostgreSQL, Kafka, Keycloak), so Docker must be running.

## Run locally

Start the shared infrastructure from the root `compose.yaml`:

```powershell
docker compose up -d
```

Then start a service, for example:

```powershell
./mvnw spring-boot:run -pl order-platform
./mvnw spring-boot:run -pl inventory-service
```

To run `order-platform` on Testcontainers without compose, use `./mvnw spring-boot:test-run -pl order-platform`.

## Authentication

Both services are OAuth2 resource servers on the same Keycloak realm. API calls need a `Authorization: Bearer <token>` header. Example requests are in `order-platform/http/shopflow-api.http`. The payment webhook is public and authenticated by its signature.

## License

See [LICENSE](LICENSE).
