import http from 'k6/http';
import { sleep } from 'k6';
import { assertOk, authHeaders, handleSummary as summary, login, optionalNumber } from './lib/common.js';

const PEAK_VUS = optionalNumber('DASHBOARD_PEAK_VUS', 100);
const P95_MS = optionalNumber('DASHBOARD_P95_MS', 1500);
const P99_MS = optionalNumber('DASHBOARD_P99_MS', 3000);

// Purpose: heaviest read path, used for bottleneck diagnosis. It starts lower because it performs
// multiple DB reads and has no cache. Duration stays under the 15-minute JWT TTL.
export const options = {
  setupTimeout: '30s',
  scenarios: {
    dashboard_bottleneck: {
      executor: 'ramping-vus',
      gracefulRampDown: '10s',
      stages: [
        { duration: '30s', target: Math.round(PEAK_VUS * 0.1) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.1) },
        { duration: '30s', target: Math.round(PEAK_VUS * 0.3) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.3) },
        { duration: '30s', target: Math.round(PEAK_VUS * 0.6) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.6) },
        { duration: '30s', target: PEAK_VUS },
        { duration: '1m', target: PEAK_VUS },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Dashboard is DB-heavy, so the gate focuses on error rate and p95/p99 diagnosis rather than max TPS.
    http_req_failed: ['rate<0.02'],
    'http_req_duration{endpoint:dashboard}': [`p(95)<${P95_MS}`, `p(99)<${P99_MS}`],
  },
};

export function setup() {
  return login();
}

export default function (data) {
  const res = http.get(`${data.baseUrl}/api/unified/dashboard`, {
    headers: authHeaders(data.token),
    tags: { endpoint: 'dashboard' },
  });
  assertOk(res, 'dashboard');
  sleep(1);
}

export function handleSummary(data) {
  return summary('dashboard-bottleneck')(data);
}
