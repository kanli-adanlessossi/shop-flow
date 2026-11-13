# order-platform user stories

The stories implemented so far, with their acceptance criteria. Written down after the fact from
the behaviour that exists (there was no backlog in the repo), roughly in the order they were built.
US-6 keeps the number it was referred to by earlier.

Each story has one acceptance test class in
`order-platform/src/test/java/com/shopflow/order/stories/`, named after it (`Us06DailySalesReportStoryTest`,
...). They run the whole application on a real port against PostgreSQL, Kafka, Keycloak (with the
real `shopflow` realm) and the WireMock inventory stand-in (with the real `deploy/wiremock` stubs),
all as Testcontainers. Run only them, from the repo root, with
`./mvnw -pl order-platform test -Dgroups=story`.

Criteria marked **open** are not implemented; their test is `@Disabled` with the reason, so the gap is
visible in every test report instead of being forgotten.

Users (Keycloak realm `shopflow`): `alice` (customer), `bob` (admin), client `shopflow-service`
(client credentials, admin).

---

## US-1 Service info

As an operator, I want to see which version and profiles are running, so that I know what is deployed.

- `GET /api/health-info` returns the build version (v2) or the full build info and profile list (v1).
- It needs a token like every other API endpoint; only `/actuator/health` is open.

## US-2 Place an order

As a customer, I want to place an order, so that the goods are reserved for me.

- `POST /api/orders` with lines (SKU, quantity, unit price) answers 201, a `Location` header and the
  order in status `PENDING_PAYMENT`, with line totals and the order total.
- The stock is reserved at inventory-service under a reservation id stored with the order.
- Not enough stock: 409 problem detail, no order is created.
- Inventory unavailable: 503 problem detail after a short retry, no order is created, and a possibly
  made reservation is released.
- Invalid input: 400 with one entry per invalid field.
- An `order.placed` event is published.

## US-3 View orders

As a customer, I want to see my orders, so that I can follow them.

- `GET /api/orders/{id}` shows the order with its lines and totals; someone else's order is 403, a
  missing one 404.
- `GET /api/orders` lists orders newest first, page by page, filterable by status (v2), sortable by
  `total`, `customerId`, `createdAt`, `status`, `id`; an unknown sort property is 400.
- **open:** a customer's list shows only their own orders (today it shows all orders).

## US-4 Change or delete an unpaid order

As a customer, I want to change or remove an order before I pay, so that I can correct mistakes.

- `PUT /api/orders/{id}` replaces customer, discount code and lines while the order is
  `PENDING_PAYMENT`; afterwards 409 with the order status.
- `DELETE /api/orders/{id}` removes an order that is `PENDING_PAYMENT` or `CANCELLED` (204); a
  confirmed order is 409.
- An order cannot be handed to another customer (403).

## US-5 API versions

As an API client, I want to choose the API version, so that a new version does not break me.

- The version comes from `X-API-Version`, the `api-version` query parameter or
  `Accept: application/vnd.shopflow.vN+json`; without one, version 2 applies.
- Version 1 answers carry `Deprecation` and `Sunset` headers.
- An unsupported version is 400.

## US-6 Daily sales report

As an administrator, I want orders, units and revenue per day, so that I can see how sales develop.

- `GET /api/admin/reports/daily-sales?from=&to=` (UTC days, both inclusive, default the last 30
  days) returns one row per day, zero rows for days without sales, and totals.
- Cancelled orders are not counted; orders waiting for payment are (open question whether only paid
  orders should count).
- Admins only (403 for customers); version 2 and later; `from` after `to` or more than 366 days is 400.

## US-7 Cancel unpaid orders automatically

As the shop, I want orders that are not paid in time to be cancelled, so that their stock is
available again.

- Orders still `PENDING_PAYMENT` after `shopflow.cancel-after` are cancelled by a scheduled job.
- Their stock reservation is released at inventory-service first.
- Paid orders are left alone; if inventory is unavailable the order stays unpaid and is retried
  on the next run.
- An `order.cancelled` event with reason `unpaid` is published.

## US-8 Pay for an order

As the shop, I want the payment provider's confirmation to mark an order as paid, so that it can
be shipped.

- A signed `payment.succeeded` webhook for the order's total sets it to `CONFIRMED` and publishes
  `order.confirmed`.
- The same provider event delivered again is answered `DUPLICATE` and changes nothing; a second
  payment for a confirmed order is `ALREADY_CONFIRMED`.
- A wrong amount is 422 and the order stays unpaid; a payment for a cancelled order is 409 (refund
  needed); `payment.failed` is acknowledged and ignored.
- A missing or wrong signature is 401.

## US-9 Cancel an order

As an administrator, I want to cancel an order, so that I can handle problems for customers.

- `PATCH /api/orders/{id}/status` with `CANCELLED` is allowed for admins only (403 for customers,
  even for their own order).
- An `order.cancelled` event with reason `cancelled on request` is published once, even if the
  order is cancelled again.

## US-10 Tell the customer about their order

As a customer, I want to be told when my order is placed, paid or cancelled.

- Every order event reaches the notification sender, with the event id as idempotency key.
- An event delivered twice is handled once.
- A failing notification is retried; after the last attempt the event is stored in `dead_letters`
  and the `shopflow.events.dead_letters` counter goes up, while other events keep flowing.

## US-11 Sign in and access rules

As the shop, I want only signed-in users to use the API, with roles deciding what they may do.

- Tokens come from Keycloak (password grant for users, client credentials for services) and are
  checked for signature, expiry and issuer.
- Roles come from the Keycloak realm roles; the caller is named after their Keycloak username.
- Customers work on their own orders; admins on all orders and the reports.
- Every endpoint except `/actuator/health` refuses a request without a token (the payment webhook
  instead requires its signature).
- The web front end's origins may call the API from a browser (CORS).
