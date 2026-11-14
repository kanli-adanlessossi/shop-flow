# Medium findings M1 to M7: reservation follows replace, cancel before release, commit-ordered eviction, bounded relay run, dev-only and placeholder markings

The change fixes the seven Medium findings on top of the approved C1 to H3 work. `replace` now moves the stock reservation (reserve new, swap key, release old after commit). The unpaid-order job cancels first and releases afterwards, using a new `stock_release_pending` flag (V10). The cache advice is ordered outside the transaction advice so evictions run after commit. The outbox relay stops starting sends after 60 s. The committed dev credentials, the load-test token URL and the inventory-service skeleton are marked or fixed. The coder's record shows each of M1 to M4 failing on the old behaviour and passing on the new, and a clean `./mvnw clean verify` (152 order-platform tests, 0 failures).

Watch for: the M2 behaviour change (an unpaid order is now cancelled even when inventory is down; documented in US-7), and a failed cache eviction after commit now surfaces as an error on an already committed change (possible, minor).

**Verdict**: APPROVED

## High-level view

M1 keeps the PUT endpoint working by reserving the new lines under a fresh key before the order switches to it, and releasing the old key only after the commit. The order is never under-reserved. The cost, a refusal near an empty shelf although the net change would fit, is documented.

M2 closes the release-then-cancel window by cancelling first. The flag makes a failed release retryable by the next run. A payment arriving after the cancel meets the existing late-payment 409.

M3 is a one-line ordering change that covers all six `@CacheEvict` methods. M4 bounds a relay run to about 65 s, inside the unchanged 2 minute lease.

M5 to M7 label and fix rather than rebuild: dev-only banners, a README and a display name for the realm; the missing Keycloak base URL in the k6 script; a placeholder README and a fixed port for inventory-service.

<details>
<summary>Issues (2)</summary>

1. **Eviction failure after commit** (possible) — with the cache advice outside the transaction, a Redis evict error is thrown after the commit, so the client sees an error for a change that was saved and a stale entry may remain until TTL. Consider catching and logging evict errors, or confirm the TTL is short enough.
2. **Cleanup run counters** (confirmed, cosmetic) — `cancelOne` counts a cancelled order as "skipped" when its release throws a non-inventory error, although the order was cancelled. Count it as cancelled, or log it separately.

</details>

<details>
<summary>Details</summary>

## Failed eviction now lands after the commit (M3)

`CacheConfig` uses `@EnableCaching(order = LOWEST_PRECEDENCE - 100)`, so cache advice wraps transaction advice. `CacheEvictionAfterCommitTest` uses the real `CacheConfig` and the default transaction advice order and asserts `begin, commit, evict`. The Boot context relies on the default transaction advice order, which that test mirrors. One side effect: the config keeps the default error handler for evict errors (it only softens reads). Before, an evict failure inside the transaction rolled the change back. Now the change is committed first and the exception reaches the caller, leaving a possibly stale entry (possible; depends on Redis availability and the cache TTL).

## Cleanup counts a cancelled order as skipped

In `UnpaidOrderCleanup.cancelOne`, the cancel commits and then `release` runs. A non-inventory runtime error from `release` is caught by the outer loop and counted as `skipped`, though the order is cancelled with the flag set. The retry still works, since the next run picks up flagged orders. Only the log line is misleading (confirmed, cosmetic).

## Test coverage

M1 to M4 each have a test shown to fail on the old behaviour (the coder's red run lists seven failures: M1 two tests, M2 two, M3 one, M4 two). The M2 payment race itself is not exercised end to end; the ordering test pins the call sequence, which is a reasonable substitute for a few-millisecond window. The only existing test changed is `Us07...inventoryDownThenBack`, named in the summary and required by M2. Not tested: M1 paid-in-the-meantime during the reservation call, M1 optimistic-lock conflict between two replaces, and the retry loop's behaviour when inventory is down during pending releases. M5 to M7 are config and docs, checked by reading. The k6 change was not run.

</details>

<details>
<summary>File map</summary>

Full diff: `git diff` plus untracked files from `git status`.

- `OrderService.java`: replace moves the reservation, cancel-first API, flag methods
- `UnpaidOrderCleanup.java`: retry pending releases, cancel then release
- `Order.java`, `OrderRepository.java`, `V10__order_stock_release_pending.sql`: flag column, queries, reservation key made updatable
- `CacheConfig.java`: cache advice order
- `OutboxRelay.java`: 60 s run deadline
- `shopflow-realm.json`, `compose.yaml`, `keycloak/README.md`: dev-only marking
- `load-tests/place-orders.js`: token URL uses `KEYCLOAK_URL`
- `inventory-service` properties and README, `application.yaml`: placeholder marking, port 8081
- `docs/NOTES.md`, `docs/user-stories.md`: updated for M1 to M7
- New tests: `UnpaidOrderCleanupOrderingTest`, `CacheEvictionAfterCommitTest`, `OutboxRelayRunLimitTest`; `Us04` (3 added), `Us07` (1 changed)

</details>
