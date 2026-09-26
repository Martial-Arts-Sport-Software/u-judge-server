CREATE TABLE domain_events (
    event_id VARCHAR(36) PRIMARY KEY,
    owner_peer_id VARCHAR(36) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    competition_id VARCHAR(36),
    court_id VARCHAR(36),
    bracket_id VARCHAR(36),
    session_id VARCHAR(36),
    judge_id VARCHAR(36),
    device_id VARCHAR(255),
    source VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    UNIQUE (owner_peer_id, sequence)
);
CREATE INDEX domain_events_session ON domain_events (bracket_id, session_id);
INSERT INTO domain_events (
    event_id, owner_peer_id, sequence, competition_id, court_id, bracket_id, session_id, judge_id, device_id,
    source, author, occurred_at, event_type, payload
)
SELECT event_id, owner_peer_id,
    ROW_NUMBER() OVER (PARTITION BY owner_peer_id ORDER BY occurred_at, legacy_table, sequence, event_id),
    competition_id, court_id, bracket_id, session_id, judge_id, device_id, source, author, occurred_at, event_type, payload
FROM (
    SELECT 2 AS legacy_table, e.* FROM session_lifecycle_events e
    UNION ALL SELECT 3 AS legacy_table, e.* FROM kerugi_score_events e
    UNION ALL SELECT 4 AS legacy_table, e.* FROM kerugi_timer_events e
    UNION ALL SELECT 5 AS legacy_table, e.* FROM kerugi_result_events e
) legacy;
DROP TABLE session_lifecycle_events;
DROP TABLE kerugi_score_events;
DROP TABLE kerugi_timer_events;
DROP TABLE kerugi_result_events
