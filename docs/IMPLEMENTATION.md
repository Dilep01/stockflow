# Implementation tracker

Build authorized: all phases, with one final review. Local-only integrations; no paid services.

- [x] Runnable backend, React frontend, database migrations and local infrastructure
- [x] Authentication, refresh sessions, authorization, staff assignments and catalog
- [x] Inventory ledger, concurrency controls, allocations and expiry
- [x] Durable order saga, cancellation and idempotency
- [x] Split shipments, returns and transfers
- [x] Redis caching, Kafka outbox/inbox, dead letters, replay and recovery
- [x] Independent service processes and database ownership (shared build artifact)
- [x] Automated PostgreSQL integration and two-instance HTTP concurrency/recovery validation
- [x] Final browser regression pass after loading-state fix
- [x] Monitoring, backup/restore and operational documentation
- [x] Final evidence report and review handoff

Each item is marked complete only with implementation and recorded evidence. Unavailable checks will be identified explicitly.

## Phase mapping

All planned implementation phases have corresponding working code and a learning path in LEARNING_GUIDE.md. Native and Docker builds use Java 21, Spring Boot 3.5.16, React/TypeScript, PostgreSQL, Redis and Kafka. JDBC replaces the proposed JPA layer so transaction and lock behavior remain explicit.

The completed scope is local and synthetic. A one-broker Kafka setup and one PostgreSQL host do not provide multi-host high availability. Service processes and databases are separate; release artifacts are shared. Optional k6 and hosted CI definitions are supplied but their execution is not claimed. The exact recorded test evidence is in VERIFICATION.md.
