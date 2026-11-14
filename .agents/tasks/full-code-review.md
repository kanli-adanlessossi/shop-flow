# ShopFlow full code review

Read-only review. No source file was changed and nothing was committed. Paths are relative to the repo root; `OP` = `order-platform/src/main/java/com/shopflow/order`.

Totals: **2 Critical, 3 High, 7 Medium, 15 Low, 11 Info.**

Every Critical and High finding was re-checked against the code. All hold. Line corrections made during the check are listed in the last section.

## Verdict on the two known items

| Item | Verdict |
|---|---|
| `docs/user-stories.md` US-3 "a customer's list shows only their own orders" (marked open, line 50) | **Correctly open, and a real data exposure (H3).** Docs, code and the `@Disabled` test (`Us03ViewOrdersStoryTest.java:70-73`) agree. Fix and test plan are under H3. |
| `OrderService.replace` does not adjust the stock reservation | **Confirmed (M1).** `OrderService.java:116-129` leaves the reservation untouched, and the Javadoc at 117-119 says so. It is reachable by any owner and combines with C1. |

## Critical

**C1. Customers set their own prices.**
- `OP/api/OrderLineRequest.java:13` accepts `unitPrice` from the body. `OP/api/OrderRequest.java:24` copies it into the draft. `OP/domain/OrderService.java:251` stores it.
- `confirmPayment` compares the webhook amount with the total built from those stored prices (`OrderService.java:226-229`), so a 0.01 price is confirmed by a 0.01 payment.
- Fix: price lines server-side (catalogue or inventory), or accept client prices from ADMIN only.

**C2. An order owner can move their order to any status without paying.**
- `OP/api/OrderController.java:59` (`STATUS_RULE = ADMIN or (OWNER and NOT_A_CANCEL)`) with the handler at 132-136 only blocks non-admins from sending CANCELLED.
- `OrderService.updateStatus` (134-142) calls `Order.setStatus` (`OP/domain/Order.java:112`, a plain setter) with no transition check.
- Effects: `PATCH /api/orders/{id}/status {"status":"SHIPPED"}` skips the signed payment webhook, which `docs/user-stories.md:95-98` makes the only route to CONFIRMED. No `OrderConfirmed` event is written and no amount check runs. A CANCELLED order can be set back to PENDING_PAYMENT or CONFIRMED although its stock was released.
- Fix: make PATCH admin-only, enforce a transition table in the service (PENDING_PAYMENT to CANCELLED, CONFIRMED to SHIPPED), return 409 otherwise, and reach CONFIRMED only through `confirmPayment`. The scheduled cleanup cancels through the service, so keep its path working.

## High

**H1. Stock reservations leak on admin cancel and on delete.**
- `OrderService.updateStatus` (138-140) emits `OrderCancelled` and never releases stock. `OrderService.delete` (147-153) never releases and emits no event.
- Only `UnpaidOrderCleanup.java:94` and the failed-create path (`OrderService.java:76, 90`) release.
- Fix: release after commit in both paths (for example `ReservationRelease.releaseInBackground`; release is idempotent).

**H2. No concurrency control on `Order`; concurrent updates are lost.**
- `OP/domain/Order.java:26-53` has no `@Version`, and no query locks.
- `confirmPayment` (215-236), `cancelIfStillUnpaid` (181-190), `updateStatus`, `replace` and `delete` read then write.
- If the cleanup job and a webhook both read PENDING_PAYMENT, both commit and the last write wins, and both events are already in the outbox. The result can be a CANCELLED order with an `OrderConfirmed` event, or the reverse.
- Fix: add `@Version` (plus a Flyway migration) or a pessimistic lock, and map the lock failure to 409.

**H3. The order list exposes every customer's orders (US-3).**
- `OP/api/OrderController.java:98-109` (`listV1`, `listV2`) has no `@PreAuthorize` and no owner filter. `OrderService.search` (105-114) takes no caller. `OP/domain/OrderRepository.java:16-34`: both list queries and both count queries have no `customerId` condition. `OP/infra/SecurityConfig.java:47` only requires authentication.
- Any signed-in user sees all customers' ids, statuses, dates and totals.
- Fix: pass the `Authentication` into `search`. For non-admins add `where o.customerId = :customerId` to both list queries and both count queries. Then enable the disabled test.

## Medium

**M1. `OrderService.replace` does not adjust the reservation (known item).** `OrderService.java:116-129`. A customer can reserve 1 unit, PUT quantity 1000 of another SKU, and still pay and confirm. Stock stays held for SKUs no longer on the order. Fix: re-reserve before saving (needs an inventory call to replace a reservation), or reject `replace` when SKUs or quantities change.

