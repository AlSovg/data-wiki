-- per-user default scoring method and hybrid weights
CREATE TABLE search_settings (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    method  VARCHAR(32) NOT NULL,
    weights JSONB
);
