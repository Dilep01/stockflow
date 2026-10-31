# Scope and limits

This is a local educational/portfolio implementation, not a validated production warehouse system.

- One business, INR currency, integer unit quantities, and synthetic data. No tax, discounts, accounting, purchase orders, lot/serial tracking, expiry-dated products, or supplier integrations.
- Shipping status and refunds are simulated. Mailpit captures mail locally. SMTP is at-least-once: a crash after sending but before marking sent can duplicate a demo email.
- Kafka has one broker. PostgreSQL is one server. Container restarts test process recovery, not host or region failure. Backups require a coordinated pause for a consistent distributed snapshot.
- One codebase/image selected by service role. Services can run and scale separately but are released together. There is no service mesh, Kubernetes, or independent release pipeline.
- JWT uses a shared signing secret. Broker access is trusted inside the local Docker network. Ports bind to loopback. Do not expose this setup directly to the internet.
- Product-list cache may be stale for up to 30 seconds. Checkout uses authoritative prices and stock.
- Reservation allocation takes pessimistic row locks across candidate warehouse balances for the requested SKUs. It favors correctness and understandable behavior over extreme throughput.
- A saga blocked by invalid data can require operator diagnosis and replay. There is no automatic repair of arbitrary corrupt state. Outbox retries are bounded; a prolonged outage can require the retry endpoint.
- The UI shows up to the latest 200 orders/movements/shipments/returns/transfers. This is a bounded demo list, not full server-side paginated search.
- Operator assignment changes affect newly issued tokens; existing tokens retain prior claims until expiry. There is no access-token denylist.
- Customer addresses and emails are stored locally for the demo. There is no regulatory certification, penetration test, or real customer data validation.
- Automated load measurements are synthetic tests on one developer machine. They are not production capacity or uptime guarantees.

Before real deployment: replace local security assumptions, add backup retention and restore monitoring, define service SLOs, perform threat modeling and load testing on intended infrastructure, add operational pagination, and complete business-specific warehouse rules with domain users.
