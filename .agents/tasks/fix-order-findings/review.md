# Review: fix C1, C2, H1, H2, H3

Verdict: APPROVED. No blockers.

Evidence:
- C1: `unitPrice` is gone from `OrderLineRequest` and `OrderDraft.Line`. `OrderService.toLines` prices from `sku_prices` (V9). It runs before the stock reservation, so an unknown SKU gets 422 with no stock taken. `replace` re-prices through the same method. Tests: `aClientPriceIsIgnored`, `unknownSku`, `replaceIsRepriced`.
- C2: PATCH status is ADMIN only. `updateStatus` allows only PENDING_PAYMENT to CANCELLED and CONFIRMED to SHIPPED. A same-status request is a no-op, which keeps the cancel-twice behaviour. Anything else raises `IllegalStatusTransitionException` (409). `cancelIfStillUnpaid` is untouched. Tests: `illegalTransitions`, `confirmedOrders`, `ownerCannotSetStatus`.
- H1: `releaseAfterCommit` runs after commit on a real cancel and on delete. A refused delete releases nothing, and release is idempotent. Tests: `cancelReleasesTheReservation`, `deleteWhileUnpaid`, `deleteWhenCancelled`, `refusedDeleteKeepsTheReservation`.
- H2: `@Version` on `Order` plus V8. A stale write maps to 409. Test: `OrderConcurrencyStoryTest.staleWriteIsRejected` is deterministic and, per the summary, fails with `@Version` removed.
- H3: `search(customerId, ...)` with four separate queries. The controller scopes non-admins to `authentication.getName()` and admins see everything. The US-3 test is re-enabled and extended with a cross-user check.
- Preserved: public endpoints, CREATE_RULE, REPLACE_RULE, the sort whitelist and the outbox in the same transaction are unchanged. The scheduled cleanup is untouched. The Flyway migrations V8 and V9 are present. The dev price seed loads only through the dev profile. `docs/user-stories.md` and `docs/NOTES.md` are updated.
- Changes to existing tests (line format without price, the removed unitPrice field error, the owner-PATCH test replaced by a 403 test) follow from the fixes.
- Verification (summary.md): full `mvnw verify` passed with 142 order-platform tests, 0 failed and 0 skipped. Reverting four of the fixes made the new tests fail. The C1 and owner-403 tests were not run against old code, but by reading they cannot pass there.
- Nothing is committed (working tree and untracked files only).

Scope notes (not blockers): the price table and the admin-only PATCH are design choices the summary states openly. They are the minimum needed for C1 and C2.
