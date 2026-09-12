CREATE TABLE kerugi_score_events (
    event_id VARCHAR(36) PRIMARY KEY,
    competition_id VARCHAR(36) NOT NULL,
    owner_peer_id VARCHAR(36) NOT NULL,
    court_id VARCHAR(36) NOT NULL,
    bracket_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    judge_id VARCHAR(36) NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    source VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    UNIQUE (owner_peer_id, sequence)
);
