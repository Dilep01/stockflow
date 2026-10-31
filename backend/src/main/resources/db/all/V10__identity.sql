CREATE TABLE users (
 id varchar(36) PRIMARY KEY, email varchar(254) UNIQUE NOT NULL, name varchar(100) NOT NULL,
 password_hash varchar(100) NOT NULL, role varchar(20) NOT NULL CHECK(role IN ('ADMIN','OPERATOR','CUSTOMER')),
 warehouse varchar(36), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE refresh_sessions (
 token_hash varchar(64) PRIMARY KEY, user_id varchar(36) NOT NULL REFERENCES users(id),
 expires_at timestamptz NOT NULL, revoked boolean NOT NULL DEFAULT false
);
