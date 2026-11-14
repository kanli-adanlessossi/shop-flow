# Keycloak realm: DEVELOPMENT AND TESTS ONLY

`shopflow-realm.json` is imported by `order-platform/compose.yaml` (local runs) and by the Keycloak
test container. It is deliberately insecure and must never be imported into a shared or production
Keycloak:

| Setting | Value here | Why it is unsafe |
|---|---|---|
| `sslRequired` | `none` | tokens and passwords travel over plain HTTP |
| users `alice` / `bob` | passwords `alice-pass` / `bob-pass` | known, committed passwords; `bob` is an admin |
| client `shopflow-service` | secret `shopflow-service-secret` | committed client secret with the admin role |
| client `shopflow-web` | `directAccessGrantsEnabled: true` | password grant, kept for tools and tests |
| client `shopflow-web` | `redirectUris: http://localhost:*`, `webOrigins: +` | any local port is accepted |

The same applies to `order-platform/compose.yaml`: Keycloak admin `admin`/`admin` and the Postgres
password `shopflow`.

For any other environment create the realm separately: HTTPS required, no password grant, exact
redirect URIs and web origins, generated client secrets and user credentials supplied from the
environment or a secret store (for example Keycloak's `KC_BOOTSTRAP_ADMIN_PASSWORD` from a secret,
client secrets via the admin API or a vault), never from this file.
