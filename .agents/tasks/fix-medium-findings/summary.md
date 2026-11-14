# Summary: fix M1 to M7

Nothing is committed or pushed. The work sits in the main checkout on top of the uncommitted C1 to H3
fixes, and those fixes are untouched. New files are untracked, so check with `git status` and `git diff`.

## Design decisions per finding

### M1: `replace` now moves the stock reservation (`OrderService.replace`)
- **Decision: reserve the new lines first, then release the old reservation after the commit.** When the
  quantity per SKU changes, the new lines are reserved under a fresh UUID, outside any transaction (as in
  `create`). One transaction then swaps `orders.reservation_id` to the new key and registers
  `releaseAfterCommit(old key)`. The old key is released only once that commit succeeds.
- If the reservation fails, the order and its old reservation stay as they were: 409 when stock is short,
  503 when inventory is down. On 503 the new key is released in the background. If the transaction fails
  after the reservation succeeded (for example the order was paid in the meantime, 409), the new key is
  released.
- Changing only the customer or discount code, or the same SKUs and quantities in a different line order,
  does not call inventory. The check and the write happen in one transaction.
- If a second replace moves the reservation while the first is still reserving, the first gets
  `ObjectOptimisticLockingFailureException`. The H2 handler already maps that to 409, and the new key is
  released.
- **Why:** with reserve-then-release the order is never under-reserved, and nothing changes unless the new
  lines can be served. That keeps the user-visible PUT endpoint working.
- **Alternatives rejected:**
  - Rejecting changed lines would remove a documented feature (US-4).
  - Releasing first, then reserving, opens a window with no stock held.
  - A new inventory "replace reservation" call does not exist in the WireMock contract.
- **Cost:** for a moment the new lines need stock next to the old ones. Near an empty shelf, a replace can
  be refused although the net change would fit. This is documented.
- **Entity change:** `Order.reservationId` lost `updatable = false` and gained a setter. The schema is
  unchanged.
- `replace` lost its own `@Transactional`. It runs its transactions through `TransactionOperations`, so the
  inventory call holds no database connection.

### M2: the cleanup job cancels first, then releases (`UnpaidOrderCleanup`, `OrderService`, V10)
- **Decision: cancel first, plus a "stock release pending" flag.** This is the review's intermediate-state
  option, done as a flag instead of a new status.
- `cancelIfStillUnpaid` does all of these in one transaction: re-check the status and age, set CANCELLED,
  set `stock_release_pending = true`, and write `OrderCancelled("unpaid")`. It now returns the reservation
  key (it used to return the customer id, which had no other caller).
- The job then releases the stock and calls `stockReleased(id)`. That is a bulk `update` that clears the
  flag without bumping `@Version`, so it never makes a concurrent request fail with 409.
- Every run first retries flagged orders (`findStockReleasePending`, batch 200), then cancels new unpaid
  orders. If inventory is down, the run stops and the flag stays set.
- **Why:** a payment that arrives after the cancel commits now meets a CANCELLED order and becomes the
  existing `LatePaymentException` (409, refund needed). The release-then-cancel window, where an order
  could be CONFIRMED without stock, is gone. The flag keeps the release retryable. Without it, a failed
  release after the cancel would be lost. A new status would have touched the transition table, the API
  enum and the docs.
- **Behaviour change, noticeable:** when inventory is down, an unpaid order is now cancelled (and the
  customer told) before its stock is back. Before, it stayed PENDING_PAYMENT until inventory answered.
  US-7 is updated.
- Only the cleanup job sets the flag. Releases after an admin cancel or a delete stay best effort, as in
  the H1 fix (out of scope).
- The old `unpaidReservationId` method is removed (its only caller was the old job code).
- **Schema:** `V10__order_stock_release_pending.sql` adds `stock_release_pending boolean not null default
  false`.

### M3: cache eviction after commit (`CacheConfig`)
- **Decision:** `@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 100)`. The cache advice then wraps the
  transaction advice, which keeps Spring Boot's default `LOWEST_PRECEDENCE`. So `@CacheEvict` on a
  `@Transactional` method runs after the commit, or after the rollback.
- **Why:** this is one line, it covers all six `@CacheEvict` methods at once, and it is the standard
  approach. An after-commit callback in each method would repeat code.
- **Measured:** with the old config, the test recorded `begin, evict 1, commit`, so the eviction really did
  run inside the transaction.
- `replace` runs its transactions inside the method body, so its eviction also follows them.
- **What is left:** a reader that loads the old state between the commit and the eviction can serve it for
  that short moment. A stale entry can no longer outlive the eviction.

### M4: bounded outbox relay run (`OutboxRelay`)
- **Decision:** `MAX_RUN = 60 s`, measured by the injected `Clock`. Before each send the loop checks the
  deadline. Once the deadline has passed, it logs a WARN and returns. The rest of the batch goes on the
  next run (1 s later), still in id order.
