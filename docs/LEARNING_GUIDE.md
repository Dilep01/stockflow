# Beginner-to-advanced reading path

The complete application is provided together. Use this order to understand it without first reading every file.

| Phase | Read / run | What to understand |
|---|---|---|
| 0 — tools | README, setup/start scripts | JDK, Maven, npm, containers, environment variables |
| 1 — skeleton | Application.java, application.yml, Flyway migrations | Dependency injection, HTTP, connection pools, versioned SQL |
| 2 — auth/catalog | Identity.java, Security.java, Catalog.java | BCrypt, signed JWT, validation, HTTP status codes, SQL CRUD |
| 3 — stock | Inventory.java, inventory migrations | Quantities, ledger, constraints, transaction boundaries |
| 4 — orders | Orders.java, checkout tests | Price snapshots, idempotency keys, row/advisory locks |
| 5 — fulfillment | Fulfillment.java, shipment UI | State transitions, split shipment aggregation, dispatch races |
| 6 — reverse logistics | Return and transfer methods | Quantity allowance, damaged goods, idempotent restocking |
| 7 — caching | Catalog.java, Redis outage system test | TTL, invalidation, authoritative reads, graceful fallback |
| 8 — messaging | Events, EventWorker, Dispatcher, KafkaConfig | Outbox, inbox, redelivery, retry, dead letters |
| 9 — services | compose.yaml, role conditions, database owners | Process boundaries, logical data ownership, HTTP vs events |
| 10 — recovery | tests/system.mjs and PlatformTest.java | Concurrency, outage recovery, eventual consistency |
| 11 — operations | monitoring configuration, runbooks | Metrics, backup/restore, reproducible evidence |

## Follow one order

Start at React's checkout function. It sends SKU IDs and quantities, never trusted prices. Follow `Orders.checkout`, its transaction, the outbox row, `EventWorker.publish`, `Dispatcher.deliver`, and `Inventory.reserve`. Then trace commitment and fulfillment creation. Inspect rows through a read-only database client while comparing the order timeline in the UI.

## Experiments

1. Place an order requiring two warehouses; inspect reservation lines and separate shipments.
2. Change inventory while a reservation exists; observe that reserved units cannot be removed by an adjustment.
3. Reuse a checkout key with the same payload, then with a different payload.
4. Run the concurrency test; replace the conditional update with an unsafe read/write only in a disposable branch to understand why the guard matters.
5. Stop Redis and load the catalog. Stop Kafka, submit an order, inspect pending events, and restart it.
6. Inspect a return as DAMAGED and verify that available units do not increase.
7. Restore a backup into a separate database and compare ledger totals.

## Interview explanations grounded in this implementation

- Why PostgreSQL locks instead of Java `synchronized`? Multiple processes must coordinate through shared durable state.
- Why both outbox and inbox? The outbox prevents losing committed intent; the inbox prevents duplicate effects after redelivery.
- Why not release stock when the order is confirmed? Those units still need to remain unavailable until shipment or cancellation.
- Why does dispatch wait for inventory? Fulfillment must not claim completed stock consumption before inventory acknowledges it.
- Why separate damaged stock? Physical possession is not equivalent to sellable availability.
- What is not proven? Multi-host availability, real carrier/payment behavior, and production-scale performance.

The implementation intentionally uses explicit SQL. Learn transactions and constraints before abstracting these operations behind an ORM. The shared image is a deliberate local-build simplification, documented rather than presented as independent service release management.
