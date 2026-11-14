# inventory-service (placeholder)

**This module is a skeleton, not a working service.** It is the generated Spring Boot application
with no controllers: it exposes none of the stock endpoints that order-platform calls.

- **The contract** order-platform relies on (reserve stock, release a reservation) is defined by the
  WireMock stubs in `deploy/wiremock/inventory/mappings`. Every order-platform test runs against
  those stubs (`InventoryStandInContainer`); there is no contract test against this module.
- **Locally**, run the stand-in instead: `docker compose -f deploy/wiremock/compose.yaml up -d`. It
  listens on `http://localhost:8081`, the default `shopflow.inventory.base-url`.
- **Port.** `server.port=8081`, the port order-platform expects, so the module does not clash with
  order-platform on 8080. It cannot run at the same time as the WireMock stand-in, which uses 8081 too.
- **When docs say "inventory-service"** (`docs/user-stories.md`, `docs/NOTES.md`,
  `order-platform/compose.yaml`) they mean whatever answers that contract, today the stand-in.

To make it real, implement the endpoints the stubs describe and add a contract test that runs the
same requests against both.
