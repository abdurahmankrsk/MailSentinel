CREATE TABLE user_ai_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    label VARCHAR(50) NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    key_ciphertext TEXT NOT NULL,
    key_last4 VARCHAR(4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_ai_keys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_ai_keys_user UNIQUE (user_id)
);
