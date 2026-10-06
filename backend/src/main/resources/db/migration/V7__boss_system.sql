-- =====================================================================
-- Cyber Heist - Phase 6: boss encounters
--
-- Three new tables and one alteration to puzzle_attempts:
--   bosses            the catalogue: what a boss is and what it pays
--   boss_stages       what each phase of a boss demands, and how much it hurts
--   boss_encounters   one player's run at one boss, with its lifecycle state
--   puzzle_attempts   gained boss_encounter_id, and mission_id became optional
--
-- V1-V6 are never modified; Phase 6 is entirely additive.
--
-- Design notes
-- ------------
-- A boss is not a mission with a bigger number. It is a multi-stage encounter
-- with its own state machine, its own puzzle attempts, and a one-shot entry cost.
-- Nothing here reuses the mission tables, because a boss has no level gate in
-- mission_progress, no single reward row, and may be active for an hour.
--
-- The one thing that *is* shared is puzzle generation: a stage names a
-- puzzle_type and a difficulty, and PuzzleService does the rest. No boss code
-- reimplements a provider.
-- =====================================================================

-- ------------------------------- bosses --------------------------------
CREATE TABLE bosses (
    id                       UUID                     NOT NULL,
    code                     VARCHAR(64)              NOT NULL,
    name                     VARCHAR(120)             NOT NULL,
    description              VARCHAR(500)             NOT NULL,
    difficulty               VARCHAR(16)              NOT NULL,
    required_level           INTEGER                  NOT NULL,
    energy_cost              INTEGER                  NOT NULL,
    stage_count              INTEGER                  NOT NULL,
    xp_reward                BIGINT                   NOT NULL,
    coin_reward              BIGINT                   NOT NULL,
    cooldown_victory_minutes INTEGER                  NOT NULL,
    cooldown_defeat_minutes  INTEGER                  NOT NULL,
    active                   BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_bosses PRIMARY KEY (id),
    CONSTRAINT uq_bosses_code UNIQUE (code),
    CONSTRAINT ck_bosses_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD', 'ELITE')),
    -- The same progression rules as a mission gate: at least level 1.
    CONSTRAINT ck_bosses_required_level CHECK (required_level >= 1),
    -- Charged once on entry. Never per stage, so a long encounter is not also a
    -- long energy bill.
    CONSTRAINT ck_bosses_energy_cost CHECK (energy_cost > 0),
    CONSTRAINT ck_bosses_stage_count CHECK (stage_count > 0 AND stage_count <= 10),
    CONSTRAINT ck_bosses_rewards CHECK (xp_reward >= 0 AND coin_reward >= 0),
    CONSTRAINT ck_bosses_cooldown CHECK (
        cooldown_victory_minutes >= 0 AND cooldown_defeat_minutes >= 0
    ),
    CONSTRAINT ck_bosses_active CHECK (active IN (TRUE, FALSE))
);

COMMENT ON TABLE bosses IS 'Server-owned boss catalogue. Rewards and entry cost are defined here, never by a request.';
COMMENT ON COLUMN bosses.energy_cost IS 'Charged once when the encounter starts. Equipment energy efficiency may reduce the final charge.';
COMMENT ON COLUMN bosses.cooldown_victory_minutes IS 'Cooldown applied after a victory. Server-calculated; the client clock is never trusted.';

CREATE INDEX idx_bosses_active ON bosses (active);
CREATE INDEX idx_bosses_required_level ON bosses (required_level);

-- ----------------------------- boss_stages -----------------------------
-- What each phase demands. puzzle_type and difficulty are handed straight to
-- PuzzleService, so a stage cannot invent a challenge family.
CREATE TABLE boss_stages (
    id                UUID                     NOT NULL,
    boss_id           UUID                     NOT NULL,
    stage_number      INTEGER                  NOT NULL,
    name              VARCHAR(120)             NOT NULL,
    description       VARCHAR(500)             NOT NULL,
    puzzle_type       VARCHAR(16)              NOT NULL,
    difficulty        VARCHAR(16)              NOT NULL,
    time_limit_seconds INTEGER                 NOT NULL,
damage_value      INTEGER                  NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_boss_stages PRIMARY KEY (id),
    -- One definition per phase, so a lookup is unambiguous.
    CONSTRAINT uq_boss_stages_boss_stage UNIQUE (boss_id, stage_number),
    CONSTRAINT fk_boss_stages_boss FOREIGN KEY (boss_id)
        REFERENCES bosses (id) ON DELETE CASCADE,
    CONSTRAINT ck_boss_stages_number CHECK (stage_number > 0),
    -- Exactly the five families PuzzleService has a provider for. Adding a type
    -- here without a provider would fail the boss at runtime, not at boot,
    -- which is why this list is deliberately identical to PuzzleType.
    CONSTRAINT ck_boss_stages_puzzle_type CHECK (
        puzzle_type IN ('CIPHER', 'SEQUENCE', 'PATTERN', 'LOGIC', 'TIMED')
    ),
    CONSTRAINT ck_boss_stages_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD', 'ELITE')),
    -- The stage window overrides the provider's own, because a boss phase needs
    -- a predictable budget regardless of family. TIMED puzzles are otherwise a
    -- matter of seconds.
    CONSTRAINT ck_boss_stages_time_limit CHECK (time_limit_seconds > 0 AND time_limit_seconds <= 3600),
    -- Damage is the only thing that moves boss integrity, and the client never
    -- supplies it.
    CONSTRAINT ck_boss_stages_damage CHECK (damage_value > 0)
);