**M2. The cleanup job releases stock before it cancels.** `OP/domain/UnpaidOrderCleanup.java:94-96`. A payment landing between the two steps leaves a CONFIRMED order with no reservation. The class Javadoc (lines 29-32) documents the overlap but not this window. Fix: cancel first, then release from a retry path, or add an intermediate state.

**M3. Cache eviction may run before commit.** `OP/domain/OrderService.java:120-121, 132-133, 145-146, 179-180, 213-214` put `@CacheEvict` and `@Transactional` on the same methods, and `OP/infra/CacheConfig.java:14` uses `@EnableCaching` with no `order`. If the evict runs inside the transaction, a concurrent `findById` (cached at line 95) can re-cache the old state. That value also feeds `OrderAccess.isOwner`. Not covered by a test that I could find. Fix: set the advice order so eviction runs after commit, or evict in an after-commit callback.

**M4. The outbox relay's ShedLock lease can expire mid-run.** `OP/events/OutboxRelay.java:70` (`lockAtMostFor = "2m"`). The loop (81-100) sends up to 50 rows, each waiting up to 5 s (line 83), so a slow-but-succeeding Kafka can exceed 2 minutes. A second instance then re-sends the same rows. The inbox absorbs duplicates, but ordering ("never out of order" in the class Javadoc) is lost. Fix: stop the loop after about 60 s by clock, or raise the lease above 250 s.

**M5. Weak committed credentials in the Keycloak realm and compose files.** `order-platform/keycloak/shopflow-realm.json` has `sslRequired: none` (line 5), `directAccessGrantsEnabled: true` (21), a client secret (32), user passwords `alice-pass`/`bob-pass` (52, 62), and broad `redirectUris`/`webOrigins` (22-23). `order-platform/compose.yaml` has Keycloak admin `admin`/`admin` and Postgres password `shopflow`. Acceptable for a laptop, but nothing marks it dev-only. Fix: label or relocate the files as dev-only and use environment-supplied secrets elsewhere.

**M6. The load test's default token fetch is broken.** `load-tests/place-orders.js:107-108` posts to a relative URL; `KEYCLOAK_URL` (line 39) is only used in the error message. A run without `ACCESS_TOKEN` fails, contrary to the documented default. `compare-virtual-threads.ps1` always passes a token, so the recorded results in `docs/NOTES.md` are unaffected. Fix: prefix the URL with `${KEYCLOAK_URL}`.

**M7. `inventory-service` is an unmarked skeleton that docs and config treat as real.** Its `application.properties` has only `spring.application.name`, with no controller and no port. `order-platform/src/main/resources/application.yaml:104` points at `http://localhost:8081`, and `order-platform/compose.yaml:24`, `docs/user-stories.md:35,87` and `docs/NOTES.md:247,261` describe stock as reserved "at inventory-service". Started as built, it listens on 8080 and exposes none of the stock endpoints the WireMock stubs in `deploy/wiremock/inventory/mappings` define. All tests run against WireMock, with no contract test. Fix: state it is a placeholder, and optionally set `server.port=8081`.

## Low

