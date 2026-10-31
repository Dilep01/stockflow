CREATE TABLE orders (
 id varchar(36) PRIMARY KEY, customer_id varchar(36) NOT NULL, customer_email varchar(254) NOT NULL,
 state varchar(40) NOT NULL, previous_state varchar(40), total numeric(12,2) NOT NULL,
 currency varchar(3) NOT NULL DEFAULT 'INR', address varchar(500) NOT NULL, reason varchar(500),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX orders_customer ON orders(customer_id,created_at DESC);
CREATE TABLE order_items (
 order_id varchar(36) REFERENCES orders(id), sku_id varchar(36) NOT NULL, name varchar(150) NOT NULL,
 price numeric(12,2) NOT NULL CHECK(price>0), quantity integer NOT NULL CHECK(quantity>0),
 PRIMARY KEY(order_id,sku_id)
);
CREATE TABLE checkout_keys (
 customer_id varchar(36) NOT NULL, key varchar(100) NOT NULL, request text NOT NULL,
 order_id varchar(36) NOT NULL REFERENCES orders(id), PRIMARY KEY(customer_id,key)
);
CREATE TABLE order_history (id bigserial PRIMARY KEY,order_id varchar(36) REFERENCES orders(id),state varchar(40) NOT NULL,note varchar(500) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
