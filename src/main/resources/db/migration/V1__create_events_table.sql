CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL UNIQUE,
    timestamp       TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    config_id       INTEGER      NOT NULL,
    policy_id       VARCHAR(64),
    client_ip       VARCHAR(45)  NOT NULL,
    hostname        VARCHAR(255),
    path            TEXT,
    method          VARCHAR(10),
    status_code     INTEGER,
    user_agent      TEXT,
    rule_id         VARCHAR(32),
    rule_name       VARCHAR(128),
    rule_message    TEXT,
    rule_severity   VARCHAR(16)  NOT NULL,
    rule_category   VARCHAR(32)  NOT NULL,
    action          VARCHAR(16)  NOT NULL,
    geo_country     VARCHAR(2),
    geo_city        VARCHAR(100),
    request_size    BIGINT,
    response_size   BIGINT,
    attack_type     VARCHAR(64)  NOT NULL,
    threat_score    INTEGER      NOT NULL
);

CREATE INDEX idx_events_config_ts     ON events (config_id, timestamp DESC);
CREATE INDEX idx_events_ts            ON events (timestamp DESC);
CREATE INDEX idx_events_client_ip_ts  ON events (client_ip, timestamp DESC);
CREATE INDEX idx_events_category      ON events (rule_category);
CREATE INDEX idx_events_action        ON events (action);
