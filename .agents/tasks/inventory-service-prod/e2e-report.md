# inventory-service-prod: end-to-end report

Worktree `.worktrees/inventory-service-prod`, branch `feature/inventory_service_prod`. Dev credentials only (the committed
well-known values of `compose.yaml` and the dev realm); nothing secret is recorded here. Logs are in `logs/`.

## Commands

```powershell
# 1. order-platform first (Spring Boot Docker Compose starts postgres, kafka, keycloak from the root compose.yaml)
./mvnw -B -ntp -pl order-platform spring-boot:run "-Dspring-boot.run.profiles=dev"      # :8080
# 2. inventory-service while order-platform runs
./mvnw -B -ntp -pl inventory-service spring-boot:run "-Dspring-boot.run.profiles=dev"   # :8081
# 3. checks (Windows PowerShell 5.1), output in logs/check-e2e.out
powershell -NoProfile -File .agents/tasks/inventory-service-prod/check-e2e.ps1
# 4. cleanup
docker compose --profile orders --profile inventory down ; stop java
```

## Finding and fix

First start of inventory-service failed: `Failed to configure a DataSource`. Spring Boot Docker Compose logged
"There are already Docker Compose services running, skipping startup" because order-platform had started Kafka and
Keycloak, so `postgres-inventory` was never started. Fix in `inventory-service/src/main/resources/application.yaml`:
`spring.docker.compose.start.skip: never`. With it, `up` starts only the missing `postgres-inventory`; Kafka and
Keycloak kept running untouched (still "Up 2 minutes" when the new database became healthy) and `lifecycle-management:
start-only` keeps inventory-service from stopping anything.

## Observed results (check-e2e.ps1, second run)

| Step | Observed |
|---|---|
| alice places order 3 x SKU-001 | `POST /api/orders` 201; stock 997 -> 994 |
| order cancelled | `PATCH /api/orders/3/status CANCELLED` as bob (admin) 200; stock back to 997 |
| order paid via `send-payment-webhook.ps1` (dev secret) | order 4 (2 x SKU-002, 21.98): webhook 200 `CONFIRMED`; stock 998 -> 996; reservation HELD -> CONFIRMED |
| PATCH SHIPPED (bob) | 200; reservation SHIPPED; stock stays 996 |
| release of the SHIPPED reservation | `DELETE /api/stock/reservations/{id}` 409 "Reservation already shipped"; stock still 996 |
| insufficient stock | `POST /api/orders` 100000 x SKU-003 -> 409 "Insufficient stock"; SKU-003 stays 1000 |
| no token | `GET /api/stock/SKU-001` 401; `POST /api/stock/reservations` 401 |

Notes:
- A first run used alice for the cancel PATCH and got 403: `PATCH .../status` is admin-only in order-platform (existing
  rule, unchanged). The script now cancels as bob. The first run's order 1 (3 x SKU-001) stayed PENDING_PAYMENT, hence
  the stock of 997 at the start of the second run.
- Reservation status was read from the inventory database with `docker exec ... psql` (no read endpoint exists).
- Both apps ran at the same time against the one compose project; no infrastructure was stopped by either.

## Cleanup

All java processes stopped, `docker compose --profile orders --profile inventory down` removed the four containers
and the network; `docker ps -a` empty afterwards.

## Build

./mvnw -B -ntp verify at the worktree root after the yaml change: BUILD SUCCESS (order-platform, inventory-service, shopflow-starter; log logs/verify.log).