COMMENT ON TABLE boss_stages IS 'Per-phase challenge, window and damage. The damage is applied by the server from this row.';
CREATE INDEX idx_boss_stages_boss_id ON boss_stages (boss_id);

-- --------------------------- boss_encounters ---------------------------
-- One player's run at one boss.
CREATE TABLE boss_encounters (
    id              UUID                     NOT NULL,
    user_id         UUID                     NOT NULL,
    boss_id         UUID                     NOT NULL,
    status          VARCHAR(16)              NOT NULL DEFAULT 'ACTIVE',
    current_stage   INTEGER                  NOT NULL DEFAULT 1,
    boss_integrity  INTEGER                  NOT NULL DEFAULT 100,
    xp_awarded      BIGINT                   NOT NULL DEFAULT 0,
    coin_awarded    BIGINT                   NOT NULL DEFAULT 0,
    reached_stage   INTEGER                  NOT NULL DEFAULT 1,
    started_at      TIMESTAMP WITH TIME ZONE              NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE              NULL,
    failed_at       TIMESTAMP WITH TIME ZONE              NULL,
    expires_at      TIMESTAMP WITH TIME ZONE              NOT NULL,
    cooldown_until  TIMESTAMP WITH TIME ZONE              NULL,
    active_marker   INTEGER                  NULL,
    created_at      TIMESTAMP WITH TIME ZONE              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_boss_encounters PRIMARY KEY (id),
    CONSTRAINT fk_boss_encounters_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_boss_encounters_boss FOREIGN KEY (boss_id)
        REFERENCES bosses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_boss_encounters_status CHECK (
        status IN ('ACTIVE', 'VICTORY', 'DEFEATED', 'EXPIRED')
    ),
    CONSTRAINT ck_boss_encounters_current_stage CHECK (current_stage >= 1),
    -- A boss always starts at 100 and only ever goes down; the ceiling keeps a
    -- corrupted row from being used to skip phases.
    CONSTRAINT ck_boss_encounters_integrity CHECK (boss_integrity >= 0 AND boss_integrity <= 100),
    CONSTRAINT ck_boss_encounters_reached_stage CHECK (reached_stage >= 1),
    -- Zero rewards unless the encounter was actually won. This is the second
    -- line of defence against a duplicated payout: even a bug that re-entered
    -- victory would find the row already marked and the rewards already spent.
    CONSTRAINT ck_boss_encounters_rewards CHECK (
        (status = 'VICTORY' AND xp_awarded > 0 AND coin_awarded > 0)
        OR (status <> 'VICTORY' AND xp_awarded = 0 AND coin_awarded = 0)
    ),
    CONSTRAINT ck_boss_encounters_times CHECK (expires_at > started_at),
    -- The single-active-encounter guarantee.
    --
    -- active_marker is 1 while the encounter is ACTIVE and NULL once it ends.
    -- UNIQUE (user_id, active_marker) therefore permits many finished encounters
    -- (NULLs never collide in a unique index) but only one live one. A partial
    -- unique index would say this more directly, but it is not portable to the
    -- H2 build the suite runs against, and this is enforced in the application
    -- too by locking the profile row first.
    CONSTRAINT uq_boss_encounters_one_active UNIQUE (user_id, active_marker),
    CONSTRAINT ck_boss_encounters_active_marker CHECK (
        (status = 'ACTIVE' AND active_marker = 1) OR (status <> 'ACTIVE' AND active_marker IS NULL)
    )
);

COMMENT ON TABLE boss_encounters IS 'A player run at a boss. Rewards are non-zero only for VICTORY.';
COMMENT ON COLUMN boss_encounters.boss_integrity IS 'Server-owned. Reduced only by boss_stages.damage_value; never accepted from a request.';
COMMENT ON COLUMN boss_encounters.cooldown_until IS 'Server-calculated at the moment the encounter ends. No background job is involved.';

CREATE INDEX idx_boss_encounters_user_id ON boss_encounters (user_id);
CREATE INDEX idx_boss_encounters_boss_id ON boss_encounters (boss_id);
CREATE INDEX idx_boss_encounters_status ON boss_encounters (status);
CREATE INDEX idx_boss_encounters_cooldown ON boss_encounters (cooldown_until);
CREATE INDEX idx_boss_encounters_created ON boss_encounters (created_at DESC);

