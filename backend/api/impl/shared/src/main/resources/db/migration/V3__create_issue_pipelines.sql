-- M2: issue pipelines and the GitHub outbox. The DB is authoritative for
-- pipeline state; GitHub `flow:*` labels/comments are a projection kept in sync
-- through the outbox.

CREATE TABLE issue_pipelines (
    id                TEXT PRIMARY KEY,
    repo_full_name    TEXT NOT NULL,
    issue_number      INTEGER NOT NULL,
    issue_title       TEXT NOT NULL,
    issue_url         TEXT NOT NULL,
    state             TEXT NOT NULL,
    session_id        TEXT REFERENCES sessions (id),
    pr_number         INTEGER,
    pr_url            TEXT,
    merge_commit_sha  TEXT,
    failure_summary   TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    cleared_at        TIMESTAMPTZ
);

-- Repo mutex, enforced at the SQL level: at most one *live* pipeline per repo.
-- Live = an active state, or FAILED that hasn't been cleared (FAILED holds the
-- mutex — the deliberate maximum-caution starting point). Double-picking is a
-- constraint violation, not a race to debug.
CREATE UNIQUE INDEX issue_pipelines_one_live_per_repo_idx
    ON issue_pipelines (repo_full_name)
    WHERE state IN ('IN_PROGRESS', 'PR_OPEN', 'AWAITING_MERGE_CHECKS')
       OR (state = 'FAILED' AND cleared_at IS NULL);

-- At most one non-cleared row per (repo, issue): a re-pick after clearing
-- creates a new row, but you can't have two concurrent rows for the same issue.
CREATE UNIQUE INDEX issue_pipelines_one_uncleared_per_issue_idx
    ON issue_pipelines (repo_full_name, issue_number)
    WHERE cleared_at IS NULL;

-- Listing is newest-first, optionally filtered by repo.
CREATE INDEX issue_pipelines_repo_created_at_idx
    ON issue_pipelines (repo_full_name, created_at);

-- Sessions the reconciler creates for a pipeline carry a back-reference plus
-- denormalized issue fields for display. Manual sessions leave these NULL.
ALTER TABLE sessions ADD COLUMN issue_pipeline_id TEXT REFERENCES issue_pipelines (id);
ALTER TABLE sessions ADD COLUMN issue_number INTEGER;
ALTER TABLE sessions ADD COLUMN issue_url TEXT;

-- Every GitHub side effect of a pipeline transition is written here in the same
-- transaction as the state change, then drained idempotently against GitHub.
CREATE TABLE github_outbox (
    id              TEXT PRIMARY KEY,
    repo_full_name  TEXT NOT NULL,
    issue_number    INTEGER NOT NULL,
    seq             INTEGER NOT NULL,
    action          TEXT NOT NULL,
    payload         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    dispatched_at   TIMESTAMPTZ,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    -- Per-(repo, issue) FIFO ordering key.
    UNIQUE (repo_full_name, issue_number, seq)
);

-- The dispatcher scans undispatched entries by (repo, issue, seq); index the
-- undispatched ones to keep that scan cheap as delivered history accumulates.
CREATE INDEX github_outbox_pending_idx
    ON github_outbox (repo_full_name, issue_number, seq)
    WHERE dispatched_at IS NULL;
