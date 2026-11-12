# ADR 0001: How events leave the order service

- **Status:** Draft (proposed, not yet accepted)
- **Date:** 2026-11-12
- **Deciders:** to be filled in
- **Scope:** `order-platform`, the `order-events` Kafka topic

## Context

`order-platform` must tell other parts of the system when an order is placed or cancelled
(`order.placed`, `order.cancelled`). Saving the order and sending to Kafka are two systems, and no
transaction spans both. If the app sends first and then crashes before the commit, it announces an
order that does not exist. If it commits first and crashes before sending (or Kafka is down), the
event is lost and nobody is told. This is the "dual write" problem.

The fix both options below share is the **transactional outbox** idea: record the event in the
database in the same transaction as the business change, and send it to Kafka afterwards, with retries.

What exists today (built and verified in this repository):

- `OutboxEvents.add(payload)` inserts a row into `outbox_events` inside the caller's transaction and
  refuses to run outside one (`Propagation.MANDATORY`). `OrderService` calls it when an order is
  placed and when it is cancelled.
- `OutboxRelay` polls every second under ShedLock, sends unpublished rows to Kafka in id order,
  marks them published, and stops at the first failure so events of one order never go out of order.
  Published rows are purged after 7 days.
- Events are an `EventEnvelope` (`eventId`, `occurredAt`, `payload`, with the type name written
  beside the payload) serialized as JSON with a fixed target type on the consumer side.
- The consumer deduplicates by `eventId` (`processed_events` inbox) and retries without blocking
  through retry topics and a dead-letter topic.
- Size: about 100 lines of code in two classes plus a 15-line migration. Verified with tests on
  H2 and PostgreSQL, including "Kafka stopped while an order is placed": the order succeeds, the event
  waits, and it is delivered once, about 3 seconds after Kafka returns.

The question is whether to keep this, or replace the publishing half with **Spring Modulith's event
publication registry and event externalization**.

## Decision drivers

1. The event is stored if and only if the business change commits (atomicity).
2. Events of one order reach Kafka in the order they happened (per-key ordering).
3. Failures are retried automatically, and a stuck event is visible and recoverable.
4. Little code and few moving parts for a small team.
5. Maturity on the versions in use (Spring Boot 4.1.2, Spring Framework 7, Java 25).
6. Reversibility: how hard it is to change our mind later.

## Options

### A. Keep the own outbox and relay

- (+) Already built and tested against a real broker and both databases.
- (+) Full control of the wire format: the envelope, the type resolver and the key are ours, and the
  relay sends the stored JSON unchanged.
- (+) Per-key ordering is a direct consequence of the design (one relay, id order, stop on failure).
- (+) No new framework. `OrderService` depends on one small class.
- (-) We own the operational behavior: there is no attempt counter, no backoff per event and no
  "failed" state. A row that can never be sent (for example one the broker rejects as too large)
  blocks every later row until someone removes it. This is the main weakness.
- (-) The relay polls every second even when idle.
- (-) No built-in view of "what is pending or failed" beyond querying the table.

### B. Spring Modulith event publication registry and `@Externalized`

How it works, from the reference documentation: events published through Spring's
`ApplicationEventPublisher` are written to an event publication log in the same transaction as the
business change, and externalization to the broker runs as a transactional event listener guarded by
that registry. For Kafka, the annotation value gives the topic and the message key
(`@Externalized("order-events::#{#this.orderId()}")`); the event can be mapped to a different payload,
which is how our envelope could be produced.

- (+) Less code of our own, and a documented publication lifecycle since Modulith 2.0
  (published, processing, completed, failed, resubmitted) with completion attempts, resubmission APIs
  (`FailedEventPublications`) and completion modes (keep, delete or archive).
- (+) `OrderService` would publish a plain domain event; sending to Kafka becomes configuration.
- (+) Works with JDBC and Flyway-managed schemas; supports other brokers if we ever add one.
- (-) The reference documentation itself says the native externalization is based on an asynchronous
  listener and lacks features expected from a real outbox implementation. Modulith 2.1 added an
  `outbox` externalization mode that delegates to Namastack Outbox or JobRunr (third-party), which
  means a further dependency and a less proven path than the default.
- (-) Ordering needs care: serialized externalization is a property
  (`spring.modulith.events.externalization.serialize-externalization`, default `false`), so per-key
  ordering is not what we get by default.
- (-) Retries are not automatic by default. Republishing on restart is off by default and the
  documentation advises against it with several instances. The staleness monitor is off by default.
  We would still have to schedule resubmission ourselves.