-- ------------------- puzzle_attempts: boss ownership -------------------
-- A boss stage needs a puzzle, and a puzzle attempt is how one is validated. The
-- Phase 3 table insisted every attempt belonged to a mission, which was true
-- then and is not true now.
--
-- Rather than loosen it into ambiguity, the attempt now names exactly one owner
-- and the database says so. That is what stops a boss puzzle being submitted to
-- a mission endpoint (or the reverse) even if some future code forgets to check.
ALTER TABLE puzzle_attempts
    ALTER COLUMN mission_id DROP NOT NULL;

ALTER TABLE puzzle_attempts
    ADD COLUMN boss_encounter_id UUID NULL;

ALTER TABLE puzzle_attempts
    ADD CONSTRAINT fk_puzzle_attempts_boss FOREIGN KEY (boss_encounter_id)
        REFERENCES boss_encounters (id) ON DELETE CASCADE;

-- Exactly one owner: a mission attempt or a boss attempt, never both and never
-- neither.
ALTER TABLE puzzle_attempts
    ADD CONSTRAINT ck_puzzle_attempts_owner CHECK (
        (mission_id IS NOT NULL AND boss_encounter_id IS NULL)
        OR (mission_id IS NULL AND boss_encounter_id IS NOT NULL)
    );

-- One puzzle per phase of an encounter. attempt_number carries the stage number
-- for a boss attempt, so this is "stage 2 has exactly one puzzle".
--
-- The existing (user_id, mission_id, attempt_number) unique constraint does not
-- cover boss rows, because its mission_id is NULL and NULLs do not collide.
CREATE UNIQUE INDEX uq_puzzle_attempts_boss_attempt
    ON puzzle_attempts (boss_encounter_id, attempt_number);

CREATE INDEX idx_puzzle_attempts_boss_encounter ON puzzle_attempts (boss_encounter_id);

COMMENT ON COLUMN puzzle_attempts.mission_id IS
    'NULL for a boss stage attempt. Exactly one of mission_id / boss_encounter_id is set.';
COMMENT ON COLUMN puzzle_attempts.attempt_number IS
    'Mission attempt number, or the boss stage number for a boss attempt.';

-- =====================================================================
-- Seed: 5 bosses, 3 phases each.
--
-- Economy. The 15 missions pay 1,133 coins and 2,265 XP in total, and Phase 4
-- priced the whole item catalogue at 18,850. A boss must be worth real effort
-- without being a shortcut, so:
--
--   boss rewards   250-1,200 XP and 150-750 coins
--   whole catalogue 5,400 XP and 2,660 coins
--   catalogue cost 18,850 coins
--
-- Winning every boss once buys roughly an eighth of the item catalogue, which
-- is about one full legendary-tier item. That reads as a milestone rather than a
-- windfall, and it is still gated behind level 6 at the very cheapest.
--
-- Required levels step 6 / 10 / 14 / 20 / 26 so a player meets them in order and
-- a level 1 player cannot stumble into an ELITE boss.
--
-- Entry energy is 30 / 40 / 50 by tier, charged once. A 3-phase encounter
-- costing the same as a single hard mission would make length a reward rather
-- than a risk, so the entry fee is priced to be a real commitment.
--
-- Damage sums to exactly 100 in every case, so a boss always falls on its final
-- phase. The split across phases differs per boss so the last one is not always
-- the same weight.
-- =====================================================================

INSERT INTO bosses (id, code, name, description, difficulty, required_level, energy_cost,
                    stage_count, xp_reward, coin_reward,
                    cooldown_victory_minutes, cooldown_defeat_minutes, active) VALUES
('41111111-0000-4000-8000-000000000001', 'THE_FIREWALL', 'The Firewall',
 'Not a program. A mind that has spent thirty years learning to say no. It does not block intrusions so much as decide that you never arrived.',
 'MEDIUM', 6, 30, 3, 350, 220, 720, 30, TRUE),

('41111111-0000-4000-8000-000000000002', 'THE_PHANTOM', 'The Phantom',
 'You have heard it three times. Once in a system you should not have reached, once in a mirror you were not supposed to own, and once, briefly, in your own voice.',
 'HARD', 10, 40, 3, 650, 400, 720, 30, TRUE),

('41111111-0000-4000-8000-000000000003', 'BLACK_ICE', 'Black Ice',
 'Corporate enforcement, distilled. It does not chase you and it does not need to. The charges were already filed before you started.',
 'ELITE', 20, 50, 3, 1200, 750, 720, 30, TRUE),

