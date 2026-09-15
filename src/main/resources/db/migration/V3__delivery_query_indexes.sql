CREATE INDEX idx_webhook_deliveries_created
    ON webhook_deliveries(created_at DESC, id DESC);

CREATE INDEX idx_webhook_deliveries_status_created
    ON webhook_deliveries(status, created_at DESC, id DESC);
