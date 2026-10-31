# Verification record

Verified locally on 28–29 October 2026. These are synthetic development tests, not production certification.

| Check | Result |
|---|---|
| Spring Boot 3.5.16 / Java 21 backend integration tests | 31 passed, zero failures/errors |
| React API-client tests | 4 passed |
| Final distributed Playwright tests | 3 passed, zero failures |
| HTTP system scenarios, two inventory processes | 8 passed |
| Dead-letter / replay checks | 4 passed |
| Frontend production build | Passed locally and in Docker |
| Backend Docker source build | Passed; source-check image built from Maven sources |
| Lite Docker startup | Healthy; web endpoint returned HTTP 200 |
| npm dependency audit | Zero reported vulnerabilities in the recorded audit |
| Database backup / isolated restore | Restored successfully; restored ledger discrepancy count 0 |
| Monitoring | Six distributed service targets scraped; Grafana database health OK |

## Concurrent checkout measurement

The test submitted 100 requests concurrently against a fresh product with 10 available units. Exactly 10 orders reached fulfillment and 90 were rejected for insufficient stock. No negative stock or over-reservation was observed. Two inventory JVM processes shared one inventory database and Kafka group.

- HTTP acceptance latency p50: 14388 ms.
- HTTP acceptance latency p95: 15345 ms.
- HTTP acceptance latency p99: 15427 ms.
- Time for all 100 business outcomes: 26682 ms.

Hardware observed: Windows host, approximately 15.3 GiB physical RAM, 12 logical processors; Docker VM approximately 7.4 GiB. Other local containers and a source build were active. These measurements show this run's behavior and are not a capacity benchmark or performance promise.

## Recovery evidence

- 100 competing checkouts allocate exactly 10 units.
- cancellation releases stock and duplicate checkout returns the same order.
- dispatch, delivery, return inspection, and restock converge.
- transfer receipt is idempotent.
- Redis outage falls back to PostgreSQL.
- inventory service restart recovers queued order.
- Kafka outage preserves order in outbox and resumes.
- inventory ledger reconciles after all workflows.

- invalid event reaches DLT.
- failed transaction does not commit inbox.
- repaired same-ID replay applies once.
- duplicate replay leaves one inbox entry.

## Browser checks

The browser suite verifies admin navigation and stock adjustment, customer checkout and order history, invalid-login feedback, and a 390 px mobile viewport without document-wide horizontal overflow. Desktop/mobile screenshots were visually inspected. A race that opened the stock dialog before product loading completed was found and fixed before the final pass.

## Evidence files

- `backend/target/surefire-reports/`: backend JUnit output (generated, ignored by Git).
- `artifacts/system-results.json`: HTTP scenarios and measured latency.
- `artifacts/dead-letter-results.json`: failed-message recovery checks.
- `artifacts/browser-results.json`: final Playwright report.
- `artifacts/npm-audit.json`: recorded npm audit.
- `artifacts/restore-check.log`: isolated database restore log.
- `artifacts/metrics-check.json`, `artifacts/grafana-health.json`: monitoring evidence.
- `docs/images/`: review screenshots.

The optional k6 workload and GitHub Actions workflow are supplied. k6 was not executed, and no hosted GitHub Actions run was created. No public deployment, real courier, real payment, multi-host failover, or penetration test was performed. See [limitations](LIMITATIONS.md).
