-- A "job" groups the sessions created together as one unit of work — a manual
-- session is a 1-session job; a reconciled issue is a 2-session job (Claude
-- primary + built-in shadow). A worker claims a whole job and runs its sessions
-- in parallel, so both engines run simultaneously under one worker while the
-- repo mutex still admits one issue at a time.
ALTER TABLE sessions ADD COLUMN job_id TEXT;

-- Backfill: every existing session becomes its own 1-session job.
UPDATE sessions SET job_id = id WHERE job_id IS NULL;

ALTER TABLE sessions ALTER COLUMN job_id SET NOT NULL;

-- The claim locks a whole job at once (all its PENDING sessions), keyed by job_id.
CREATE INDEX sessions_job_id_idx ON sessions (job_id);
