# order-platform notes

Two findings from working on this module, with the examples and numbers behind them.

- [Jackson 3 in Spring Boot 4.1](#jackson-3-in-spring-boot-41)
- [Lazy connection fetching](#lazy-connection-fetching)

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
 "status":"PENDING",
 "lines":[{"sku":"A","quantity":2,"unitPrice":10.50,"lineTotal":21.00},
          {"sku":"B","quantity":1,"unitPrice":3.25,"lineTotal":3.25}],
 "totals":{"lineCount":2,"unitCount":3,"amount":24.25}}
```

A page. This needs `spring.data.web.pageable.serialization-mode: via-dto`. Without it Jackson
serializes `PageImpl` as-is and logs that the JSON shape is not stable.

```json
{"content":[{"id":3,"customerId":"c9","status":"PENDING","createdAt":"2026-11-12T10:30:07.309276Z","total":8.00}],
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
