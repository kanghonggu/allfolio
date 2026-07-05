# Allfolio k6 Load Tests

Render deployment smoke/load scripts for the three read paths, in the recommended order:

1. `allocation-max-tps.js` - `GET /api/reports/allocation`
   - Max TPS probe.
   - Expected backend path: one DB read from `ua_assets`, no Redis, no snapshot, no external API.
2. `positions-cache.js` - `GET /api/portfolios/{id}/positions`
   - Redis cache path probe.
   - Expected backend path: Redis `HGETALL`, DB 0 reads.
3. `dashboard-bottleneck.js` - `GET /api/unified/dashboard`
   - Bottleneck diagnosis.
   - Expected backend path: multiple DB reads, no cache.

All scripts log in once in `setup()` through `POST /api/auth/login`, extract `accessToken`, and reuse the token from every VU through `Authorization: Bearer ...`. The default scenarios finish under the 15-minute access-token TTL. For tests longer than 15 minutes, use a separate long-run script with token refresh logic instead of extending these scenarios directly.

## Required Environment Variables

Install k6 before running real tests:

```bash
brew install k6
# or see https://grafana.com/docs/k6/latest/set-up/install-k6/
```

```bash
export BASE_URL="https://allfolio.onrender.com"
export TEST_USER_EMAIL="load-test@example.com"
export TEST_USER_PASSWORD="..."
export PORTFOLIO_ID="00000000-0000-0000-0000-000000000000" # positions-cache.js only
```

Optional knobs:

```bash
export SUMMARY_DIR="k6/results"
export ALLOCATION_PEAK_VUS=800
export POSITIONS_PEAK_VUS=250
export DASHBOARD_PEAK_VUS=100
export ALLOCATION_P95_MS=300
export ALLOCATION_P99_MS=800
export POSITIONS_P95_MS=500
export POSITIONS_P99_MS=1200
export DASHBOARD_P95_MS=1500
export DASHBOARD_P99_MS=3000
```

## Run Order

Run from the repository root so the default summary directory exists.

```bash
k6 run k6/allocation-max-tps.js --summary-export k6/results/allocation-max-tps-raw.json
k6 run k6/positions-cache.js --summary-export k6/results/positions-cache-raw.json
k6 run k6/dashboard-bottleneck.js --summary-export k6/results/dashboard-bottleneck-raw.json
```

Each script also writes a JSON summary through `handleSummary()`:

- `k6/results/allocation-max-tps-summary.json`
- `k6/results/positions-cache-summary.json`
- `k6/results/dashboard-bottleneck-summary.json`

## Thresholds

Thresholds intentionally differ by endpoint cost:

| Script | Failure rate | p95 | p99 | Reason |
| --- | ---: | ---: | ---: | --- |
| `allocation-max-tps.js` | `<1%` | `<300ms` | `<800ms` | Lightest DB-only path for max TPS claim validation. |
| `positions-cache.js` | `<1%` | `<500ms` | `<1200ms` | Redis path should be stable but may include network variance. |
| `dashboard-bottleneck.js` | `<2%` | `<1500ms` | `<3000ms` | DB-heavy diagnostic endpoint starts with a looser gate. |

For README performance numbers, capture:

- `http_reqs` rate: sustained request throughput.
- `http_req_failed`: error rate.
- `http_req_duration` p95/p99: user-visible latency.
- Threshold pass/fail: whether the run met the endpoint-specific gate.

## Local Validation

This validates script structure without hitting any backend:

```bash
node k6/validate-scripts.mjs
```

If k6 is installed, inspect scripts without running load:

```bash
k6 inspect k6/allocation-max-tps.js
k6 inspect k6/positions-cache.js
k6 inspect k6/dashboard-bottleneck.js
```

Do not run real load against Render until the deployment and test account are ready.
