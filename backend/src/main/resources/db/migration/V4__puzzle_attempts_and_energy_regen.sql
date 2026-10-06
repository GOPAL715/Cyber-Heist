-- =====================================================================
-- Cyber Heist - Phase 3: puzzle engine and energy regeneration
--
-- Three additive changes. Nothing from V1-V3 is modified, so an existing
-- database upgrades in place and a fresh one reaches the same schema.
--
--   1. missions.puzzle_type      - which puzzle each mission produces.
--   2. player_profiles.last_energy_update
--                               - the timestamp lazy regeneration measures from.
--   3. puzzle_attempts           - one row per generated puzzle.
--
-- Design notes:
--  * puzzle_attempts stores everything needed to VALIDATE a submission (type,
--    difficulty, seed, expiry, status) but never the answer. Validation
--    re-derives the expected answer deterministically from the seed, so there
--    is no plaintext to leak from a database dump and no hash to crack.
--  * started_at / expires_at are server stamps. The client never sends a
--    duration and the server never accepts one.
--  * last_energy_update lets the backend compute regenerated energy lazily on
--    read and mutation instead of running a background sweeper.
-- =====================================================================

-- --------------------- mission -> puzzle mapping ----------------------
-- The puzzle family a mission serves is data, not code, so retuning a mission
-- never means a deploy. Added with a default first so the NOT NULL constraint
-- can be applied to existing rows in one step.
ALTER TABLE missions
    ADD COLUMN puzzle_type VARCHAR(16) NOT NULL DEFAULT 'SEQUENCE';

ALTER TABLE missions
    ADD CONSTRAINT ck_missions_puzzle_type CHECK (
        puzzle_type IN ('CIPHER', 'SEQUENCE', 'PATTERN', 'LOGIC', 'TIMED')
    );

COMMENT ON COLUMN missions.puzzle_type IS
    'Puzzle family generated for this mission. Consumed by the provider registry; never supplied by a client.';

-- Tie each seeded mission to the family that fits its fiction, so the first
-- playable run exercises the whole engine rather than one mechanic repeatedly.
UPDATE missions SET puzzle_type = 'CIPHER' WHERE category = 'CRYPTOGRAPHY';
UPDATE missions SET puzzle_type = 'LOGIC'   WHERE category = 'INTELLIGENCE';
UPDATE missions SET puzzle_type = 'PATTERN' WHERE category = 'NETWORK';
UPDATE missions SET puzzle_type = 'SEQUENCE' WHERE category = 'RECON';
-- EXPLOIT stays on the SEQUENCE default except for the hardest entry, which
-- becomes the short-window timed challenge.
UPDATE missions SET puzzle_type = 'TIMED' WHERE code = 'EXPLOIT_WEAK_LINK';

-- ------------------------- energy regeneration -------------------------
ALTER TABLE player_profiles
    ADD COLUMN last_energy_update TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

COMMENT ON COLUMN player_profiles.last_energy_update IS
    'Server timestamp of the last energy accounting. Regeneration is computed lazily from this value; it is never trusted from clients.';

-- --------------------------- puzzle_attempts ---------------------------
CREATE TABLE puzzle_attempts (
    id             UUID                     NOT NULL,
    user_id        UUID                     NOT NULL,
    mission_id     UUID                     NOT NULL,
    puzzle_id      UUID                     NOT NULL,
    puzzle_type    VARCHAR(16)              NOT NULL,
    difficulty     VARCHAR(16)              NOT NULL,
    seed           BIGINT                   NOT NULL,
    status         VARCHAR(16)              NOT NULL DEFAULT 'ACTIVE',
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at   TIMESTAMP WITH TIME ZONE NULL,
    attempt_number INTEGER                  NOT NULL DEFAULT 1,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_puzzle_attempts PRIMARY KEY (id),
    -- The public instance identifier. Exactly one row per generated puzzle,
    -- which is what makes double-submit protection a row-state check rather
    -- than a race.
    CONSTRAINT uq_puzzle_attempts_puzzle_id UNIQUE (puzzle_id),
    -- One row per generation for a given mission, so attempt numbers cannot
    -- collide. "Only one puzzle is ACTIVE per mission" is enforced by
    -- PuzzleService, which expires the previous one in the same transaction
    -- that creates its successor; a partial unique index would express that
    -- directly but is not portable to the H2 build used by the test suite.
    CONSTRAINT uq_puzzle_attempts_user_mission_attempt UNIQUE (user_id, mission_id, attempt_number),
    CONSTRAINT fk_puzzle_attempts_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_puzzle_attempts_mission FOREIGN KEY (mission_id)
        REFERENCES missions (id) ON DELETE CASCADE,
    CONSTRAINT ck_puzzle_attempts_type CHECK (
        puzzle_type IN ('CIPHER', 'SEQUENCE', 'PATTERN', 'LOGIC', 'TIMED')
    ),
    CONSTRAINT ck_puzzle_attempts_difficulty CHECK (
        difficulty IN ('EASY', 'MEDIUM', 'HARD', 'ELITE')
    ),
    CONSTRAINT ck_puzzle_attempts_status CHECK (
        status IN ('ACTIVE', 'SUCCEEDED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT ck_puzzle_attempts_number CHECK (attempt_number >= 1),
    -- A window must run forwards, or "expired" could be true at creation time.
    CONSTRAINT ck_puzzle_attempts_window CHECK (expires_at > started_at),
    -- An answered puzzle must carry its submission stamp.
    CONSTRAINT ck_puzzle_attempts_submitted CHECK (
        (status = 'ACTIVE')
        OR (submitted_at IS NOT NULL)
    )
);

COMMENT ON TABLE puzzle_attempts IS 'One row per generated puzzle. Stores seed and state for validation, never the answer.';

CREATE INDEX idx_puzzle_attempts_user_mission ON puzzle_attempts (user_id, mission_id);
CREATE INDEX idx_puzzle_attempts_status ON puzzle_attempts (status);
CREATE INDEX idx_puzzle_attempts_expires_at ON puzzle_attempts (expires_at);