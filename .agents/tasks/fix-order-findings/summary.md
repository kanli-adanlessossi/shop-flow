# Summary: fix C1, C2, H1, H2, H3

Nothing is committed or pushed (as instructed). All changes are in the working tree.

## Design decisions

- **C1, prices (table `sku_prices`, V9).** The repo has no price source, so the order-platform database holds a price list. `unitPrice` is removed from `OrderLineRequest` and `OrderDraft.Line` for everyone, admins included; a body that still carries it is ignored (checked by a test). `OrderService.toLines` prices every line from the table and copies the price into `order_lines.unit_price`, so `confirmPayment` still compares against the stored total. Unknown SKU is `UnknownSkuException`, 422, thrown before stock is reserved (the test makes inventory answer 503 and still expects 422). `replace` re-prices too. Rejected: inventory-supplied prices (no such contract exists) and admin-only client prices (customers still need a source).
- **C2, transitions.** PATCH status is `ADMIN` only. `updateStatus` allows `PENDING_PAYMENT -> CANCELLED` and `CONFIRMED -> SHIPPED`; same status is a 200 no-op (keeps "cancel twice answers 200, one event"); everything else is `IllegalStatusTransitionException`, 409. `CONFIRMED` is reachable only through `confirmPayment`. `cancelIfStillUnpaid` is untouched, so the scheduled cleanup works as before.
- **H1, release.** `releaseAfterCommit` registers an `afterCommit` synchronization that calls `ReservationRelease.releaseInBackground`. Used in `updateStatus` (real change to CANCELLED) and `delete` (PENDING_PAYMENT and CANCELLED; release is idempotent, so a delete after a cancel releases a second time and heals earlier leaks). A refused delete releases nothing.
- **H2, locking.** Optimistic: `@Version long version` on `Order` plus `V8`. `ObjectOptimisticLockingFailureException` maps to 409 in `ApiExceptionHandler`. A losing webhook rolls back its inbox row and outbox event, so the provider's retry is handled fresh. No retry loop. The M2 window is not touched.
- **H3, list scope.** `OrderService.search(customerId, status, pageable)`; `OrderController.callerScope` passes `null` for `ROLE_ADMIN`, else `authentication.getName()`. Four repository queries (each with its own `where`, count queries included) instead of `:customerId is null or ...`, which PostgreSQL cannot type for an untyped null.

## Behavior changes users will notice

1. `unitPrice` in request bodies is ignored; unknown SKUs are 422. **A deployment whose `sku_prices` table is empty rejects every order.** Prices must be inserted by SQL; there is no admin endpoint. The `dev` profile seeds SKU-001..SKU-008 (the load-test SKUs, prices 5.99 to 40.99 made up by me).
2. Owners can no longer PATCH status at all (was: any status except CANCELLED).
3. Admin `CONFIRMED -> CANCELLED`, and any other move outside the two allowed ones, is 409. A paid order cannot be cancelled (no refund path exists, review L13).
4. A concurrent change answers 409.
5. A customer's `GET /api/orders` lists and counts only their own orders.

## Files

Main: `OrderController`, `OrderService`, `OrderRepository`, `Order`, `OrderDraft`, `OrderRequest`, `OrderLineRequest`, `ApiExceptionHandler`; new `IllegalStatusTransitionException`, `UnknownSkuException`, `SkuPrice`, `SkuPriceRepository`; `V8__order_version.sql`, `V9__create_sku_prices.sql`; `application-dev.yaml` (adds `spring.flyway.locations`), `db/dev/afterMigrate__dev_sku_prices.sql`. Docs: `docs/user-stories.md`, `docs/NOTES.md` (US-2/3/4/8/9 updated, "open" US-3 criterion removed, ownership table and rules updated, new section "Order integrity").

## New tests (one or more per finding)