- **L1. One bad outbox row blocks all later events.** `OutboxRelay.java:89-93` returns on the first failure and the same row is picked first on every run. A permanently rejected record stalls the pipeline with only a WARN each second. Needs a metric or quarantine path, which is an ordering trade-off and a product decision.
- **L2. A dead letter can lose its coordinates.** `OP/events/DeadLetters.java:130-132` binds topic, partition and offset from headers. `V6__create_dead_letters.sql:7-9` declares them `not null`, so a record without the headers is not stored (error logged, counter tagged `unknown`). Optional fix: fall back to the record's own topic, partition and offset.
- **L3. The relay's send and mark are separate steps.** `OutboxRelay.java:83` and 95-98. A DB failure on the update propagates to the scheduler and the row is re-sent. This matches the documented at-least-once behaviour. Optional: catch and log clearly.
- **L4. `OrderAccess.isOwner` returns true for a missing order.** `OP/api/OrderAccess.java:35-43`. A missing id gives 404 and someone else's gives 403, so ids can be enumerated. `docs/user-stories.md:46-47` asks for exactly this, so it is an accepted trade-off. Change it only if order ids are sensitive.
- **L5. No startup validation of the webhook secret length.** `shopflow.payments.webhook-secret` in `application.yaml`. An empty secret fails closed with 503, but a short one is accepted. The signature check itself is sound (HMAC-SHA256, constant-time compare, 5-minute window).
- **L6. CORS defaults are dev origins.** `OP/infra/CorsConfig.java:34-36`, `application.yaml:~101` (`localhost:3000`, `localhost:5173`). Deployments must set `SHOPFLOW_CORS_ALLOWED_ORIGINS`.
- **L7. The CI comment overstates the compile checks.** `.github/workflows/ci.yml:1-3` says Error Prone and NullAway run on every module. Only `order-platform/pom.xml` configures them. Reword, or move the config into the parent `pluginManagement`.
- **L8. The two skeleton modules add CI cost and no coverage.** `inventory-service` and `shopflow-starter` each have a single `contextLoads()` test and an empty `services: {}` compose file. `shopflow-starter` has no documented purpose in `docs/`.
- **L9. The CI duration gate is tight.** `.github/workflows/ci.yml:20` says about 3 minutes locally. Lines 55-56 fail the job at 600 s and warn at 480 s. A cold hosted runner with five Testcontainers may pass 10 minutes and fail a green suite. Base the limits on a measured CI run.
- **L10. `load-tests/compare-virtual-threads.ps1:32` deletes `vt-*` files on every run,** including the committed `results/vt-*.json` that `docs/NOTES.md` cites as evidence. Line 76 also writes untracked `vt-*-app.log` and `.err` files there. Write to a timestamped folder and check `.gitignore`.
- **L11. Testcontainers tests skip silently without Docker.** All use `@Testcontainers(disabledWithoutDocker = true)` (`KeycloakSecurityIntegrationTest.java:44`, `StoryTestSupport.java:66`, `OrderRepositoryPostgresTest.java:19`, `KafkaTracePropagationTest.java:48`). With no Docker, CI stays green with the integration coverage gone. Add a CI step that fails on unexpected skips.
- **L12. An oversized event payload would roll back the order.** `OP/events/OutboxEvents.java:42-45` throws for payloads over 4000 characters. Today's events are small, so it is not reachable yet.
- **L13. The cancel reason is always `"cancelled on request"`.** `OrderService.java:139`, including an admin cancelling a CONFIRMED or SHIPPED order that has no refund or stock path (follows from C2).
- **L14. Schema and entity gaps.** `orders.status` has no check constraint, so C2 can write any enum value. `reservation_id` is `varchar(64)` in V3 but has no declared length on the entity. `ddl-auto: validate` still passes.
- **L15. Static-analysis and housekeeping warnings.** `WebhookSignature.java:50` uses `String.split(String)` on the signature header (StringSplitter). The Initializr placeholders `<license/>`, `<developer/>` and `<scm>` remain in three poms. `DeadLetters.java:92` (ReferenceEquality) was checked: it is an intentional cycle guard in a cause-chain loop, so no change is needed.

## Review sections

### Correctness and concurrency
C2, H1, H2, M1, M2, M3, M4, L13. The create flow is well built: reserve runs outside the transaction, the order and its `OrderPlaced` outbox row are saved together, and release runs in the background on failure (`OrderService.java:70-93`).

### Security
C1, C2, H3, M5, L4, L5, L6. Checked and fine: public endpoints are limited to `GET /actuator/health/**` and `POST /api/webhooks/payments` (`SecurityConfig.java:43-45`), `/api/admin/**` needs ADMIN, CSRF is off only for `/api/**` with stateless bearer auth, `CREATE_RULE` and `REPLACE_RULE` force `customerId` to the caller for non-admins, and the sort whitelist (`OrderService.java:102`) blocks sort injection. The CI workflow has `contents: read`, SHA-pinned actions, no secrets, and no `pull_request_target`.

### Event pipeline
M4, L1, L2, L3, L12. The design is sound: outbox insert is `Propagation.MANDATORY`, the relay stops at the first failure to preserve order, the inbox mark shares the transaction with the work, and dead-letter storage is deduplicated and never throws. The unconfirmed point is retry-topic partition counts (see I5).

### Persistence
H2, L14, M3. Flyway matches the entities. `discountCode` is stored but never applied (I1). The two purge jobs run unbounded single `DELETE` statements (I7), which is fine at current volumes.

### Error handling
C2 (no 409 for illegal transitions), L1, L2, L3, L12. Webhook errors are generic 401/503, a duplicate-key race returns 409 (`PaymentWebhookController.java:101-110`), and a late payment on a cancelled order raises `LatePaymentException`.

### Test quality and coverage gaps
- The only `@Disabled` test is US-3 (`Us03ViewOrdersStoryTest.java:70`). Stories run in default `verify`, so the gap shows in reports.
- No test asserts owner scoping of the list. `OrderControllerTest.java:256-270` only mocks `orders.search`, so a wrong H3 fix would not be caught. Add a cross-user test (bob's order absent from alice's list) next to the re-enabled story test.
- The reports I reviewed show no test for illegal status transitions (C2), for stock release on admin cancel or delete (H1), for concurrent confirm versus cancel (H2), or for client-supplied prices (C1). I did not search the test sources for each of these, so treat them as likely gaps to confirm.
- Inventory is only ever tested against WireMock (M7). L11 and L8 cover silent skips and placeholder tests.

