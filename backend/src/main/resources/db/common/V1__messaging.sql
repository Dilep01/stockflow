CREATE TABLE outbox_events (
 id varchar(36) PRIMARY KEY, aggregate_id varchar(100) NOT NULL, target varchar(30) NOT NULL,
 kind varchar(80) NOT NULL, payload text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz, attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
 last_error varchar(500), dead boolean NOT NULL DEFAULT false
);
CREATE INDEX outbox_pending ON outbox_events(next_attempt,created_at) WHERE published_at IS NULL AND NOT dead;
CREATE TABLE processed_messages (event_id varchar(36) PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());
