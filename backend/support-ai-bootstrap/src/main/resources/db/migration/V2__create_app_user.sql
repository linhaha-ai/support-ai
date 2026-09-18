CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_username_normalized CHECK (username = LOWER(BTRIM(username))),
    CONSTRAINT ck_app_user_username_not_blank CHECK (LENGTH(BTRIM(username)) > 0),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'USER'))
);
