-- Quick Settings: a single global, per-deployment configuration row (not a
-- stringly-typed KV bag — one typed column per setting). `id` is pinned to 1 so
-- the table can only ever hold its one seeded singleton row.
CREATE TABLE settings (
    id         INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    auto_merge BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO settings (id, auto_merge) VALUES (1, FALSE);
