-- Bounded retry for sessions lost to worker death (crash, node failure, or a
-- drain that exceeds its deadline): rather than terminating on the first
-- worker-death signal, the session is requeued (returned to PENDING) up to a
-- bounded number of times before it is allowed to terminate FAILED.
-- attempt_count counts prior worker-death requeues (0 = never requeued).
ALTER TABLE sessions ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