('41111111-0000-4000-8000-000000000004', 'ZERO_DAY', 'Zero Day',
 'The window before the patch. Everything still works, nothing is watched yet, and everyone in the building knows it. That is the whole job.',
 'MEDIUM', 6, 30, 3, 250, 150, 720, 30, TRUE),

('41111111-0000-4000-8000-000000000005', 'THE_ARCHITECT', 'The Architect',
 'It designed the building you are standing in. It is curious about what you came for, and it has already decided the answer.',
 'ELITE', 26, 50, 3, 1100, 700, 720, 30, TRUE);

-- ---------------------------- boss_stages ----------------------------
-- Each boss draws a different combination of the five existing families, so
-- no two fights ask for the same sequence of skills.
INSERT INTO boss_stages (id, boss_id, stage_number, name, description, puzzle_type,
                         difficulty, time_limit_seconds, damage_value) VALUES
-- THE_FIREWALL: find the gap, break the lock, reach the core
('42111111-0000-4000-8000-000000000001', '41111111-0000-4000-8000-000000000001', 1, 'Scan the Perimeter',
 'It has already noticed you. Map what is watching before anything watches back.',
 'PATTERN', 'MEDIUM', 240, 20),
('42111111-0000-4000-8000-000000000002', '41111111-0000-4000-8000-000000000001', 2, 'Break the Cipher',
 'The front door is not a door. It is a handshake, and you have just been asked to prove you know the protocol.',
 'CIPHER', 'HARD', 240, 30),
('42111111-0000-4000-8000-000000000003', '41111111-0000-4000-8000-000000000001', 3, 'Override Core Security',
 'One decision left. It is reading your intent now, and it has not looked away.',
 'LOGIC', 'HARD', 300, 50),

-- THE_PHANTOM: order, silence, then the room it left behind
('42111111-0000-4000-8000-000000000004', '41111111-0000-4000-8000-000000000002', 1, 'Follow the Timing',
 'It moves between systems on a schedule it did not write. Find the interval.',
 'SEQUENCE', 'HARD', 240, 25),
('42111111-0000-4000-8000-000000000005', '41111111-0000-4000-8000-000000000002', 2, 'Silence the Trace',
 'Every probe you send writes something down. Write nothing.',
 'CIPHER', 'HARD', 240, 30),
('42111111-0000-4000-8000-000000000006', '41111111-0000-4000-8000-000000000002', 3, 'Face the Copy',
 'It is waiting in a room you built. It has your credentials, your route and your face.',
 'PATTERN', 'HARD', 300, 45),

-- BLACK_ICE: compliance first, then the paper trail, then the verdict
('42111111-0000-4000-8000-000000000007', '41111111-0000-4000-8000-000000000003', 1, 'Satisfy the Policy',
 'Nothing here is attacking you. That is the point. Comply, and keep moving.',
 'LOGIC', 'HARD', 300, 20),
('42111111-0000-4000-8000-000000000008', '41111111-0000-4000-8000-000000000003', 2, 'Read the Charge',
 'The case against you is already written. Find the one fact in it that is wrong.',
 'SEQUENCE', 'HARD', 300, 30),
('42111111-0000-4000-8000-000000000009', '41111111-0000-4000-8000-000000000003', 3, 'Close the Case',
 'It will read your defence once. Make it a lie it cannot check.',
 'CIPHER', 'ELITE', 360, 50),

-- ZERO_DAY: the shortest window, the least room
('42111111-0000-4000-8000-000000000010', '41111111-0000-4000-8000-000000000004', 1, 'Enter Before the Patch',
 'Everything still works. Nothing is watching yet. Move.',
 'CIPHER', 'MEDIUM', 180, 30),
('42111111-0000-4000-8000-000000000011', '41111111-0000-4000-8000-000000000004', 2, 'Take the Window',
 'The window is closing faster than the estimate said it would.',
 'SEQUENCE', 'MEDIUM', 180, 30),
('42111111-0000-4000-8000-000000000012', '41111111-0000-4000-8000-000000000004', 3, 'Get Out Clean',
 'One command left before the patch lands and this door stops being a door.',
 'PATTERN', 'HARD', 240, 40),

-- THE_ARCHITECT: the building itself
('42111111-0000-4000-8000-000000000013', '41111111-0000-4000-8000-000000000005', 1, 'Read the Blueprint',
 'The floor plan is a lie it wrote for you. The real one is in the pattern.',
 'PATTERN', 'HARD', 300, 15),
('42111111-0000-4000-8000-000000000014', '41111111-0000-4000-8000-000000000005', 2, 'Find the Load Bearing',
 'Take out the wrong wall and the whole structure notices.',
 'LOGIC', 'HARD', 300, 35),
('42111111-0000-4000-8000-000000000015', '41111111-0000-4000-8000-000000000005', 3, 'Face the Architect',
 'It has been watching you solve its own building since the first door.',
 'TIMED', 'ELITE', 180, 50);