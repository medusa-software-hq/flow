-- Dual-engine fan-out: every picked issue now runs a primary session (Claude,
-- observed) and a parallel "shadow" session (built-in) for comparison. The
-- shadow opens its own PR but is never observed — it doesn't advance pipeline
-- state or gate the merge. Nullable: pipelines picked before this column
-- existed have no shadow.
ALTER TABLE issue_pipelines
    ADD COLUMN shadow_session_id TEXT REFERENCES sessions (id);
