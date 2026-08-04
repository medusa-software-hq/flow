-- Workers became uniform: every worker runs every engine, so a worker no longer
-- advertises a capability set. Drop the now-unused column. V6 created it and has
-- already been applied to staging/prod, so this is a forward migration rather
-- than an edit to V6.
ALTER TABLE workers DROP COLUMN supported_engines;