### CI config
L7, L9, L11. The workflow triggers on `push` and `workflow_dispatch` only.

### Docs versus code
Consistent: the ownership table in `docs/NOTES.md` matches `OrderController.java:43-59`, US-3's text matches the sort whitelist and 400 behaviour, and the open US-3 criterion matches the code and the disabled test. Inconsistent: M7 (inventory-service described as real), L7 (CI comment), and ADR 0001 (`docs/adr/0001-...md:4`) is still "Draft", while `docs/NOTES.md` cites it. The comment at `OrderController.java:127-131` says owners "may move an order to another status", but no story asks for it (C2).

## Info
- **I1.** `discountCode` is stored (`Order.java:45`) but never applied in `total(...)` (`OrderService.java:238-242`).
- **I2.** `OrderEventHandler.java:36-48` rolls back on a send failure after the inbox insert. A successful send followed by a failed commit gives a duplicate notification, which is documented and uses the event id as an idempotency key.
- **I3.** Unknown event payloads are skipped and never recorded (`OrderEventHandler.java:37-42`). Intentional.
- **I4.** `ProcessedEvents.java:35-46` uses a `where not exists` guard on top of the primary key. A race loser fails and retries, as the Javadoc says.
- **I5.** Retry topics use 3 partitions (`OrderEventListener.java:56-62`, `KafkaTemplates.java:45-66`). The main topic's partition count is not set in code, so verify topic provisioning matches.
- **I6.** Inbox retention (14 days) exceeds the default 7-day topic retention (`application.yaml:96`, `ShopFlowProperties.java:26`). Add a deployment note if broker retention is ever raised above 14 days.
- **I7.** `ProcessedEventsPurge.java:37` and `OutboxRelay.java:108` are daily cron jobs with unbounded `DELETE`s.
- **I8.** WireMock ports are published on all interfaces (`8081:8080`, `8082:8080`) in `deploy/wiremock/compose.yaml`. Binding to `127.0.0.1` is a cheap hardening step. The `callback-verify.json` stub always returns `valid:true`.
- **I9.** `application-dev.yaml` holds `dev-only-webhook-secret`, scoped to the `dev` profile.
- **I10.** The ADR 0001 gaps (poison-row protection, lag metrics) are not built. Decide whether to accept the ADR or track them as work.
- **I11.** The load-test results show 2 runs per mode, and `docs/NOTES.md` already says that cannot separate signal from noise.

## Build and test state

Recorded from the build step, not rerun in this review.

- `./mvnw verify` from the repo root ran once, exited 0, printed `BUILD SUCCESS` after 3:19 min.
- Docker was available (server 29.9.3), so the Testcontainers tests ran: Postgres repository test, Keycloak security test, Kafka trace propagation test.
- Reactor: shop-flow (parent), order-platform, inventory-service and shopflow-starter all passed.

| Module | Run | Failed | Errored | Skipped |
|---|---|---|---|---|
| order-platform | 124 | 0 | 0 | 1 |
| inventory-service | 1 | 0 | 0 | 0 |
| shopflow-starter | 1 | 0 | 0 | 0 |
| **Total** | **126** | **0** | **0** | **1** |

- The one skipped test is `onlyOwnOrdersInTheList` in `Us03ViewOrdersStoryTest`. It comes from `@Disabled` (confirmed in this review at `Us03ViewOrdersStoryTest.java:70-73`).
- Warnings: two Maven repositories (flyway-community-db-support on GitHub Packages, red-gate MavenFeed) returned HTTP 401 on metadata fetches without failing the build. Error Prone warned in main code at `ShopFlowProperties.java:27,29,33`, `OrderService.java:220` (statement switch), `DeadLetters.java:92` (benign, see L15) and `WebhookSignature.java:50`. In test code it warned about `String.split` (`KeycloakSecurityIntegrationTest` lines 134 and 156, `StoryTestSupport:145`) and ignored Futures (`Us10NotifyCustomerStoryTest` lines 72-73). `shopflow-starter` tests print a JDK warning that Mockito self-attaches its agent; configure Mockito as an agent in the build.
- Green tests do not cover C1, C2, H1 or H2, since the suite passes with those behaviours present.

## Spot-check corrections

All Critical and High citations were checked against the code and hold. These line numbers were corrected:
- `STATUS_RULE` is at `OrderController.java:59`, not 56. The list handlers are at 98-109.
- The `OutboxRelay` lease is at line 70 and the send loop at 81-100, not the 62-63 and 75-98 cited in the event-pipeline input.
- `OrderService.search` spans 104-114.
- The security input rated `OrderAccess.isOwner` as Medium. The docs ask for that behaviour, so it is Low (L4).
- The quality input rated US-3 Medium. The core and security inputs rated it High, and I kept High because it is a data exposure.
