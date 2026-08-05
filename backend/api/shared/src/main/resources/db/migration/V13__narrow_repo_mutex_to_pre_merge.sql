-- Release the repo mutex at PR merge instead of at DONE, so the next issue
-- can be picked (and worked) while this one's post-merge checks are still
-- being watched in AWAITING_MERGE_CHECKS.
--
-- The old index enforced at most one *live* pipeline per repo (active state,
-- or uncleared FAILED). The new index narrows the blocked set to the
-- pre-merge work phase only: an unmerged branch off trunk (IN_PROGRESS,
-- PR_OPEN), plus an uncleared FAILED row (a post-merge failure still halts
-- further picks until a human clears it). AWAITING_MERGE_CHECKS no longer
-- holds the mutex.
DROP INDEX issue_pipelines_one_live_per_repo_idx;

CREATE UNIQUE INDEX issue_pipelines_one_busy_per_repo_idx
    ON issue_pipelines (repo_full_name)
    WHERE state IN ('IN_PROGRESS', 'PR_OPEN')
       OR (state = 'FAILED' AND cleared_at IS NULL);