- H3: `Us03ViewOrdersStoryTest.onlyOwnOrdersInTheList` (re-enabled, extended: bob's order absent from alice's list and count, with and without status, admin sees both); `OrderRepositoryQueries` (two tests, run on H2 and PostgreSQL); `OrderControllerTest` (customer scope in v1 and v2, admin unscoped).
- C2: `Us09CancelOrderStoryTest` `illegalTransitions`, `confirmedOrders`, `ownerCannotSetStatus`; `OrderControllerTest` `aCustomerCannotChangeTheStatusOfTheirOwnOrderAtAll`, `anIllegalStatusChangeIs409`.
- H1: `Us09...cancelReleasesTheReservation`; `Us04...deleteWhileUnpaid`, `deleteWhenCancelled`, `refusedDeleteKeepsTheReservation`.
- H2: `OrderConcurrencyStoryTest.staleWriteIsRejected` (deterministic: load, cancel in another thread, write); `OrderControllerTest.aConcurrentChangeIs409`.
- C1: `Us02PlaceOrderStoryTest.aClientPriceIsIgnored`, `unknownSku`; `Us04...replaceIsRepriced`; `OrderControllerTest.anUnknownSkuIs422`.

No new Spring contexts or containers: new story classes extend `StoryTestSupport`.

## Existing tests changed, and why

- `StoryTestSupport.orderJson` and `placeAlicesOrder`: line format `SKU:qty:price` becomes `SKU:qty` (prices are server-side). All callers in `Us02`, `Us03`, `Us04`, `Us10`, `Us11` updated.
- `Us10NotifyCustomerStoryTest`: the poison order used `A:1:66.66`; it now orders SKU `POISON`, seeded at 66.66.
- `Us02PlaceOrderStoryTest.invalidInput`: no longer expects a `lines[0].unitPrice` field error (the field is gone).
- `Us03ViewOrdersStoryTest.onlyOwnOrdersInTheList`: `@Disabled` removed, test extended.
- `Us04ChangeUnpaidOrderStoryTest.deleteWhileUnpaid`, `deleteWhenCancelled`: now also assert the stock release (H1).
- `OrderControllerTest`: `NEW_ORDER` and the invalid-input body lose `unitPrice`; `listReturnsAPageOfSummaries` uses the new `search(customerId, status, pageable)` signature; `aCustomerMayChangeTheStatusOfTheirOwnOrderToSomethingElse` (expected 200) is replaced by the 403 test above.
- `OrderApiIntegrationTest.NEW_ORDER`, `KeycloakSecurityIntegrationTest.ALICES_ORDER`: `unitPrice` removed.
- New test resource `order-platform/src/test/resources/db/migration/afterMigrate__test_sku_prices.sql` seeds A 10.50, B 3.25, C 0.50, POISON 66.66 (the prices the old tests sent, so all totals are unchanged).

## What I ran

Docker was available (server 29.9.3), so Testcontainers tests ran.

1. `./mvnw -pl order-platform test` after the changes: 142 run, 0 failed, 0 skipped (baseline in the review: 124 run, 1 skipped).
2. Red-on-old-code check (not via git): I temporarily broke four behaviors (removed `@Version`, made the transition check `if (false)`, made the release a no-op, made `callerScope` always `null`) and ran `OrderConcurrencyStoryTest, Us03, Us09, Us04`: 20 run, 4 failures and 3 errors, namely `staleWriteIsRejected`, `onlyOwnOrdersInTheList`, `illegalTransitions`, `confirmedOrders`, `cancelReleasesTheReservation`, `deleteWhileUnpaid`, `deleteWhenCancelled`. Then I reverted all four breaks. `ownerCannotSetStatus` (403) and the C1 tests were not run against old code: the old controller allowed the owner's PATCH, and the old DTO took the client price, so they cannot pass there; this is by reading, not by a run.
3. `./mvnw verify` from the repo root after reverting: BUILD SUCCESS, 2:26 min. order-platform 142 run, 0 failed, 0 skipped; inventory-service 1; shopflow-starter 1. The baseline was 3:19 min.

Not done on purpose: M1-M7, L*, I* findings; no admin endpoint for prices; `load-tests/place-orders.js` unchanged (it still sends `unitPrice`, now ignored).

## Note for the reviewer (uncommitted changes)

Review the working tree with `git status` and `git diff` (new files are untracked). Results above are from the final tree. Files in the tree may show LF/CRLF warnings from git (`autocrlf`); the new files are LF.
