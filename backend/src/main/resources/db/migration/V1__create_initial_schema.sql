-- V1__create_initial_schema.sql
-- SmartExtract initial database schema

CREATE TABLE users (
    id            BIGSERIAL       PRIMARY KEY,
    name          VARCHAR(100)    NOT NULL,
    email         VARCHAR(255)    NOT NULL UNIQUE,
    password_hash VARCHAR(255)    NOT NULL,
    created_at    TIMESTAMP       NOT NULL
);

CREATE TABLE documents (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES users(id),
    file_name   VARCHAR(255)    NOT NULL,
    file_type   VARCHAR(50)     NOT NULL,
    status      VARCHAR(30)     NOT NULL,
    uploaded_at TIMESTAMP       NOT NULL
);

CREATE TABLE purchase_orders (
    id             BIGSERIAL        PRIMARY KEY,
    user_id        BIGINT           NOT NULL REFERENCES users(id),
    document_id    BIGINT           NOT NULL UNIQUE REFERENCES documents(id),
    po_number      VARCHAR(100),
    supplier       VARCHAR(255),
    order_date     DATE,
    delivery_date  DATE,
    payment_terms  VARCHAR(100),
    currency       VARCHAR(10),
    subtotal       DECIMAL(15, 2),
    tax            DECIMAL(15, 2),
    total          DECIMAL(15, 2),
    created_at     TIMESTAMP        NOT NULL
);

CREATE TABLE purchase_order_items (
    id                 BIGSERIAL       PRIMARY KEY,
    purchase_order_id  BIGINT          NOT NULL REFERENCES purchase_orders(id),
    description        VARCHAR(500),
    quantity           DECIMAL(12, 2),
    unit_price         DECIMAL(15, 2),
    total_price        DECIMAL(15, 2)
);

