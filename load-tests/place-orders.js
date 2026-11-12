// Places orders at a constant 200 requests/second for 2 minutes.
//
//   k6 run load-tests/place-orders.js
//
// Without a local k6, use the container (Docker Desktop: the app is on the host):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load-tests/place-orders.js
//
// Settings (environment variables, defaults in brackets):
//   BASE_URL     where order-platform listens          [http://localhost:8080]
//   RATE         orders started per second             [200]
//   DURATION     how long to keep that rate            [2m]
//   API_VERSION  value of the X-API-Version header     [2]
//
// The test needs order-platform and the services behind it running: its database, and
// inventory-service on :8081 (or the WireMock stand-in, see deploy/wiremock/README.md).
// Every order reserves stock first, so with WireMock in `slow` or fault modes you will see 503s.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '2m';
const API_VERSION = __ENV.API_VERSION || '2';

// Outcomes the API documents for POST /api/orders, counted separately from "failures".
const created = new Counter('orders_created');
const noStock = new Counter('orders_rejected_insufficient_stock'); // 409
const inventoryDown = new Counter('orders_rejected_inventory_unavailable'); // 503
const unexpected = new Counter('orders_unexpected_status');

export const options = {
  scenarios: {
    place_orders: {
      // Open model: new orders start at a fixed rate whatever the response times are, so a slow
      // server shows up as latency and dropped iterations instead of silently lowering the load.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.ceil(RATE / 2),
      maxVUs: RATE * 5,
    },
  },
  thresholds: {
    // Only 201, 409 and 503 are expected answers; anything else is a bug.
    orders_unexpected_status: ['count==0'],
    // If k6 had to skip iterations, the target rate was not reached.
    dropped_iterations: ['count==0'],
    // Latency of successful placements (they include the stock reservation call).
    'http_req_duration{expected_response:true}': ['p(95)<500', 'p(99)<1000'],
    // At least 99% of orders are created.
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const SKUS = ['SKU-001', 'SKU-002', 'SKU-003', 'SKU-004', 'SKU-005', 'SKU-006', 'SKU-007', 'SKU-008'];

// A random id for the X-Correlation-Id header (the app accepts letters, digits, '.', '_' and '-').
function correlationId() {
  return `k6-${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 10)}`;
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function orderBody() {
  const lineCount = randomInt(1, 3);
  const lines = [];
  for (let i = 0; i < lineCount; i++) {
    lines.push({
      sku: SKUS[randomInt(0, SKUS.length - 1)],
      quantity: randomInt(1, 5),
      unitPrice: randomInt(100, 5000) / 100,
    });
  }
  return JSON.stringify({ customerId: `load-${randomInt(1, 10000)}`, lines });
}

export function setup() {
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    fail(`order-platform is not healthy at ${BASE_URL} (GET /actuator/health answered ${health.status})`);
  }
}

export default function () {
  const res = http.post(`${BASE_URL}/api/orders`, orderBody(), {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Version': API_VERSION,
      'X-Correlation-Id': correlationId(),
    },
    tags: { name: 'POST /api/orders' },
    // 409 and 503 are documented answers, so they must not count as failed requests.
    responseCallback: http.expectedStatuses(201, 409, 503),
  });

  if (res.status === 201) {
    created.add(1);
  } else if (res.status === 409) {
    noStock.add(1);
  } else if (res.status === 503) {
    inventoryDown.add(1);
  } else {
    unexpected.add(1);
  }

  check(res, {
    'order created (201)': (r) => r.status === 201,
    'has Location header': (r) => r.status !== 201 || !!r.headers['Location'],
    'status is PENDING_PAYMENT': (r) => r.status !== 201 || r.json('status') === 'PENDING_PAYMENT',
  });
}
