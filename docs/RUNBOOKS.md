# Local runbooks

Run commands from the project root. These procedures operate on this project's Compose stack only.

## Startup troubleshooting

1. Confirm Docker Desktop is running with Linux containers.
2. Run `docker compose --profile distributed ps` and inspect the failed service with `docker compose logs --tail 100 SERVICE`.
3. PostgreSQL must be healthy before applications start. Kafka's initialization service assigns its named volume to the broker's unprivileged user; the broker health check gates distributed service startup.
4. Initial builds require internet downloads. Once images and dependencies are cached, normal startup needs no paid or external application service.
5. A changed `.env` database password does not change already-created PostgreSQL roles. Restore the original local setting or deliberately rotate role passwords through PostgreSQL administration.
6. The native development backend uses port 18080 to avoid a preexisting application on 8080. Compose exposes web ports 8088/8089, not service HTTP ports.

## Order stuck in a pending state

- Inspect the order timeline and the relevant service log using the order ID.
- Check `GET /api/ops/orders/status`, `/api/ops/inventory/status`, and `/api/ops/fulfillment/status` with an administrator token. The System screen shows inventory's outbox.
- Check service health and Kafka. Transient failures retry automatically. Active checkout reservations can expire; committed reservations remain held.
- A dead outbox record can be retried after fixing the cause: `POST /api/ops/SERVICE/events/EVENT_ID/retry`. This retains the original event ID.
- Do not manually increase stock or overwrite order states to hide a stuck saga. Reconcile the original command and its outcome first.

## Kafka dead letters

Each consumer target has `stockflow.TARGET.DLT`. Inspect a specific record with the Kafka console consumer inside the broker container, using a partition and offset. Record its event ID and diagnose the underlying schema, payload, or business-state issue.

Save the single JSON event to a local file, then use:

```powershell
./scripts/replay-event.ps1 -File artifacts/repaired-event.json -Target inventory
```

The script validates target, version, and identifiers and preserves the event ID. Successfully applied events will be ignored by the inbox. Failed events have no committed inbox entry and can be applied after repair. Do not assign a new ID to bypass deduplication. Keep the original record and your repair reason as operational evidence. Replaying an event may mutate inventory; use only a reviewed event from this local project.

## Database backup and isolated restore

```powershell
./scripts/backup.ps1 -Database stockflow
./scripts/restore-check.ps1 -Backup artifacts/backups/EXACT_FILE.sql
```

The restore creates a new timestamped `restore_check_...` database and never overwrites a live database. Inspect its tables and verify balances against stock movements before relying on it.

For a distributed snapshot, stop identity, catalog, inventory, orders, fulfillment, and notification first. Then back up each database using `backup.ps1`. Record Kafka consumer positions and keep broker data. Restoring databases without coordinating Kafka offsets can replay retained events; inbox deduplication protects already-recorded messages but is not a substitute for a consistent restore plan. This project verifies isolated logical restore, not point-in-time distributed disaster recovery.

## Reconciliation

`GET /api/inventory/reconciliation` should return `[]`. Any row means the materialized balance differs from the ledger. Stop stock mutations, inspect the corresponding movements, and correct the business operation using a compensating movement. Do not edit or delete ledger entries; a trigger rejects those actions.

## Scaling experiment

```powershell
docker compose --profile distributed up -d --no-build --scale inventory=2
```

Both instances share the inventory database and consumer group. Kafka partitions distribute work; PostgreSQL locks coordinate stock access. The local gateway may retain DNS answers until reload, so reload Nginx after topology changes. Scaling improves process concurrency, not availability of the single database/broker host.

## Monitoring

Enable the monitoring profile. Grafana's Stockflow dashboard shows outbox backlog, failures, request rate, and heap use. A growing pending count with a stopped broker is expected; verify it drains after recovery. Investigate dead events instead of suppressing the metric. Actuator metrics are reachable on the internal Compose network and are blocked at the public web gateway.

## Clean shutdown

Use `scripts/stop.ps1`. Volumes are retained. Native development processes run in their own terminals and should be stopped there. Do not terminate unrelated Java, Node, or Docker projects.
