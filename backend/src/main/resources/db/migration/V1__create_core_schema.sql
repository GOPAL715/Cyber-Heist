-- =====================================================================
-- Cyber Heist - Phase 1 core schema
--
-- This migration is the single source of truth for the schema.
-- Hibernate runs with ddl-auto=validate and never mutates the schema.
-- =====================================================================

-- ----------------------------- users -----------------------------
CREATE TABLE users (
    id            UUID                     NOT NULL,
    username      VARCHAR(32)              NOT NULL,
    email         VARCHAR(255)             NOT NULL,
    password_hash VARCHAR(100)             NOT NULL,
    role          VARCHAR(20)              NOT NULL DEFAULT 'PLAYER',
    enabled       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('PLAYER', 'ADMIN'))
);

COMMENT ON TABLE users IS 'Application accounts. Password is only ever stored as a BCrypt hash.';

-- ------------------------- player_profiles -------------------------
-- Created in the same transaction as the user, ready for future game systems
-- (missions, XP, level ups, shop, inventory...).
CREATE TABLE player_profiles (
    id           UUID                     NOT NULL,
    user_id      UUID                     NOT NULL,
    display_name VARCHAR(32)              NOT NULL,
    level        INTEGER                  NOT NULL DEFAULT 1,
    experience   BIGINT                   NOT NULL DEFAULT 0,
    coins        BIGINT                   NOT NULL DEFAULT 100,
    energy       INTEGER                  NOT NULL DEFAULT 100,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_profiles PRIMARY KEY (id),
    CONSTRAINT uq_player_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_player_profiles_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_profiles_level CHECK (level >= 1),
    CONSTRAINT ck_player_profiles_progress CHECK (experience >= 0 AND coins >= 0 AND energy >= 0)
);

COMMENT ON TABLE player_profiles IS 'Per-user game state. One row per user, derived exclusively from the authenticated principal.';

-- -------------------------- refresh_tokens --------------------------
-- Opaque random tokens are issued to clients. Only the SHA-256 hash is
-- persisted, so a database leak cannot be replayed against the API.
CREATE TABLE refresh_tokens (
    id         UUID                     NOT NULL,
    user_id    UUID                     NOT NULL,
    token_hash VARCHAR(64)              NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked    BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_revoked CHECK (revoked IN (TRUE, FALSE))
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

COMMENT ON TABLE refresh_tokens IS 'Hashed, rotatable and revocable refresh tokens. Raw tokens are never stored.';