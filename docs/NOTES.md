# order-platform notes

Findings from working on this module, with the examples and numbers behind them.

- [Jackson 3 in Spring Boot 4.1](#jackson-3-in-spring-boot-41)
- [Lazy connection fetching](#lazy-connection-fetching)
- [Virtual threads: when they help and when they don't](#virtual-threads-when-they-help-and-when-they-dont)

Versions: Spring Boot 4.1.2, Spring Framework 7.0.10, Jackson 3.1.7, Hibernate 7.4.13, Java 25.

---

## Jackson 3 in Spring Boot 4.1

### Packages

Jackson 3 moved the core and databind classes to a new package root. The annotations did not move.

| What | Package | Artifact (this project) |
|---|---|---|
| `JsonMapper`, `ObjectMapper`, databind | `tools.jackson.databind` | `tools.jackson.core:jackson-databind` 3.1.7 |
| Streaming (`StreamReadFeature`, `StreamWriteFeature`) | `tools.jackson.core` | `tools.jackson.core:jackson-core` 3.1.7 |
| Annotations (`@JsonProperty`, `@JsonIgnore`, ...) | **`com.fasterxml.jackson.annotation`** (unchanged) | `com.fasterxml.jackson.core:jackson-annotations` 2.21 |

This is what `./mvnw dependency:tree` shows. All of it arrives through
`spring-boot-starter-jackson`. The project's own code imports no Jackson classes at all: Spring MVC
does the serialization.

```java
import com.fasterxml.jackson.annotation.JsonProperty;   // annotations: still com.fasterxml
import tools.jackson.databind.json.JsonMapper;          // everything else: tools.jackson
```

`JsonMapper` is injectable as a bean, which was handy for checking output in tests:

```java
@Autowired JsonMapper mapper;

mapper.writeValueAsString(new BigDecimal("1E+3"));   // "1000" with the settings below
```

### Configuring features with properties, not mapper beans

Boot 4.1 binds feature switches straight from `spring.jackson.*`. The prefix decides which Jackson
enum the keys come from:

| Property prefix | Jackson enum | Examples of keys |
|---|---|---|
| `spring.jackson.read.*` | `StreamReadFeature` | `strict-duplicate-detection`, `ignore-undefined`, `use-fast-double-parser`, `include-source-in-location`, `auto-close-source` |
| `spring.jackson.write.*` | `StreamWriteFeature` | `write-bigdecimal-as-plain`, `ignore-unknown`, `use-fast-double-writer`, `auto-close-target`, `flush-passed-to-stream` |
| `spring.jackson.json.read.*` / `.write.*` | `JsonReadFeature` / `JsonWriteFeature` | JSON-syntax options |
| `spring.jackson.deserialization.*` | `DeserializationFeature` | databind-level reading |
| `spring.jackson.serialization.*` | `SerializationFeature` | databind-level writing |
| `spring.jackson.datatype.datetime.*` | `DateTimeFeature` | date and time handling |
| `spring.jackson.mapper.*` | `MapperFeature` | mapper behaviour |

Other single-value keys exist too: `default-property-inclusion`, `property-naming-strategy`,
`time-zone`, `locale`, `date-format`, `default-leniency`, `use-jackson2-defaults`.

The enum names come from the jars (`javap` on `jackson-core-3.1.7`), and the property keys from the
`spring-boot-jackson` configuration metadata. Enum constants are written in kebab case.

What this module sets (`application.yaml`):

```yaml
spring:
  jackson:
    read:
      strict-duplicate-detection: true   # reject {"a":1,"a":2} instead of letting the last value win
    write:
      write-bigdecimal-as-plain: true    # money is written as 1000, never 1E+3
```

#### Measured effect (same app, each setting off and then on)

| Check | Setting off | Setting on |
|---|---|---|
| `BigDecimal("1E+3")` written by the mapper | `1E+3` | `1000` |
| `POST /api/orders` with `"customerId"` given twice | `201`, the last value silently wins | `400` problem detail |
| A normal `POST` | `201` | `201` |

The duplicate-key request:

```json
{ "customerId": "a", "customerId": "b",
  "lines": [ { "sku": "A", "quantity": 1, "unitPrice": 1 } ] }
```

With the feature on, the response is the same generic body as any unreadable request. The message
does not say that a key was duplicated:

```json
{"detail":"Failed to read request","instance":"/api/orders","status":400,"title":"Bad Request"}
```

### Examples of what Jackson produces in this module

Dates are ISO-8601 strings (`Instant`, `LocalDate`) and `BigDecimal` keeps its scale.

A version 2 order (`GET /api/orders/{id}`):

```json
{"id":2,"customerId":"c-v2","discountCode":"WELCOME","createdAt":"2026-11-12T11:15:32.373597Z",
 "status":"PENDING_PAYMENT",
 "lines":[{"sku":"A","quantity":2,"unitPrice":10.50,"lineTotal":21.00},
          {"sku":"B","quantity":1,"unitPrice":3.25,"lineTotal":3.25}],
 "totals":{"lineCount":2,"unitCount":3,"amount":24.25}}
```

A page. This needs `spring.data.web.pageable.serialization-mode: via-dto`. Without it Jackson
serializes `PageImpl` as-is and logs that the JSON shape is not stable.

```json
{"content":[{"id":3,"customerId":"c9","status":"PENDING_PAYMENT","createdAt":"2026-11-12T10:30:07.309276Z","total":8.00}],
 "page":{"size":20,"number":0,"totalElements":3,"totalPages":1}}
```

A problem detail (`spring.mvc.problemdetails.enabled: true`), here with the extra `errors` list:

```json
{"detail":"Invalid request content.","instance":"/api/orders","status":400,"title":"Bad Request",
 "errors":[{"field":"lines[0].quantity","message":"must be greater than 0"},
           {"field":"customerId","message":"must not be blank"}]}
```

A sales report row, where zero days are written as `0.00` so money always has two decimals:

```json
{"salesDate":"2026-11-10","orderCount":0,"unitCount":0,"revenue":0.00}
```

### Things to remember

- Records work as request and response types with no extra annotations.
- Nullable record components use JSpecify's `@Nullable` (`org.jspecify.annotations`), which is
  separate from Jackson. A `null` is written as `"discountCode":null`.
- A cached value that is deserialized by Java serialization (the Redis cache) bypasses Jackson
  entirely. See `OrderDetails`.

---

## Lazy connection fetching

`spring.datasource.connection-fetch` is a Boot 4.1 property (string, default `eager`). With `lazy`,
Boot wraps the `DataSource` in Spring's `LazyConnectionDataSourceProxy`, so a JDBC connection is
taken from the pool only when the first statement runs, not when the transaction begins.

```yaml
spring:
  datasource:
    connection-fetch: lazy
```

This module has it set to `lazy`. A check of the bean class confirmed the proxy:
`LazyConnectionDataSourceProxy` (with `eager` it is `HikariDataSource`).

### The experiment

A `@Transactional` method that simulates a remote call, then runs its first query:

```java
@Transactional
public long slow() throws InterruptedException {
    Thread.sleep(2000);                       // simulated remote call, inside the transaction
    return em.createQuery("select count(o) from Order o", Long.class).getSingleResult();
}
```

Setup:

- 8 parallel HTTP requests to a controller calling it, against a real embedded server.
- Hikari pool deliberately small: `maximum-pool-size=4`, `minimum-idle=4`.
- `hikaricp.connections.active` and `hikaricp.connections.pending` read from the Micrometer
  registry every 50 ms.
- One warm-up request first. Same code, same pool, only `connection-fetch` changed.
- Database: H2 in-memory.

### Results

| | `eager` | `lazy` |
|---|---|---|
| `DataSource` bean | `HikariDataSource` | `LazyConnectionDataSourceProxy` |
| Requests OK | 8 of 8 | 8 of 8 |
| Wall time | **4020 ms** | **2017 ms** |
| `connections.active` during the 2 s wait | 4 (pool full) | 0 |
| Max `connections.pending` | 4 | 0 |
| Average `active` over the run | 2.99 | 0.00 |

Sampled `eager` series (every ~0.5 s):

```
t=   4ms active=0 pending=0      requests not in the service yet
t= 515ms active=0 pending=0
t=1028ms active=4 pending=4      4 hold a connection while sleeping, 4 wait for one
t=1537ms active=4 pending=4
t=2047ms active=4 pending=0      first wave done, second wave holds the connections
t=2558ms active=4 pending=0
t=3066ms active=4 pending=0
t=3576ms active=4 pending=0
```

Sampled `lazy` series: `active=0 pending=0` at every sample.

### Reading the numbers

- **Eager** takes a connection when the transaction starts and keeps it through the 2 s sleep.
  The pool filled after four requests, the other four queued, and the batch ran in two waves
  (about 4 s).
- **Lazy** takes no connection during the sleep. All eight requests slept at once, then ran their
  queries within milliseconds (about 2 s).
- `maxActive=0` under lazy means the sampler never saw a connection in use. It does not mean none
  were used. The queries hold a connection for only a few milliseconds, which a 50 ms sample can
  miss.
- The first samples show `active=0` in both modes because the requests had not reached the
  service yet. That is client startup, not a difference between the modes.

### Caveats

- One run per mode, on H2 in memory, with a simulated delay. These are illustrations, not
  benchmarks.
- A pool of 4 against 8 requests was chosen to make the effect visible. With a larger pool the
  wall-time gap shrinks. The connection is still held for the whole 2 s under `eager`.
- It only helps when slow non-database work happens before the first query. A method that queries
  first and then calls out holds its connection in both modes.
- With `lazy`, a database that is unreachable shows up at the first query, not when the
  transaction begins.
- `spring.jpa.open-in-view` is still `true`, so a request can hold a Hibernate session for its
  whole life. With `lazy` that no longer implies holding a connection, but
  `open-in-view: false` is the cleaner setting.
- The better fix for the pattern in the experiment is to move the remote call outside the
  transaction. That frees the connection in both modes.

---

## Virtual threads: when they help and when they don't

`spring.threads.virtual.enabled: true` is set in `application.yaml`. Tomcat then handles each
request on a virtual thread (log name `tomcat-handler-N`, instead of `http-nio-auto-1-exec-N` with
platform threads).

### When they help

- **Many requests that mostly wait.** A request that spends its time on a database, a Redis call or
  an HTTP call to inventory-service parks its virtual thread while it waits, and the carrier thread
  runs something else. You can have thousands of such requests in flight without a big thread pool.
- **Simple blocking code.** The code stays plain and sequential (`RestClient`, JPA, `JdbcClient`).
  No reactive rewrite is needed to get that concurrency.

### When they don't

- **A single request is not faster.** Each call still takes as long as the database or the remote
  service takes. Virtual threads raise how many requests can wait at once, not how fast one runs.
- **CPU-bound work.** Computing, serializing and mapping still need a real core. With more
  runnable threads than cores they just compete.
- **They remove the thread limit, not the other limits.** The next bottleneck is whatever the
  threads wait on:
  - the Hikari pool (`maximum-pool-size`): excess requests queue for a connection;
  - the warehouse: many callers can now reach inventory-service at once.
  Cap these on purpose. In this module `@ConcurrencyLimit` on `InventoryStockReservations.reserve`
  (`shopflow.inventory.max-concurrent-requests`) is the only cap on parallel warehouse calls, and
  the connection pool size limits database work.
- **Pinning.** A virtual thread that blocks while it cannot unmount holds its carrier thread. On
  Java 25, blocking inside `synchronized` no longer pins. Pinning can still happen during class
  loading and class initialization, and in native frames.
- **Do not pool them.** Create one per task. Limit concurrency with a semaphore or
  `@ConcurrencyLimit`, not with a small thread pool.

### What was checked in this module

A JFR recording (JDK 25) of 400 concurrent clients against the app, with the pinning threshold at
0 ms, gave 9,011 `jdk.VirtualThreadPinned` events:

- 8,954 happened in the first second, when many threads hit cold classes at once, and all of them
  were class loading or class initialization. The longest was 106 ms.
- 57 came later. 44 of them were class loading, and the rest were first use of Jackson, Spring
  Data, H2 and the report query.
- None was blocked in application code.

Not verified: a second, warmed-up run to confirm there is no pinning at steady state, and any run
against real PostgreSQL or Redis, whose drivers could behave differently from H2 and Caffeine.

### Load comparison: virtual threads off and on

`load-tests/compare-virtual-threads.ps1` runs `load-tests/place-orders.js` (200 orders/s for 2
minutes, each reserving stock at a WireMock inventory) against the app with
`spring.threads.virtual.enabled` off and on, two runs each, in the order off, on, off, on. Every
run starts from a fresh PostgreSQL, checks from a thread dump which threads serve requests, and does
a 20 s unrecorded warm-up first. Summaries are in `load-tests/results/vt-*.json`.

| Run | Virtual threads | Requests/s | p95 | p99 | Max | Errors | Dropped iterations |
|---|---|---|---|---|---|---|---|
| vt-off-1 | off | 199.9 | 65 ms | 232 ms | 542 ms | 0 | 0 |
| vt-off-2 | off | 199.9 | 77 ms | 131 ms | 235 ms | 0 | 0 |
| vt-on-1 | on | 199.9 | 101 ms | 246 ms | 473 ms | 0 | 0 |
| vt-on-2 | on | 199.5 | 149 ms | 452 ms | 1254 ms | 0 | 61 |

"Errors" counts failed requests and unexpected statuses; there were no 503s or 409s in the measured
runs. Reading it:

- Throughput matched the target in all four runs (about 24,000 orders each).
- p95 was lower with virtual threads off (65 and 77 ms) than on (101 and 149 ms), and the one run
  with dropped iterations was an "on" run. So there is no sign of a benefit here.
- The spread between the two runs of the same mode (65 vs 77 ms off, 101 vs 149 ms on) is as large as
  some of the differences between modes. Two runs per mode, on one machine with the database, WireMock
  and k6 all in Docker, can't separate a real effect from noise.
- At 200 requests/s with about 20 ms per request, only a handful of requests are in flight at once,
  so the platform thread pool (200 threads by default) is nowhere near its limit. Virtual threads
  help when many requests wait at the same time; this load does not create that.
- Without the warm-up, the first full run (cold JVM) dropped 508 of 24,000 iterations and had a p99
  of 3.7 s. Warm-up matters more here than the thread type.

### Related

- [Lazy connection fetching](#lazy-connection-fetching): with many concurrent requests, hold a
  database connection only while querying.
- A remote call inside a `@Transactional` method holds a connection (under `eager` fetching) for
  its whole duration. `OrderService.create` reserves stock first, with no transaction open, and only
  then saves the order in a short transaction of its own, for that reason.
