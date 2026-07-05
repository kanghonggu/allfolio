import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const k6Dir = path.join(root, 'k6');

const scripts = [
  {
    file: 'allocation-max-tps.js',
    endpoint: '/api/reports/allocation',
    env: ['BASE_URL', 'TEST_USER_EMAIL', 'TEST_USER_PASSWORD'],
    tag: 'allocation',
  },
  {
    file: 'positions-cache.js',
    endpoint: '/api/portfolios/${data.portfolioId}/positions',
    env: ['BASE_URL', 'TEST_USER_EMAIL', 'TEST_USER_PASSWORD', 'PORTFOLIO_ID'],
    tag: 'positions',
  },
  {
    file: 'dashboard-bottleneck.js',
    endpoint: '/api/unified/dashboard',
    env: ['BASE_URL', 'TEST_USER_EMAIL', 'TEST_USER_PASSWORD'],
    tag: 'dashboard',
  },
];

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function read(relativePath) {
  const absolutePath = path.join(k6Dir, relativePath);
  assert(fs.existsSync(absolutePath), `Missing ${relativePath}`);
  return fs.readFileSync(absolutePath, 'utf8');
}

const common = read('lib/common.js');
assert(common.includes('/api/auth/login'), 'common.js must login through /api/auth/login');
assert(common.includes('accessToken'), 'common.js must extract accessToken');
assert(common.includes('__ENV'), 'common.js must read configuration from __ENV');
assert(common.includes('handleSummary'), 'common.js must provide summary export helper');

for (const script of scripts) {
  const source = read(script.file);

  assert(source.includes('export const options'), `${script.file} must export k6 options`);
  assert(source.includes('scenarios'), `${script.file} must define scenarios`);
  assert(source.includes('thresholds'), `${script.file} must define thresholds`);
  assert(source.includes('http_req_failed'), `${script.file} must threshold http_req_failed`);
  assert(source.includes('p(95)<'), `${script.file} must threshold p95 latency`);
  assert(source.includes('p(99)<'), `${script.file} must threshold p99 latency`);
  assert(source.includes('export function setup()'), `${script.file} must login in setup()`);
  assert(source.includes('export function handleSummary(data)'), `${script.file} must export handleSummary()`);
  assert(source.includes(script.endpoint), `${script.file} must hit ${script.endpoint}`);
  assert(source.includes(`endpoint: '${script.tag}'`), `${script.file} must tag requests as ${script.tag}`);
  assert(
    source.includes('authHeaders(data.token)') && common.includes('Authorization'),
    `${script.file} must send Bearer auth`
  );

  for (const envName of script.env) {
    assert(source.includes(envName) || common.includes(envName), `${script.file} must use ${envName}`);
  }
}

const readme = read('README.md');
for (const script of scripts) {
  assert(readme.includes(script.file), `README.md must document ${script.file}`);
}
assert(readme.includes('BASE_URL'), 'README.md must document BASE_URL');
assert(readme.includes('--summary-export'), 'README.md must mention --summary-export alternative');

console.log(`Validated ${scripts.length} k6 scripts.`);
