# WireMock stand-ins

Two WireMock containers that replace the services order-platform calls, so you can try slow
responses, errors and dropped connections without breaking a real service.

| Service | URL | Stubs |
|---|---|---|
| inventory-service | `http://localhost:8081` | `GET /api/stock/{sku}`, `POST /api/stock/reservations`, `DELETE /api/stock/reservations/{reservationId}` |
| payment provider | `http://localhost:8082` | `POST /api/payments/callbacks/verify` |

Port 8081 is the default `shopflow.inventory.base-url`, so the app talks to the inventory stand-in
with no configuration. The payment stub is not used by the app yet. Its request and response shape
is invented, so change `payments/mappings/callback-verify.json` to match the real provider.

The compose file is here, outside `order-platform/`, on purpose. Spring Boot's Docker Compose
support starts everything in `order-platform/compose.yaml` on every run, and these containers
should only run when you want them.

## Start and stop

```powershell
docker compose -f deploy/wiremock/compose.yaml up -d
docker compose -f deploy/wiremock/compose.yaml down
```

Run these from the repository root. The image is pinned to `wiremock/wiremock:3.13.1`.

## Change behaviour while running

```powershell
./deploy/wiremock/wiremock.ps1 <inventory|payments> <mode> [options]
```

Every call resets the service to its file stubs first, then applies the mode you asked for. Nothing
is saved: a container restart or `... ok` brings back normal behaviour.

### Modes

| Mode | What the service does |
|---|---|
| `ok` | Normal answers, no delay |
| `slow` | Normal answers after a delay (needs `-DelayMs`) |
| `conflict` | 409 problem body |
| `not-found` | 404 |
| `bad-request` | 400 |
| `error` | 500 |
| `unavailable` | 503 |
| `gateway-timeout` | 504 |
| `reset-connection` | Closes the TCP connection with a reset |
| `empty` | Closes the connection with an empty response |
| `malformed` | Sends a broken chunked response |
| `garbage` | Sends random bytes, then closes |
| `flaky` | Fails with 500 `-Failures` times (default 2), then answers normally |

Status modes return a body like `{"status":409,"title":"Conflict","detail":"WireMock conflict"}`
with content type `application/problem+json`.

### Options

| Option | Meaning |
|---|---|
| `-DelayMs 3000` | Add a fixed delay to every response. Works with every mode |
| `-MaxDelayMs 900` | With `-DelayMs`, use a uniform random delay between the two values |
| `-Failures 3` | With `flaky`, how many calls fail before one succeeds |
| `-UrlPattern '/api/stock/reservations'` | Regex for the paths a fault applies to. Default is every path |
| `-Show` | List the active stubs and global settings, and change nothing |

`-UrlPattern` only limits the fault modes. A delay applies to the whole service.

### Examples

```powershell
# Slower than the 500 ms read timeout: the client gives up and retries once or twice
./deploy/wiremock/wiremock.ps1 inventory slow -DelayMs 3000

# Fail twice, then succeed: shows @Retryable recovering
./deploy/wiremock/wiremock.ps1 inventory flaky -Failures 2

# Insufficient stock on reservations only, with a 400 ms delay
./deploy/wiremock/wiremock.ps1 inventory conflict -UrlPattern '/api/stock/reservations' -DelayMs 400

# Random latency between 500 and 900 ms
./deploy/wiremock/wiremock.ps1 inventory slow -DelayMs 500 -MaxDelayMs 900

# Dropped connection from the payment provider
./deploy/wiremock/wiremock.ps1 payments reset-connection

# Back to normal
./deploy/wiremock/wiremock.ps1 inventory ok
```

## What the app does in each case

Measured with `POST /api/orders` against the inventory stand-in (H2, default settings: 500 ms read
timeout, and reservation retries that stop after 1 s in total). Times include retries.

| Inventory mode | Response | Time |
|---|---|---|
| `ok` | 201 | 413 ms for the first request, 23 ms once warm |
| `slow -DelayMs 300` | 201 | 321 ms |
| `slow -DelayMs 700` | 503 `inventory-unavailable` | 1.26 s |
| `slow -DelayMs 3000` | 503 `inventory-unavailable` | 1.25 to 1.28 s (3 runs) |
| `flaky -Failures 1` | 201 | 261 ms, the second attempt succeeds |

A hung inventory is two attempts of 500 ms plus one backoff of about 200 ms, then the retry budget is
spent. Modes not listed (`conflict`, `error`, `reset-connection`, ...) were measured under the earlier
2 s read timeout and have not been re-measured.

Stock is reserved before the order is saved, so a failed reservation saves no order. After a 503 the
app also sends `DELETE /api/stock/reservations/{reservationId}` in the background, because the
reservation may have been made even though the answer never arrived.

Related settings are in `order-platform/src/main/resources/application.yaml`
(`shopflow.inventory.*`) and on `InventoryStockReservations` (`@Retryable`, `@ConcurrencyLimit`).

## Adding or changing stubs

Stubs are JSON files in `<service>/mappings/`, loaded at container start. The containers run with
`--global-response-templating`, so responses can use request data:

```json
"jsonBody": {
  "sku": "{{request.pathSegments.[2]}}",
  "reservationId": "{{jsonPath request.body '$.reservationId'}}"
}
```

File stubs have WireMock's default priority (5). The script's overrides use priority 1, so they
win. After editing a file, restart the container:

```powershell
docker compose -f deploy/wiremock/compose.yaml restart inventory
```

The WireMock admin API is at `http://localhost:8081/__admin` and `http://localhost:8082/__admin`
if you want to look at requests or stubs directly.

## Limitations

- Needs Docker and PowerShell. The script was tested on Windows PowerShell 5.1.
- One mode at a time per service. Calling the script again replaces the previous mode.
- `-UrlPattern` does not filter by HTTP method.
- Runtime changes are in memory only.
