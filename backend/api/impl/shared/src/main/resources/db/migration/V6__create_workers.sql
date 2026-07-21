-- Worker fleet registry (M5). Each row is one worker process/host, keyed by a
-- stable worker_id it chooses; a worker upserts its row periodically so the
-- control plane always knows which workers are alive and what build each runs.
-- Liveness is `last_seen_at` freshness (the system-test gate's preflight); the
-- version columns surface skew (story 04). This is intentionally decoupled from
-- session claiming — a worker mid-run still refreshes its registration, so a
-- long session never reads as "worker down".
CREATE TABLE workers (
    worker_id         TEXT PRIMARY KEY,
    -- Best-effort build version; empty until story 04 wires the real stamp.
    worker_version    TEXT NOT NULL DEFAULT '',
    -- Container image digest when containerized; empty otherwise.
    image_digest      TEXT NOT NULL DEFAULT '',
    -- Comma-separated engine capability list (DB values: BUILTIN,CLAUDE); empty
    -- string = a pre-M4 "claim any" worker.
    supported_engines TEXT NOT NULL DEFAULT '',
    first_seen_at     TIMESTAMPTZ NOT NULL,
    last_seen_at      TIMESTAMPTZ NOT NULL
);

-- The liveness read orders workers by last-seen; index it so the freshest
-- worker is cheap to find.
CREATE INDEX workers_last_seen_at_idx ON workers (last_seen_at);
