-- Deals table
CREATE TABLE deals (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    title VARCHAR(100) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 1 AND price_paise <= 1000000),
    stock_total INT NOT NULL CHECK (stock_total >= 1 AND stock_total <= 10000),
    stock_available INT NOT NULL CHECK (stock_available >= 0 AND stock_available <= stock_total),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_deal_time_window CHECK (starts_at < ends_at)
);

CREATE INDEX idx_deals_restaurant_id ON deals (restaurant_id);

-- Claims table
CREATE TABLE claims (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 1 AND quantity <= 3),
    amount_paise BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_claims_deal_id ON claims (deal_id);
CREATE INDEX idx_claims_customer_id ON claims (customer_id);
CREATE INDEX idx_claims_created_at_id ON claims (created_at DESC, id DESC);
CREATE INDEX idx_claims_status_expires_at ON claims (status, expires_at) WHERE status = 'RESERVED';

-- Rule 2: A customer can hold at most one RESERVED, PAID or NEEDS_REVIEW claim on a deal.
-- Once a claim expires (or is refund_pending), they may claim again.
CREATE UNIQUE INDEX idx_claims_active_per_customer_deal 
ON claims (deal_id, customer_id) 
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Idempotency table
CREATE TABLE idempotency_keys (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    deal_id UUID NOT NULL,
    quantity INT NOT NULL,
    status_code INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '60 seconds'),
    PRIMARY KEY (customer_id, idempotency_key)
);

CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);

-- Payment Webhook deduplication table
CREATE TABLE payment_events (
    event_id VARCHAR(128) PRIMARY KEY,
    claim_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    amount_paise BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_events_claim_id ON payment_events (claim_id);

-- Rate limiting table (Stretch Goal 2: 10 claim requests per customer per minute across instances)
CREATE TABLE claim_rate_limits (
    id BIGSERIAL PRIMARY KEY,
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_claim_rate_limits_customer_time ON claim_rate_limits (customer_id, requested_at);
CREATE INDEX idx_claim_rate_limits_customer_key ON claim_rate_limits (customer_id, idempotency_key);

