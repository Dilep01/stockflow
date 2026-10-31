CREATE TABLE products (
 id varchar(36) PRIMARY KEY, sku varchar(50) UNIQUE NOT NULL, name varchar(150) NOT NULL,
 description text NOT NULL, price numeric(12,2) NOT NULL CHECK(price>0), category varchar(50) NOT NULL,
 active boolean NOT NULL DEFAULT true, version integer NOT NULL DEFAULT 0
);
