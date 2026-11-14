# Review: Low findings L1 to L15 and two minor issues

Verdict: APPROVED. No blockers.

Evidence checked (source read; suites not re-run, recorded `./mvnw clean verify` BUILD SUCCESS in summary.md, 0 failed/0 skipped, Docker-backed tests ran):
- summary.md has a line for each of the 17 items (L1 to L15, (a), (b)) and the build result.
- (a) `CacheConfig.EvictUnreadableEntryHandler.handleCacheEvictError` logs and does not rethrow. Put errors are still rethrown. `CacheEvictErrorHandlerTest` fails on the old code, whose default handler rethrows.
- (b) `UnpaidOrderCleanup` increments `cancelled` once `cancelIfStillUnpaid` returns a reservation. A non-inventory release error is logged and the run continues. `InventoryUnavailableException` still stops the run. The pending flag is kept, so the next run retries. The new test asserts "2 cancelled, 0 skipped". The old code would log 1 and 1, so the test fails on it.
- L1, L4, L13 have reasoned decisions. L1 keeps ordering, adds a failure counter and documents it. L4 and L13 are accepted with the reason recorded in code.
- L2, L3, L5 each have code and a test. L3 is a bounded catch that ends the run with at-least-once delivery unchanged.
- L14: V11 is the next version and is justified by a schema change. The enum has exactly PENDING_PAYMENT, CONFIRMED, SHIPPED and CANCELLED, stored as STRING, so the check cannot reject valid data. `length = 64` is declared on the entity.
- L9: the numbers come from recorded local runs. `ci.yml` states that no hosted-runner timing was measured. The 600 s fail limit stays at the stated target.
- L10: the script writes to a per-run folder and no longer deletes. The final report still reads from `$results`. `.gitignore` covers the folder.
- L11: the script is simple and was verified locally for both the pass and fail cases.
- Items that were not changed: docs/NOTES.md was not edited in this step. No commits were made. Public endpoints, ownership rules, outbox transaction behavior and the sort whitelist are unchanged. The earlier C/H/M fixes are intact (the CacheConfig ordering, the cancel-first cleanup and the pending flag are all still present).
- Existing tests changed (`OutboxRelayRunLimitTest` constructor, plus added tests) are named in the summary and required by the L1 constructor change.

Residual notes (non-blocking): the (b) test was not run against the old code, but reading shows it would fail. The L10 script and L11 CI step were not run on the target platforms. Both are disclosed in the summary.
