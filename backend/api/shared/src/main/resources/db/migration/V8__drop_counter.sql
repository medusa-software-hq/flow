-- The counter demo feature has been removed. V1 created this table and has already been
-- applied to staging/prod, so this is a forward migration rather than an edit to V1.
DROP TABLE IF EXISTS counter;
