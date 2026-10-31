CREATE TABLE fulfillment_orders (
 order_id varchar(36) PRIMARY KEY, customer_id varchar(36) NOT NULL, state varchar(40) NOT NULL,
 items text NOT NULL DEFAULT '[]'
);
CREATE TABLE shipments (
 id varchar(36) PRIMARY KEY, order_id varchar(36) REFERENCES fulfillment_orders(order_id), warehouse_id varchar(36) NOT NULL,
 state varchar(40) NOT NULL DEFAULT 'READY', tracking varchar(60) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(order_id,warehouse_id)
);
CREATE TABLE shipment_items (
 shipment_id varchar(36) REFERENCES shipments(id), sku_id varchar(36) NOT NULL, quantity integer NOT NULL CHECK(quantity>0),
 PRIMARY KEY(shipment_id,sku_id)
);
CREATE TABLE shipment_events (id bigserial PRIMARY KEY, shipment_id varchar(36) REFERENCES shipments(id),state varchar(40) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE return_requests (
 id varchar(36) PRIMARY KEY, shipment_id varchar(36) REFERENCES shipments(id), sku_id varchar(36) NOT NULL,
 customer_id varchar(36) NOT NULL, quantity integer NOT NULL CHECK(quantity>0), reason varchar(300) NOT NULL,
 state varchar(30) NOT NULL DEFAULT 'REQUESTED', disposition varchar(20), refund numeric(12,2),
 created_at timestamptz NOT NULL DEFAULT now()
);
