-- Drop single-column indexes that are never selectivity-useful on their own.
-- Every query that touches rule_category or action also filters on config_id
-- and a timestamp range, so Postgres ignores these standalone indexes and
-- uses idx_events_config_ts instead, paying a full sequential aggregate.
DROP INDEX idx_events_category;
DROP INDEX idx_events_action;

-- Composite indexes that match the actual query predicates.
-- Leading column is config_id (equality, high selectivity), then the
-- GROUP-BY/filter column, then timestamp DESC so the index also serves
-- ORDER BY timestamp DESC on findSamples queries.

-- Serves: byCategory aggregation  +  findSamples filtered by rule_category
CREATE INDEX idx_events_config_category_ts
    ON events (config_id, rule_category, timestamp DESC);

-- Serves: byAction aggregation  +  findSamples filtered by action
CREATE INDEX idx_events_config_action_ts
    ON events (config_id, action, timestamp DESC);
