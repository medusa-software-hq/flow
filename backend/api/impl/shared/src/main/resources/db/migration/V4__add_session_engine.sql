-- M4: the agentic engine a session runs on. 'UNSPECIFIED' = the claiming worker's
-- default (any worker may claim it); a named engine ('BUILTIN' / 'CLAUDE') may only be
-- claimed by a worker that declares it. The DEFAULT backfills existing rows.
ALTER TABLE sessions ADD COLUMN engine TEXT NOT NULL DEFAULT 'UNSPECIFIED';