- (-) With the default completion mode, completed rows stay in the table until we purge them.
- (-) It brings Modulith concepts (modules, verification, its serializer) into a project that does
  not use them, and our `EventEnvelope` and type resolver would need to be rebuilt on its mapping step.
- (-) The version that fits Spring Boot 4.1.2 has not been verified (see open questions).

### C. Change data capture (for example Debezium reading the outbox table)

- (+) No polling in the application, and low latency.
- (-) Needs Kafka Connect and database replication settings, which is a lot of infrastructure for
  this project. Not considered further.

## Proposed decision

**Keep option A, the own outbox and relay, for now.** Do not adopt Modulith externalization yet.

Reasons, mapped to the drivers:

- It already meets drivers 1 and 2 with evidence (tests on both databases, Kafka-down recovery,
  per-key ordering), while option B reaches driver 2 only with an extra setting and driver 3 only with
  scheduling we would still write.
- Driver 4 is a draw at this size: the own outbox is about 100 lines, and Modulith adds a dependency, a
  table, a serializer and new concepts in exchange.
- Driver 5 favors waiting: the default externalization is documented as short of a real outbox, and
  the mode that closes the gap rests on third-party libraries whose behavior on this stack I have not
  verified.
- Driver 6 is favorable: the publishing side is isolated behind `OutboxEvents.add(Payload)` and
  `OutboxRelay`, so replacing it later touches those two classes and one table.

## Consequences

- We keep maintaining the relay. Before relying on it in production, two gaps should be closed:
  1. **Poison row protection.** Add an attempt counter and a `failed` state to `outbox_events`; after N
     failures, mark the row failed, raise an alert (a counter, as for dead letters) and let later rows
     continue. Note the trade-off: skipping a failed row breaks per-key ordering for that order.
  2. **Idle polling and lag visibility.** Expose the number of unpublished rows and the age of the
     oldest as metrics, and consider a longer interval or a wake-up when `add` commits.
- Consumers must still be idempotent, because the relay is at-least-once (the inbox already covers this).
- The event size limit of 4,000 characters of JSON stays.
- Adding another event source later means calling `OutboxEvents.add`, which is simple but is a rule
  people must know: events must not be sent to Kafka directly.

## When to revisit

Reopen this decision if any of these happens:

- The project adopts Spring Modulith for module boundaries, so the registry would be there anyway.
- We need several brokers or consumers per event, or event volume makes our single relay a bottleneck.
- The relay needs features we would otherwise build ourselves: per-event backoff, resubmission by age,
  archive of completed events.
- Spring Modulith's `outbox` mode is proven on our Spring Boot version, with the library it uses.

## Migration path to option B (if chosen later)

1. Add `spring-modulith-starter-jdbc` and `spring-modulith-events-kafka` at a version verified for
   the Spring Boot in use; create its `event_publication` table with Flyway.
2. Replace `OutboxEvents.add(payload)` in `OrderService` with `ApplicationEventPublisher.publishEvent(...)`
   of a domain event, annotated `@Externalized("order-events::#{#this.orderId()}")`, and map it to
   the `EventEnvelope` in the externalization configuration.
3. Turn on serialized externalization and scheduled resubmission of failed publications.
4. Drain `outbox_events` (relay until empty), then delete `OutboxRelay`, `OutboxEvents` and the table.
5. Re-run the checks that exist today: atomicity with the order, per-key ordering, Kafka down and back,
   duplicates absorbed by the inbox.

## Open questions

- Which Spring Modulith release matches Spring Boot 4.1.2? Modulith 2.0 targets Spring Boot 4 and
  Spring Framework 7, and a 2.2 release candidate targets Boot 4.2, but I have not confirmed the
  supported range for 4.1 or run it in this project.
- Is Modulith's `outbox` mode (Namastack Outbox or JobRunr) production-ready enough for us, and
  what does it add to operations?
- Do we want Modulith's module structure and verification for this application at all?
- Should a permanently failing event be skipped (and ordering for that order relaxed) or should it
  stop the line until someone fixes it?

## References

- Spring Modulith reference, "Working with Application Events", including the event publication
  registry, the publication lifecycle, and "Externalizing Events":
  https://docs.spring.io/spring-modulith/reference/events.html
- Spring Modulith reference, appendix with configuration properties (externalization mode,
  serialized externalization, completion mode, republish on restart, staleness):
  https://docs.spring.io/spring-modulith/reference/appendix.html
- Spring Modulith 2.0 release announcement (Spring Boot 4 baseline, publication lifecycle, serialized
  externalization): https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released
- In this repository: `OutboxEvents`, `OutboxRelay`, `V5__create_outbox_events.sql`,
  `EventEnvelope`, and `OrderEventHandler` (the consumer inbox).
