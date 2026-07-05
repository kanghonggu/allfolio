import http from 'k6/http';
import { check, fail } from 'k6';

const DEFAULT_SUMMARY_DIR = 'k6/results';

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value || value.trim() === '') {
    fail(`Missing required environment variable: ${name}`);
  }
  return value.trim();
}

export function optionalNumber(name, fallback) {
  const raw = __ENV[name];
  if (!raw || raw.trim() === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    fail(`${name} must be a positive number`);
  }
  return value;
}

export function baseUrl() {
  return requireEnv('BASE_URL').replace(/\/+$/, '');
}

export function login() {
  const url = `${baseUrl()}/api/auth/login`;
  const payload = JSON.stringify({
    email: requireEnv('TEST_USER_EMAIL'),
    password: requireEnv('TEST_USER_PASSWORD'),
  });
  const params = {
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    tags: { endpoint: 'auth-login' },
  };

  const res = http.post(url, payload, params);
  let accessToken = '';
  let expiresIn = 0;
  try {
    accessToken = res.json('accessToken') || '';
    expiresIn = Number(res.json('expiresIn') || 0);
  } catch (e) {
    accessToken = '';
  }

  const ok = check(res, {
    'login status is 200': (r) => r.status === 200,
    'login returns accessToken': () => Boolean(accessToken),
  });
  if (!ok) {
    fail(`Login failed: status=${res.status} body=${res.body}`);
  }

  return {
    baseUrl: baseUrl(),
    token: accessToken,
    expiresIn,
  };
}

export function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
}

export function assertOk(res, label) {
  check(res, {
    [`${label} status is 200`]: (r) => r.status === 200,
  });
}

export function handleSummary(scriptName) {
  return function handleSummary(data) {
    const metrics = data.metrics || {};
    const reqs = metrics.http_reqs?.rate || 0;
    const failedRate = metrics.http_req_failed?.rate || 0;
    const duration = metrics.http_req_duration?.percentiles || {};
    const lines = [
      `k6 summary: ${scriptName}`,
      `http_reqs/s: ${reqs.toFixed(2)}`,
      `http_req_failed: ${(failedRate * 100).toFixed(2)}%`,
      `http_req_duration p95: ${(duration['95'] || 0).toFixed(2)}ms`,
      `http_req_duration p99: ${(duration['99'] || 0).toFixed(2)}ms`,
      '',
      'Full JSON summary written by handleSummary().',
      'You can also pass --summary-export for an additional raw k6 export.',
      '',
    ].join('\n');

    const dir = (__ENV.SUMMARY_DIR || DEFAULT_SUMMARY_DIR).replace(/\/+$/, '');
    return {
      stdout: lines,
      [`${dir}/${scriptName}-summary.json`]: JSON.stringify(data, null, 2),
    };
  };
}