- With the 5 s send timeout, a run lasts at most about 65 s, well inside the unchanged 2-minute
  `lockAtMostFor`.
- **Why:** this bounds the run however Kafka behaves. Raising the lease to 250 s or more would also
  lengthen the takeover delay after a crash, and it would break again if the batch size or timeout
  changed.

### M5: dev-only credentials marked
- **Decision: label the files, do not move or remove them.** Compose and the Keycloak test container
  import the same realm file. Moving it would touch test infrastructure and gain nothing.
- `shopflow-realm.json`: `displayName` is now "ShopFlow (DEV ONLY - insecure test realm, see
  keycloak/README.md)". No test reads the display name.
- New `order-platform/keycloak/README.md`: a table of each unsafe setting and why it is unsafe, plus what
  a real realm must do instead (HTTPS, no password grant, exact redirect URIs, secrets from the
  environment or a vault).
- `order-platform/compose.yaml`: a DEVELOPMENT ONLY header that lists the committed credentials.
- `docs/NOTES.md` Security, "Who exists": the dev-only statement is now explicit and covers compose too.

### M6: load-test token URL
- `load-tests/place-orders.js`: the token POST now goes to
  `${KEYCLOAK_URL}/realms/shopflow/protocol/openid-connect/token`.
- Not run: k6 needs the running stack. This is a one-line change, checked by reading.

### M7: inventory-service marked as a placeholder
- **Decision:** state that it is a placeholder, and set `server.port=8081`. Both are the review's own
  suggestions.
- New `inventory-service/README.md` says:
  - there are no stock endpoints;
  - the WireMock stubs in `deploy/wiremock/inventory` are the contract;
  - run the stand-in locally;
  - the module cannot run alongside the stand-in, because both use 8081.
- `application.properties`: a PLACEHOLDER comment and `server.port=8081`.
- `application.yaml` (`shopflow.inventory.base-url`) and the `order-platform/compose.yaml` port comment
  now point at the stand-in.
- `docs/user-stories.md` US-2 and `docs/NOTES.md` say that "inventory-service" in the docs means the
  stubbed contract.
- Not done: implementing the service or a contract test. Both are beyond "mark it".

## Tests added
| Finding | Test | What it shows |
|---|---|---|
| M1 | `Us04ChangeUnpaidOrderStoryTest.replaceMovesTheReservation` | new key reserved once, old key released once, new key not released |
| M1 | `Us04...replaceWithSameLinesKeepsTheReservation` | same SKUs and quantities in another line order: key unchanged, no release |
| M1 | `Us04...replaceRefusedForStock` | inventory answers 409, so the replace is 409 and the order keeps its lines and key |
| M2 | `UnpaidOrderCleanupOrderingTest` (plain Mockito, no Spring) | the cancel comes before the release, which comes before `stockReleased`; a paid order is not released; a failed release is retried first by the next run |
| M3 | `infra/CacheEvictionAfterCommitTest` (small `@SpringJUnitConfig`, real `CacheConfig`, recording transaction manager and cache, no database) | events are exactly `begin, commit, evict`; a rollback evicts nothing |
| M4 | `events/OutboxRelayRunLimitTest` (embedded H2 `JdbcClient`, mocked `KafkaTemplate`, a clock that each send moves forward 20 s, no Spring) | 3 of 10 rows go out in one run; the next run continues in id order (6 total, max id 6) |

The M3 and M4 tests are deterministic: they need no real time and no real threads. The M2 race window
itself (a payment between the two steps) is a few milliseconds wide and cannot be hit reliably end to
end. The test checks the order of the calls instead, which is what closes the window. No new Spring Boot
contexts or containers were added. The new story tests use the shared `StoryTestSupport` context, and
`CacheEvictionAfterCommitTest` is a plain Spring context with no Boot features.

## Existing tests changed
- `Us07CancelUnpaidOrdersStoryTest.inventoryDownThenBack`: needed for M2. It used to expect
  PENDING_PAYMENT after a failed release. It now expects CANCELLED with `stock_release_pending = true`
  after the first run, and one release with the flag cleared after the second. It got a small
  `releasePending` helper. Display name: "inventory down: the order is cancelled anyway, and the next run
  completes the release".
- No other existing test was changed.

## Docs updated
- `docs/user-stories.md`:
  - US-2: inventory-service is a placeholder, and the WireMock stubs are the contract.
  - US-4: the reservation follows replaced lines (409/503 behaviour, the empty-shelf caveat, no inventory
    call when nothing changes).
  - US-7: cancel first, then release; a late payment is refused; with inventory down the order is
    cancelled and the release retried.
