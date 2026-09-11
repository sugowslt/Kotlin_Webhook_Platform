ALTER TABLE webhook_deliveries
    ADD COLUMN lease_token UUID;

CREATE TABLE webhook_delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES webhook_deliveries(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    status_code INTEGER,
    error_message VARCHAR(1000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_delivery_attempt_number UNIQUE (delivery_id, attempt_number)
);

CREATE INDEX idx_webhook_delivery_attempts_delivery
    ON webhook_delivery_attempts(delivery_id, attempt_number);
