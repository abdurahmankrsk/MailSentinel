CREATE TABLE usage_periods (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    allowance INT NOT NULL,
    scans_used INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_usage_periods_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_usage_periods_user_start UNIQUE (user_id, period_start),
    CONSTRAINT ck_usage_periods_used_nonneg CHECK (scans_used >= 0),
    CONSTRAINT ck_usage_periods_used_within_allowance CHECK (scans_used <= allowance)
);

CREATE INDEX idx_usage_periods_user_start_desc ON usage_periods (user_id, period_start DESC);