- `docs/NOTES.md`:
  - Security, "Who exists": the dev-only credentials statement.
  - "Order integrity": new bullets for M1, M2, M3, M4 and M7.
- New: `order-platform/keycloak/README.md`, `inventory-service/README.md`.
- Comments: `order-platform/compose.yaml`, `application.yaml`, inventory-service
  `application.properties`.
- Javadoc: `OrderService.replace`, `cancelIfStillUnpaid`, `UnpaidOrderCleanup` (class), `OutboxRelay`
  (class and `MAX_RUN`), `CacheConfig`, `Order.reservationId` and `stockReleasePending`.

## Files (this step only)
- **Main code:** `Order`, `OrderRepository` (`findIdsWithStockReleasePending`,
  `clearStockReleasePending`), `OrderService`, `UnpaidOrderCleanup`, `OutboxRelay`, `CacheConfig`.
- **Migration:** `V10__order_stock_release_pending.sql`.
- **Resources:** `application.yaml` (comment only).
- **Other modules and files:** `inventory-service/.../application.properties`, `load-tests/place-orders.js`,
  `order-platform/compose.yaml`, `order-platform/keycloak/shopflow-realm.json`.
- **New tests:** the three test classes in the table above.
- **Changed tests:** `Us04` (3 tests added), `Us07` (1 test changed).

## Commands run, with results
Docker was available (server 29.9.3), so the Testcontainers tests ran.
1. `./mvnw -q -pl order-platform test -Dtest=CacheEvictionAfterCommitTest,OutboxRelayRunLimitTest,UnpaidOrderCleanupOrderingTest,Us04ChangeUnpaidOrderStoryTest,Us07CancelUnpaidOrdersStoryTest`.
   The first attempt failed to compile (NullAway: a lambda in `replace` returned null). I fixed it by
   returning `Optional`. On the second attempt all passed: 3 + 2 + 2 + 10 + 4 tests.
2. **Red check on the old behaviour.** No git was used: I backed up the four fixed files and temporarily
   put back each old behaviour:
   - M1: the reservation is never moved;
   - M2: the old `unpaidReservationId`, release, then cancel;
   - M3: plain `@EnableCaching`;
   - M4: no deadline check.

   I then ran the same command. 7 tests failed, every one I expected:
   - `UnpaidOrderCleanupOrderingTest.theOrderIsCancelledBeforeItsStockIsReleased` (M2);
   - `Us07...inventoryDownThenBack` (M2);
   - both `OutboxRelayRunLimitTest` tests (M4);
   - `CacheEvictionAfterCommitTest.theEntryIsEvictedAfterTheTransactionCommitted` (M3: actual
     `[begin, evict 1, commit]`);
   - `Us04...replaceMovesTheReservation` (M1);
   - `Us04...replaceRefusedForStock` (M1: 200 instead of 409).

   The other new tests passed against the old behaviour as well. They pin behaviour that must hold
   either way: `replaceWithSameLinesKeepsTheReservation`, `anOrderThatWasPaidInTheMeantimeIsNotReleased`,
   `aFailedReleaseIsRetried...` under the simulated old code, and `aRolledBackChangeEvictsNothing`.
   Against the real pre-change code, `UnpaidOrderCleanupOrderingTest` would not even compile, because it
   uses the new service methods. I restored the backups afterwards.
3. `./mvnw verify` from the repo root: **BUILD FAILURE**, with the same 7 failures. `Copy-Item` had kept
   the backups' old timestamps, so Maven's incremental compile kept the classes built from the broken
   sources. The sources themselves were correct (checked with a search for the break markers). I updated
   the timestamps of the four files.
4. `./mvnw clean verify` from the repo root: **BUILD SUCCESS**, 2:42 min.
   - order-platform: 152 run, 0 failed, 0 errors, 0 skipped (it was 142 after C1 to H3).
   - inventory-service: 1 run, passed, including `contextLoads` with `server.port=8081`.
   - shopflow-starter: 1 run, passed.
   - That is well inside the 10-minute CI target.

## Incident during the work (fixed)
A PowerShell helper I named `R` turned out to be a built-in alias (`Invoke-History`), and that call
overwrote `docs/NOTES.md` with only my new section. I restored the file from the editor's local history.
The latest entry was byte-identical in size (36706) to the file before my edits, and it contains the
C1 to H3 doc changes. I then applied my edits with the editor's replace tool. `git diff docs/NOTES.md`
shows the C1 to H3 changes plus mine. Please glance at it during review.

## Not done on purpose
- No Low or Info findings were touched.
- No release-pending flag for admin cancel or delete.
- No new inventory API.
- No contract test for inventory-service.
- `load-tests/place-orders.js` still sends `unitPrice` (ignored since C1).
