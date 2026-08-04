CREATE TABLE sessions (
    id                TEXT PRIMARY KEY,
    repo_full_name    TEXT NOT NULL,
    task_markdown     TEXT NOT NULL,
    state             TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    created_by        TEXT NOT NULL,
    claimed_at        TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    pr_url            TEXT,
    failure_summary   TEXT
);

-- The claim query scans the oldest PENDING session; the lazy-expiry sweep scans
-- RUNNING sessions by heartbeat age. Index the state to keep both cheap.
CREATE INDEX sessions_state_created_at_idx ON sessions (state, created_at);

CREATE TABLE session_events (
    session_id TEXT NOT NULL REFERENCES sessions (id),
    seq        INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    kind       TEXT NOT NULL,
    message    TEXT NOT NULL,
    PRIMARY KEY (session_id, seq)
);
