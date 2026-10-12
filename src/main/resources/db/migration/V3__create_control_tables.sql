CREATE TABLE outbox_events (
    sequence     BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_events_status_sequence ON outbox_events (status, sequence);

CREATE TABLE inbox_events (
    message_key      VARCHAR(200) PRIMARY KEY,
    response_payload JSONB,
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbox_events_processed_at ON inbox_events (processed_at);
