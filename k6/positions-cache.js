import http from 'k6/http';
import { sleep } from 'k6';
import {
  SUMMARY_TREND_STATS,
  assertOk, authHeaders, handleSummary as summary, login, optionalNumber, requireEnv,
} from './lib/common.js';

const PEAK_VUS = optionalNumber('POSITIONS_PEAK_VUS', 250);
const P95_MS = optionalNumber('POSITIONS_P95_MS', 500);
const P99_MS = optionalNumber('POSITIONS_P99_MS', 1200);

// Purpose: Redis cache read path. Expected backend work is Redis HGETALL, DB 0 reads.
// Duration stays under the 15-minute JWT TTL, so setup() logs in once and every VU reuses that token.
export const options = {
  setupTimeout: '30s',
  summaryTrendStats: SUMMARY_TREND_STATS,
  scenarios: {
    positions_cache: {
      executor: 'ramping-vus',
      gracefulRampDown: '10s',
      stages: [
        { duration: '30s', target: Math.round(PEAK_VUS * 0.2) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.2) },
        { duration: '30s', target: Math.round(PEAK_VUS * 0.6) },
        { duration: '1m', target: Math.round(PEAK_VUS * 0.6) },
        { duration: '30s', target: PEAK_VUS },
        { duration: '1m', target: PEAK_VUS },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Cache path should be very stable, but allows more network variance than allocation.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:positions}': [`p(95)<${P95_MS}`, `p(99)<${P99_MS}`],
  },
};

export function setup() {
  return {
    ...login(),
    portfolioId: requireEnv('PORTFOLIO_ID'),
  };
}

export default function (data) {
  const res = http.get(`${data.baseUrl}/api/portfolios/${data.portfolioId}/positions`, {
    headers: authHeaders(data.token),
    tags: { endpoint: 'positions' },
  });
  assertOk(res, 'positions');
  sleep(0.1);
}

export function handleSummary(data) {
  return summary('positions-cache')(data);
}
