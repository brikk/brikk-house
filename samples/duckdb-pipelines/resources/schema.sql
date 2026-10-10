-- One schema source for both compiler validation and the actual in-memory database.
CREATE SCHEMA sample;

CREATE TABLE sample.raw_events (
    event_id BIGINT NOT NULL,
    tenant VARCHAR NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    payload JSON
);

CREATE TABLE sample.customers (
    tenant VARCHAR NOT NULL,
    customer_id VARCHAR NOT NULL,
    segment VARCHAR
);
