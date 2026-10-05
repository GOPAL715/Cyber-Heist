-- =====================================================================
-- Cyber Heist - Phase 2: player progression and mission system
--
-- Adds the mission catalogue and per-player mission progress. The Phase 1
-- tables (users, player_profiles, refresh_tokens) are left untouched, and
-- player_profiles keeps its Phase 1 column list, so no ALTER is required.
-- =====================================================================

-- ------------------------------ missions ------------------------------
-- The catalogue is server-owned data: rewards here are the authoritative
-- values the backend applies. Clients never submit reward amounts.
CREATE TABLE missions (
    id                          UUID                     NOT NULL,
    code                        VARCHAR(64)              NOT NULL,
    title                       VARCHAR(120)             NOT NULL,
    description                 VARCHAR(500)             NOT NULL,
    category                    VARCHAR(32)              NOT NULL,
    difficulty                  VARCHAR(16)              NOT NULL,
    required_level              INTEGER                  NOT NULL DEFAULT 1,
    xp_reward                   INTEGER                  NOT NULL,
    coin_reward                 BIGINT                   NOT NULL,
    energy_cost                 INTEGER                  NOT NULL,
    estimated_duration_seconds  INTEGER                  NOT NULL,
    active                      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_missions PRIMARY KEY (id),
    -- Stable external identifier used by clients and by seed tests.
    CONSTRAINT uq_missions_code UNIQUE (code),
    CONSTRAINT ck_missions_category CHECK (
        category IN ('RECON', 'EXPLOIT', 'CRYPTOGRAPHY', 'NETWORK', 'INTELLIGENCE')
    ),
    CONSTRAINT ck_missions_difficulty CHECK (
        difficulty IN ('EASY', 'MEDIUM', 'HARD', 'ELITE')
    ),
    -- Rewards and costs can never be negative; a zero-cost mission is allowed
    -- for story content but must not be created accidentally.
    CONSTRAINT ck_missions_rewards CHECK (xp_reward >= 0 AND coin_reward >= 0),
    CONSTRAINT ck_missions_energy_cost CHECK (energy_cost >= 0 AND energy_cost <= 1000),
    CONSTRAINT ck_missions_required_level CHECK (required_level >= 1),
    CONSTRAINT ck_missions_duration CHECK (estimated_duration_seconds > 0),
    CONSTRAINT ck_missions_active CHECK (active IN (TRUE, FALSE))
);

COMMENT ON TABLE missions IS 'Server-owned mission catalogue. xp_reward/coin_reward/energy_cost are authoritative and are never supplied by clients.';

-- Filters always read by category and difficulty, so index both.
CREATE INDEX idx_missions_category ON missions (category);
CREATE INDEX idx_missions_difficulty ON missions (difficulty);
CREATE INDEX idx_missions_active ON missions (active);

-- --------------------------- mission_progress --------------------------
-- One row per (player, mission). The unique constraint is what guarantees a
-- player can never accumulate two independent progress records for the same
-- mission, even under concurrent start requests.
CREATE TABLE mission_progress (
    id             UUID                     NOT NULL,
    user_id        UUID                     NOT NULL,
    mission_id     UUID                     NOT NULL,
    status         VARCHAR(16)              NOT NULL DEFAULT 'NOT_STARTED',
    started_at     TIMESTAMP WITH TIME ZONE NULL,
    completed_at   TIMESTAMP WITH TIME ZONE NULL,
    attempt_count  INTEGER                  NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mission_progress PRIMARY KEY (id),
    CONSTRAINT uq_mission_progress_user_mission UNIQUE (user_id, mission_id),
    CONSTRAINT fk_mission_progress_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_mission_progress_mission FOREIGN KEY (mission_id)
        REFERENCES missions (id) ON DELETE CASCADE,
    CONSTRAINT ck_mission_progress_status CHECK (
        status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')
    ),
    CONSTRAINT ck_mission_progress_attempts CHECK (attempt_count >= 0),
    -- A completed mission must have been started, and must carry both stamps.
    CONSTRAINT ck_mission_progress_timestamps CHECK (
        (status <> 'COMPLETED')
        OR (started_at IS NOT NULL AND completed_at IS NOT NULL)
    )
);

COMMENT ON TABLE mission_progress IS 'Per-player mission state. Ownership is derived from the authenticated principal; no client supplies user_id.';

CREATE INDEX idx_mission_progress_user_id ON mission_progress (user_id);
CREATE INDEX idx_mission_progress_mission_id ON mission_progress (mission_id);
CREATE INDEX idx_mission_progress_status ON mission_progress (status);