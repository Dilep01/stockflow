import fs from 'node:fs';
import assert from 'node:assert/strict';
const system=JSON.parse(fs.readFileSync('artifacts/system-results.json','utf8'));
const browser=JSON.parse(fs.readFileSync('artifacts/browser-results.json','utf8'));
const dead=JSON.parse(fs.readFileSync('artifacts/dead-letter-results.json','utf8'));
const audit=JSON.parse(fs.readFileSync('artifacts/npm-audit.json','utf8'));
const backend=fs.readFileSync('backend/target/surefire-reports/dev.stockflow.PlatformTest.txt','utf8').match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
assert(backend&&backend[2]==='0'&&backend[3]==='0');assert.equal(browser.stats.unexpected,0);assert.equal(dead.passed,true);assert(system.results.every(r=>r.passed!==false));assert.equal(audit.metadata.vulnerabilities.total,0);
const measurements=system.results.find(r=>r.name==='checkout measurements');
const report=`# Verification record

Verified locally on 28–29 October 2026. These are synthetic development tests, not production certification.

| Check | Result |
|---|---|
| Spring Boot 3.5.16 / Java 21 backend integration tests | ${backend[1]} passed, zero failures/errors |
| React API-client tests | 4 passed |
| Final distributed Playwright tests | ${browser.stats.expected} passed, zero failures |
| HTTP system scenarios, two inventory processes | ${system.results.filter(r=>r.passed).length} passed |
| Dead-letter / replay checks | ${dead.checks.length} passed |
| Frontend production build | Passed locally and in Docker |
| Backend Docker source build | Passed; source-check image built from Maven sources |
| Lite Docker startup | Healthy; web endpoint returned HTTP 200 |
| npm dependency audit | Zero reported vulnerabilities in the recorded audit |
| Database backup / isolated restore | Restored successfully; restored ledger discrepancy count 0 |
| Monitoring | Six distributed service targets scraped; Grafana database health OK |

## Concurrent checkout measurement

The test submitted ${measurements.requests} requests concurrently against a fresh product with 10 available units. Exactly 10 orders reached fulfillment and 90 were rejected for insufficient stock. No negative stock or over-reservation was observed. Two inventory JVM processes shared one inventory database and Kafka group.

- HTTP acceptance latency p50: ${measurements.p50Ms} ms.
- HTTP acceptance latency p95: ${measurements.p95Ms} ms.
- HTTP acceptance latency p99: ${measurements.p99Ms} ms.
- Time for all 100 business outcomes: ${measurements.completionMs} ms.

Hardware observed: Windows host, approximately 15.3 GiB physical RAM, 12 logical processors; Docker VM approximately 7.4 GiB. Other local containers and a source build were active. These measurements show this run's behavior and are not a capacity benchmark or performance promise.

## Recovery evidence

${system.results.filter(r=>r.passed).map(r=>'- '+r.name+'.').join('\n')}

${dead.checks.map(c=>'- '+c+'.').join('\n')}

## Browser checks

The browser suite verifies admin navigation and stock adjustment, customer checkout and order history, invalid-login feedback, and a 390 px mobile viewport without document-wide horizontal overflow. Desktop/mobile screenshots were visually inspected. A race that opened the stock dialog before product loading completed was found and fixed before the final pass.

## Evidence files

- \`backend/target/surefire-reports/\`: backend JUnit output (generated, ignored by Git).
- \`artifacts/system-results.json\`: HTTP scenarios and measured latency.
- \`artifacts/dead-letter-results.json\`: failed-message recovery checks.
- \`artifacts/browser-results.json\`: final Playwright report.
- \`artifacts/npm-audit.json\`: recorded npm audit.
- \`artifacts/restore-check.log\`: isolated database restore log.
- \`artifacts/metrics-check.json\`, \`artifacts/grafana-health.json\`: monitoring evidence.
- \`docs/images/\`: review screenshots.

The optional k6 workload and GitHub Actions workflow are supplied. k6 was not executed, and no hosted GitHub Actions run was created. No public deployment, real courier, real payment, multi-host failover, or penetration test was performed. See [limitations](LIMITATIONS.md).
`;
fs.writeFileSync('docs/VERIFICATION.md',report);
fs.mkdirSync('docs/evidence',{recursive:true});for(const name of ['system-results.json','dead-letter-results.json','npm-audit.json','grafana-health.json'])fs.copyFileSync('artifacts/'+name,'docs/evidence/'+name);
console.log('Verification report written from passing test evidence');
