import http from 'k6/http';
import { sleep } from 'k6';
import { assertOk, authHeaders, handleSummary as summary, login, optionalNumber } from './lib/common.js';

const PEAK_VUS = optionalNumber('ALLOCATION_PEAK_VUS', 800);
const P95_MS = optionalNumber('ALLOCATION_P95_MS', 300);
const P99_MS = optionalNumber('ALLOCATION_P99_MS', 800);

// Purpose: lightest read path, used to validate the 3000+ TPS claim.
// Duration stays under the 15-minute JWT TTL, so setup() logs in once and every VU reuses that token.
export const options = {
  setupTimeout: '30s',
  scenarios: {
    allocation_max_tps: {
      executor: 'ramping-vus',
      gracefulRampDown: '10s',
      stages: [
        { duration: '30s', target: Math.round(PEAK_VUS * 0.1) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.1) },
        { duration: '30s', target: Math.round(PEAK_VUS * 0.3) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.3) },
        { duration: '30s', target: Math.round(PEAK_VUS * 0.65) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.65) },
        { duration: '30s', target: PEAK_VUS },
        { duration: '1m', target: PEAK_VUS },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Allocation should be the cleanest max-TPS path: DB ua_assets read only, no cache/snapshot/external call.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:allocation}': [`p(95)<${P95_MS}`, `p(99)<${P99_MS}`],
  },
};

export function setup() {
  return login();
}

export default function (data) {
  const res = http.get(`${data.baseUrl}/api/reports/allocation`, {
    headers: authHeaders(data.token),
    tags: { endpoint: 'allocation' },
  });
  assertOk(res, 'allocation');
  sleep(0);
}

export function handleSummary(data) {
  return summary('allocation-max-tps')(data);
}
