-- M4 (Claude engine): the run's terminal cost in USD, stamped from the RUN_COST
-- session event so the sessions list can show cost without loading events. NULL for
-- sessions that haven't reported a cost yet (or engines that don't report one).
ALTER TABLE sessions ADD COLUMN total_cost_usd DOUBLE PRECISION;
