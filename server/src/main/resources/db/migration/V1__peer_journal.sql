CREATE TABLE peer_journal_events (
    id VARCHAR(255) PRIMARY KEY,
    owner_peer_id VARCHAR(255) NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    payload TEXT NOT NULL,
    UNIQUE (owner_peer_id, sequence)
);
