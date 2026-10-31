CREATE TABLE warehouses (id varchar(36) PRIMARY KEY, name varchar(100) NOT NULL, city varchar(100) NOT NULL, priority integer NOT NULL);
CREATE TABLE stock_balances (
 warehouse_id varchar(36) REFERENCES warehouses(id), sku_id varchar(36) NOT NULL,
 on_hand integer NOT NULL DEFAULT 0 CHECK(on_hand>=0), reserved integer NOT NULL DEFAULT 0 CHECK(reserved>=0),
 damaged integer NOT NULL DEFAULT 0 CHECK(damaged>=0), version integer NOT NULL DEFAULT 0,
 PRIMARY KEY(warehouse_id,sku_id), CHECK(reserved<=on_hand)
);
CREATE TABLE stock_movements (
 id varchar(36) PRIMARY KEY, warehouse_id varchar(36) NOT NULL, sku_id varchar(36) NOT NULL,
 on_hand_delta integer NOT NULL, reserved_delta integer NOT NULL DEFAULT 0, damaged_delta integer NOT NULL DEFAULT 0,
 kind varchar(30) NOT NULL, reference varchar(100) NOT NULL, reason varchar(300) NOT NULL,
 actor varchar(100) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX movements_time ON stock_movements(created_at DESC);
CREATE TABLE reservations (
 order_id varchar(36) PRIMARY KEY, state varchar(20) NOT NULL, expires_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reservation_expiry ON reservations(expires_at) WHERE state='ACTIVE';
CREATE TABLE reservation_lines (
 order_id varchar(36) REFERENCES reservations(order_id), warehouse_id varchar(36) NOT NULL,
 sku_id varchar(36) NOT NULL, quantity integer NOT NULL CHECK(quantity>0), consumed boolean NOT NULL DEFAULT false,
 PRIMARY KEY(order_id,warehouse_id,sku_id)
);
CREATE TABLE inventory_operations (id varchar(100) PRIMARY KEY, request text NOT NULL);
CREATE TABLE stock_transfers (
 id varchar(36) PRIMARY KEY, source varchar(36) NOT NULL REFERENCES warehouses(id),
 destination varchar(36) NOT NULL REFERENCES warehouses(id), sku_id varchar(36) NOT NULL,
 quantity integer NOT NULL CHECK(quantity>0), state varchar(20) NOT NULL DEFAULT 'IN_TRANSIT',
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(source<>destination)
);
