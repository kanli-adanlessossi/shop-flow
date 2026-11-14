# inventory-service

Keeps the stock of every SKU and the reservations that order-platform makes against it. Spring Boot,
PostgreSQL (Flyway, JPA), an OAuth2 resource server on the same Keycloak realm as order-platform, and a
Kafka consumer for the order events. Port **8081**, the default `shopflow.inventory.base-url` of
order-platform.

## API

All calls need `Authorization: Bearer <token>` with the realm role `inventory`. The `shopflow-service`
client (client credentials) has it, and order-platform signs in as that client. Health endpoints are open.
Errors are `application/problem+json`; every response carries `X-Correlation-Id`.

| Call | Success | Failure |
|---|---|---|
| `GET /api/stock/{sku}` | 200 `{"sku","available"}` | 404 unknown SKU |
| `POST /api/stock/reservations` body `{"reservationId","lines":[{"sku","quantity"}]}` | 200 `{"reservationId"}` | 409 not enough stock (or unknown SKU), no line changes; 400 invalid body |
| `DELETE /api/stock/reservations/{reservationId}` | 204 | 404 no such reservation; 409 already shipped |

Reserving is all or nothing and safe to repeat: the same `reservationId` again answers 200 and takes no
more stock. Lines with the same SKU are added up. No token is 401, a token without the role is 403.

## Reservation lifecycle

| Status | Set by | Release (`DELETE`) | Expires |
|---|---|---|---|
| `HELD` | `POST /api/stock/reservations` | 204, stock returned | yes, after `hold-ttl` |
| `CONFIRMED` | event `order.confirmed` | 204, stock returned | never |
| `SHIPPED` | event `order.shipped` | **409**, stock stays taken | never |

The status only moves forward (HELD, CONFIRMED, SHIPPED). A `order.shipped` that arrives before its
`order.confirmed` leaves the reservation SHIPPED. `HELD` to `SHIPPED` is allowed so a reordered event
cannot leave a reservation stuck.

## Events

The consumer reads `order-events` in the group `inventory`. It has its own copy of the event classes
(`events/`), the same JSON envelope order-platform writes, and ignores the fields it does not need.

- `order.confirmed` `{orderId, reservationId}` sets CONFIRMED, `order.shipped` `{orderId, reservationId}`
  sets SHIPPED.
- An event is handled once per event id (table `processed_events`, purged after 14 days).
- An unknown event type, an event without a reservation id and an event for an unknown reservation are
  logged and skipped. They are not retried and do not reach the dead-letter topic.
- Any other failure is retried on `order-events-retry-*` (0.5 s, 1 s, 2 s) and then lands in
  `order-events-dlt` and in the table `dead_letters` (counter `shopflow.events.dead_letters`).

## Stale holds

order-platform cancels an unpaid order after 30 minutes and releases its reservation. If that release
never arrives, the job `HeldReservationExpiry` (cron `shopflow.inventory.expiry-cron`, default every five
minutes, ShedLock `expireHeldReservations`) releases `HELD` reservations older than
`shopflow.inventory.hold-ttl` (default **45m**, above the 30 minutes so the order cancels first).
`CONFIRMED` and `SHIPPED` reservations are never touched.

## Run it

Docker must be running. The root `compose.yaml` holds Kafka, Keycloak and the databases; the app starts
what it needs through Spring Boot Docker Compose (`start-only`: it never stops what order-platform uses).

```powershell
./mvnw -pl inventory-service spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The `dev` profile seeds `SKU-001` to `SKU-008` with 1000 units each (`db/dev`). Its database is the
`postgres-inventory` service of the root compose file (port 5433, user, password and database
`inventory`, **development only**). Without Docker Compose support, start the stack yourself:

```powershell
docker compose --profile inventory up -d --wait
```

Get a token and call it:

```powershell
$t = (Invoke-RestMethod -Method Post http://localhost:8180/realms/shopflow/protocol/openid-connect/token `
  -Body @{grant_type='client_credentials';client_id='shopflow-service';client_secret='shopflow-service-secret'}).access_token
Invoke-RestMethod http://localhost:8081/api/stock/SKU-001 -Headers @{Authorization="Bearer $t"}
```

Ports: inventory-service 8081, order-platform 8080, Keycloak 8180, Kafka 9092, inventory Postgres 5433.
The WireMock stand-in (`deploy/wiremock`) also uses 8081 and cannot run together with this service.

## Tests

`./mvnw -B -ntp -pl inventory-service verify` (Docker needed; Error Prone and NullAway run on the main
and test code).

- `StockControllerTest`: the HTTP contract and status codes with a mocked service.
- `StockRepositoryPostgresTest`: concurrency on a real PostgreSQL (no overselling, no deadlock, one
  reservation id from many threads).
- Everything else extends `InventoryIntegrationTest` and shares **one** Spring context with PostgreSQL,
  Kafka and Keycloak: `SecurityIntegrationTest` (401/403/200 with real tokens), `OrderEventConsumerTest`,
  `HeldReservationExpiryTest` and `RealServiceContractTest`.
- `contract/`: `StockContract` holds the requests order-platform makes (reserve, release, stock query).
  `WireMockStubContractTest` runs them against the stubs in `deploy/wiremock/inventory` and
  `RealServiceContractTest` against this service, so the stand-in cannot drift from the real contract.
  Error paths are asserted against the real service only, because the stubs always answer success.