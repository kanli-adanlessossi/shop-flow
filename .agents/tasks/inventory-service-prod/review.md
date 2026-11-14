# Review: inventory-service production-style service and order-platform integration

Range reviewed: `7e74216..HEAD` of `feature/inventory_service_prod` (4 feature commits plus the root verify record). Verification evidence was taken from the FEAT-001..004 findings and commit messages; no suites were re-run.

**Verdict**: APPROVED

Every decision in `plan.md` and every acceptance criterion in FEAT-001..004 is met in the diff. The notes below are non-blocking.

## High-level view

Reservations are atomic and safe across instances. `StockService.reserve` runs in one transaction: `insert ... on conflict do nothing` on the reservation id, SKUs merged into a `TreeMap` (sorted lock order), then a conditional `update ... where available >= :q` per SKU. Any miss throws, which rolls the whole reservation back. No `synchronized` or lock object exists in main code (grep confirmed). `release` and the expiry job lock the reservation row first and then touch stock in SKU order, which matches the reserve lock order, so there is no lock cycle.

The lifecycle follows the plan. Release of SHIPPED returns 409, and HELD or CONFIRMED returns 204 and restores stock. Absent returns 404. `markConfirmed` and `markShipped` are conditional updates that only move forward. A duplicate event is stopped by the inbox row. An unknown reservation, a blank reservation id or an unknown type is skipped without throwing, so none of them reach the DLT. Expiry selects only HELD rows and re-checks status and age under the row lock (`releaseIfStillHeld`), so CONFIRMED never expires and a confirmation racing the job wins.

Security matches the plan. `/api/stock/**` requires `ROLE_INVENTORY`, and only health is open. The realm gets role `inventory` on the `shopflow-service` account. order-platform signs its calls through `OAuth2ClientHttpRequestInterceptor` with a client-credentials manager. There is no test bypass or production toggle, and tests get real tokens by pointing `token-uri` and the secret at the Keycloak container. The only secrets are dev-only values in `compose.yaml`, `application-dev.yaml` and the dev realm. The prod `client-secret` defaults to empty and comes from `SHOPFLOW_INVENTORY_CLIENT_SECRET`.

The event contract is additive. `OrderConfirmed.reservationId` is `@Nullable`. `OrderShipped(orderId, reservationId)` is written to the outbox only on a real CONFIRMED to SHIPPED change. order-platform's `OrderEventHandler` skips it before the inbox mark, and `LoggingNotificationSender` throws if it were ever reached.

Compose is a single root `compose.yaml`. Kafka (9092) and Keycloak (8180) are unchanged and have no profile. Postgres sits in profile `orders` and `postgres-inventory` (5433, named volume) in profile `inventory`. Both apps set `file: ../compose.yaml`, `lifecycle-management: start-only` and their own profile, so both can run at once and neither stops the other's infrastructure. FEAT-003 ran the inventory app against the Boot-started stack and showed the profile approach works. Running both apps at once is not shown in the findings. It is assigned to the later end-to-end step.

CI budget is met. inventory-service has one shared Keycloak/Postgres/Kafka context (`InventoryIntegrationTest` has no extra properties or mocks), plus a `@DataJpaTest` slice and a context-free WireMock test. Root `verify` took 3m09s locally against the 10 min target, with 0 failures and 0 skips. Docs are updated: both READMEs, `docs/NOTES.md`, user stories, the wiremock README, the keycloak README, and the `ci.yml` comments.

<details>
<summary>Issues (3, none blocking)</summary>

1. **Indentation lost in three files** (confirmed). `OrderEventHandler.java` and `HeldReservationExpiry.java` in inventory-service have no tab indentation, and the last method of `ReservationRepository.java` is unindented. Reformat to the project's tab style.
2. **Token cache not asserted** (confirmed). The "token cached between calls" criterion of FEAT-002 relies on the manager's standard behaviour. The coder disclosed this. Optionally add a Keycloak request counter test.
3. **Events for a reservation that does not exist yet are dropped** (likely). A `confirm` or `ship` event for an unknown reservation is logged and marked processed, as specified in `plan.md`. If the event beats the reserve call or arrives after expiry, that transition is lost. Accepted by the plan, so only a documentation note.

</details>

<details>
<summary>Details</summary>

### Reservation transaction and lock order

`reserve` inserts the reservation row first. A concurrent request with the same id blocks on the unique index until the first transaction ends, then gets 0 rows and returns 200 without further change. If the first one rolled back, the second proceeds normally. Stock decrements are single conditional `UPDATE` statements, so Postgres row locks do the serialisation and `available >= 0` cannot be violated. Unknown SKUs match no row and end up as 409. The concurrency test in `StockRepositoryPostgresTest` covers exact success counts, crossing SKU orders and the same id from many threads, per the FEAT-003 findings.

### Event handling and expiry

The handler writes the inbox row and moves the status in one transaction, so a failure rolls back both and the retry reprocesses the event. The only way into the DLT is a genuine processing failure. FEAT-004 shows this with a NUL character in the reservation id. The expiry cron uses UTC and ShedLock `expireHeldReservations` with `lockAtMostFor` 10m. The 45m TTL is above order-platform's 30m cancel-after.

### Security posture

Resource server settings mirror order-platform's (issuer from `SHOPFLOW_ISSUER_URI`, realm roles upper-cased with a `ROLE_` prefix). Tests cover 401 without a token, 403 for `alice`, and 200 for the service account. CSRF is ignored only for `/api/**`, which is stateless and header-authenticated.

### Test coverage

inventory-service: 57 tests (controller slice, Postgres concurrency, security, event consumer, expiry, contract against WireMock stubs and the real service). order-platform: 168 tests, including the Bearer header at the stand-in, `reservationId` on `order.confirmed`, a single `order.shipped` per shipment, and the handler skipping it. Not tested: token cache reuse (see Issues), and both apps running together against one compose project (planned for the end-to-end step).

</details>

## File map

<details>
<summary>Files</summary>

Root: `compose.yaml` (moved from order-platform), `.github/workflows/ci.yml` (comments), `docs/`, `deploy/wiremock/README.md`, `load-tests/compare-virtual-threads.ps1`. order-platform: `OrderConfirmed`, `OrderShipped`, `Payload`, `OrderService`, `OrderEventHandler`, `LoggingNotificationSender`, `InventoryClientConfig`, `InventoryStockReservations`, the `pom.xml` and `application*.yaml`, realm JSON, and tests. inventory-service: new `domain`, `api`, `events` and `infra` packages, Flyway V1 to V5, dev seed, `application*.yaml`, `pom.xml`, README and tests. Full diff: `git diff 7e74216..HEAD` in the worktree.

</details>
