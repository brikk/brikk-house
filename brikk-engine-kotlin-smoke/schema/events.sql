-- Schema cache for the smoke module: plain DDL, as if introspected from the target database.
CREATE TABLE public.events (
  event_id BIGINT NOT NULL,
  event_at TIMESTAMPTZ NOT NULL,
  tenant TEXT NOT NULL,
  payload JSONB
);
