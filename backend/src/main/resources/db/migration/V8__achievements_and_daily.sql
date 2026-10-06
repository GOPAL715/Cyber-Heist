-- =====================================================================
-- Cyber Heist - Phase 7: achievements and daily challenges
--
-- Eight new tables plus two columns on player_profiles:
--   achievements               the permanent milestone catalogue
--   player_achievements        what a player has earned against each one
--   player_streaks             consecutive-day activity, one row per player
--   player_daily_counters      per-day event totals, the daily progress source
--   daily_challenge_definitions the rotating catalogue today's set is drawn from
--   daily_challenges           the concrete set materialised for one date
--   daily_challenge_progress   what a player has done toward today's set
--   streak_milestone_awards    which streak milestones were already paid
--   player_profiles.coins_earned     lifetime coins, for the economy achievements
--
-- V1-V7 are never modified; Phase 7 is entirely additive apart from the two
-- additive columns above.
--
-- Design notes
-- ------------
-- Two different progress sources, on purpose.
--
-- Achievement progress is *derived*, never incremented. The counters behind
-- FIRST_STEPS, CIPHER_BREAKER and FULLY_LOADED are computed from the tables
-- that already record what happened - mission_progress, puzzle_attempts,
-- player_inventory_items, player_equipment, player_skills, boss_encounters -
-- every time achievements are evaluated. Nothing accumulates, so a retried
-- request, a replayed submission or a double-clicked button cannot inflate a
-- count, and a player who already qualifies before this migration lands is
-- credited on their first read rather than being locked out forever.
--
-- Daily challenge progress *is* a counter, because a daily requirement is by
-- definition scoped to one business date and cannot be recovered from a
-- lifetime total. Those totals live in player_daily_counters, keyed by
-- (user_id, business_date, metric). Keying by date is what removes the need for
-- a midnight job: a row for yesterday is simply never read again, and there is
-- no scheduler, no per-player timer and no reset sweep anywhere in this phase.
--
-- Requirement types are a closed set of VARCHAR codes with CHECK constraints,
-- not executable expressions. There is deliberately no column anywhere in this
-- schema that a database string could be interpreted as code.
-- =====================================================================

-- ----------------------- player_profiles additions ---------------------
-- Lifetime coins credited, distinct from the balance. `coins` is spent, so it
-- cannot answer "how much has this player ever earned", which the economy
-- achievements and the COINS_EARNED daily requirement both need.
ALTER TABLE player_profiles
    ADD COLUMN coins_earned BIGINT NOT NULL DEFAULT 0;

ALTER TABLE player_profiles
    ADD CONSTRAINT ck_player_profiles_coins_earned CHECK (coins_earned >= 0);

COMMENT ON COLUMN player_profiles.coins_earned IS
    'Lifetime coins credited to this player. Incremented by the single writer of the coin balance (PlayerProfile.addCoins); never decreases when coins are spent.';

-- ----------------------------- achievements ----------------------------
-- The permanent catalogue. Every row is a milestone, not a step: the intent is
-- roughly thirty of these rather than hundreds of trivial ones.
CREATE TABLE achievements (
    id                UUID                     NOT NULL,
    code              VARCHAR(64)              NOT NULL,
    name              VARCHAR(120)             NOT NULL,
    description       VARCHAR(500)             NOT NULL,
    category          VARCHAR(20)              NOT NULL,
    requirement_type  VARCHAR(32)              NOT NULL,
    requirement_value INTEGER                  NOT NULL,
    xp_reward         BIGINT                   NOT NULL DEFAULT 0,
    coin_reward       BIGINT                   NOT NULL DEFAULT 0,
    icon              VARCHAR(16)              NOT NULL DEFAULT '◆',
    sort_order        INTEGER                  NOT NULL DEFAULT 0,
    active            BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_achievements PRIMARY KEY (id),
    CONSTRAINT uq_achievements_code UNIQUE (code),
    -- A closed set, so a new requirement is a migration rather than a surprise.
    CONSTRAINT ck_achievements_category CHECK (
        category IN ('MISSIONS', 'PUZZLES', 'PROGRESSION', 'ECONOMY',
                     'EQUIPMENT', 'SKILLS', 'BOSSES', 'DAILY')
    ),
    CONSTRAINT ck_achievements_requirement_type CHECK (
        requirement_type IN (
            'MISSIONS_COMPLETED', 'PUZZLES_SOLVED',
            'CIPHER_SOLVED', 'LOGIC_SOLVED', 'PATTERN_SOLVED',
            'SEQUENCE_SOLVED', 'TIMED_SOLVED',
            'PLAYER_LEVEL', 'COINS_EARNED',
            'ITEMS_OWNED', 'ITEMS_EQUIPPED',
            'SKILLS_UNLOCKED', 'SKILL_LEVEL',
            'BOSSES_DEFEATED', 'DAILY_CHALLENGES_COMPLETED', 'LOGIN_STREAK'
        )
    ),
    -- A milestone of zero would be unlocked by doing nothing at all.
    CONSTRAINT ck_achievements_requirement_value CHECK (requirement_value > 0),
    CONSTRAINT ck_achievements_rewards CHECK (xp_reward >= 0 AND coin_reward >= 0),
    CONSTRAINT ck_achievements_active CHECK (active IN (TRUE, FALSE))
);

CREATE INDEX idx_achievements_active ON achievements (active);
CREATE INDEX idx_achievements_category ON achievements (category);

COMMENT ON TABLE achievements IS
    'Server-owned milestone catalogue. requirement_type names one controlled counter and requirement_value is its target; nothing here is evaluated as an expression.';

-- ------------------------ player_achievements -------------------------
CREATE TABLE player_achievements (
    id             UUID                     NOT NULL,
    user_id        UUID                     NOT NULL,
    achievement_id UUID                     NOT NULL,
    -- The last observed counter value, cached so a list read does not have to
    -- recount. Never authoritative: it is refreshed from the real counters on
    -- every evaluation, and it is always clamped to requirement_value.
    progress       INTEGER                  NOT NULL DEFAULT 0,
    unlocked       BOOLEAN                  NOT NULL DEFAULT FALSE,
    unlocked_at    TIMESTAMP WITH TIME ZONE NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_achievements PRIMARY KEY (id),
    -- The idempotency guarantee. A second unlock for the same pair cannot be
    -- written even if two threads evaluate the same milestone at once.
    CONSTRAINT uq_player_achievements_user_achievement UNIQUE (user_id, achievement_id),
    CONSTRAINT fk_player_achievements_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_player_achievements_achievement FOREIGN KEY (achievement_id)
        REFERENCES achievements (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_achievements_progress CHECK (progress >= 0),
    -- An unlock without a timestamp could never be ordered or displayed, and a
    -- timestamp without an unlock is meaningless.
    CONSTRAINT ck_player_achievements_unlock CHECK (
        (unlocked = TRUE AND unlocked_at IS NOT NULL)
        OR (unlocked = FALSE AND unlocked_at IS NULL)
    )
);

CREATE INDEX idx_player_achievements_user ON player_achievements (user_id);

COMMENT ON TABLE player_achievements IS
    'A player''s state against the milestone catalogue. Rows exist only for achievements that have been evaluated; absence means "not yet reached", and unlocked rows are never revoked.';

-- ---------------------------- player_streaks ---------------------------
CREATE TABLE player_streaks (
    id                 UUID    NOT NULL,
    user_id            UUID    NOT NULL,
    current_streak     INTEGER NOT NULL DEFAULT 0,
    longest_streak     INTEGER NOT NULL DEFAULT 0,
    -- A business date, not an instant: which day counts is a server decision.
    last_activity_date DATE    NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_streaks PRIMARY KEY (id),
    CONSTRAINT uq_player_streaks_user UNIQUE (user_id),
    CONSTRAINT fk_player_streaks_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_streaks_current CHECK (current_streak >= 0),
    -- The longest streak is a record of the current streak, so it can never
    -- fall below it.
    CONSTRAINT ck_player_streaks_longest CHECK (
        longest_streak >= 0 AND longest_streak >= current_streak
    ),
    CONSTRAINT ck_player_streaks_activity CHECK (
        last_activity_date IS NULL
        OR (current_streak > 0 AND longest_streak > 0)
    )
);

COMMENT ON TABLE player_streaks IS
    'Consecutive business days on which a player was active. One row per player, created on first activity. Updated lazily from the server clock; no scheduler touches it.';

-- ----------------------- streak_milestone_awards -----------------------
CREATE TABLE streak_milestone_awards (
    id             UUID                     NOT NULL,
    user_id        UUID                     NOT NULL,
    milestone_days INTEGER                  NOT NULL,
    xp_awarded     BIGINT                   NOT NULL DEFAULT 0,
    coin_awarded   BIGINT                   NOT NULL DEFAULT 0,
    awarded_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_streak_milestone_awards PRIMARY KEY (id),
    -- Why the retroactive duplicate is impossible: the pair can only be written
    -- once, so crossing a milestone again on a later, longer streak pays nothing.
    CONSTRAINT uq_streak_milestone_awards_user_milestone UNIQUE (user_id, milestone_days),
    CONSTRAINT fk_streak_milestone_awards_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_streak_milestone_awards_days CHECK (milestone_days > 0),
    CONSTRAINT ck_streak_milestone_awards_rewards CHECK (xp_awarded >= 0 AND coin_awarded >= 0)
);

COMMENT ON TABLE streak_milestone_awards IS
    'Which streak milestones a player has already been paid for. A 7-day milestone is paid once for the lifetime of the account, not once per 7-day streak.';

-- ------------------------ player_daily_counters -----------------------
CREATE TABLE player_daily_counters (
    id            UUID    NOT NULL,
    user_id       UUID    NOT NULL,
    business_date DATE    NOT NULL,
    metric        VARCHAR(32) NOT NULL,
    metric_value  BIGINT  NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_daily_counters PRIMARY KEY (id),
    CONSTRAINT uq_player_daily_counters_user_date_metric UNIQUE (user_id, business_date, metric),
    CONSTRAINT fk_player_daily_counters_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_daily_counters_metric CHECK (
        metric IN ('MISSIONS_COMPLETED', 'PUZZLES_SOLVED', 'XP_EARNED',
                   'COINS_EARNED', 'BOSSES_DEFEATED')
    ),
    -- Counters only ever go up, and a negative total has no meaning.
    CONSTRAINT ck_player_daily_counters_value CHECK (metric_value >= 0)
);

CREATE INDEX idx_player_daily_counters_user_date ON player_daily_counters (user_id, business_date);

COMMENT ON TABLE player_daily_counters IS
    'Real events recorded against one business date, incremented inside the transaction that performed the event. The daily challenge system reads these; it never asks a client what happened.';

-- ------------------- daily_challenge_definitions -----------------------
-- The rotating pool. Today's three are selected from these deterministically,
-- so the pool is authored and reviewable and no requirement can ever be
-- generated that the game cannot actually satisfy.
CREATE TABLE daily_challenge_definitions (
    id                UUID                     NOT NULL,
    code              VARCHAR(64)              NOT NULL,
    title             VARCHAR(120)             NOT NULL,
    description       VARCHAR(500)             NOT NULL,
    requirement_type  VARCHAR(32)              NOT NULL,
    requirement_value INTEGER                  NOT NULL,
    xp_reward         BIGINT                   NOT NULL DEFAULT 0,
    coin_reward       BIGINT                   NOT NULL DEFAULT 0,
    sort_order        INTEGER                  NOT NULL DEFAULT 0,
    active            BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_daily_challenge_definitions PRIMARY KEY (id),
    CONSTRAINT uq_daily_challenge_definitions_code UNIQUE (code),
    CONSTRAINT ck_daily_challenge_definitions_requirement_type CHECK (
        requirement_type IN ('MISSIONS_COMPLETED', 'PUZZLES_SOLVED', 'XP_EARNED',
                             'COINS_EARNED', 'BOSSES_DEFEATED')
    ),
    CONSTRAINT ck_daily_challenge_definitions_value CHECK (requirement_value > 0),
    CONSTRAINT ck_daily_challenge_definitions_rewards CHECK (xp_reward >= 0 AND coin_reward >= 0),
    CONSTRAINT ck_daily_challenge_definitions_active CHECK (active IN (TRUE, FALSE))
);

COMMENT ON TABLE daily_challenge_definitions IS
    'The authored pool of daily objectives. Selection is deterministic per business date, so every player sees the same three and none of them is randomly impossible.';

-- --------------------------- daily_challenges --------------------------
-- The concrete set for one date, materialised once per date. Rows are frozen
-- copies rather than a view onto the definitions, so retuning the pool mid-day
-- cannot change what a player is already being asked for or already paid for.
CREATE TABLE daily_challenges (
    id                UUID                     NOT NULL,
    challenge_date    DATE                     NOT NULL,
    code              VARCHAR(64)              NOT NULL,
    definition_id     UUID                     NOT NULL,
    title             VARCHAR(120)             NOT NULL,
    description       VARCHAR(500)             NOT NULL,
    requirement_type  VARCHAR(32)              NOT NULL,
    requirement_value INTEGER                  NOT NULL,
    xp_reward         BIGINT                   NOT NULL DEFAULT 0,
    coin_reward       BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_daily_challenges PRIMARY KEY (id),
    -- The same objective cannot appear twice on one date. This is the
    -- constraint that makes duplicate-free selection a database property
    -- rather than an application convention.
    CONSTRAINT uq_daily_challenges_date_code UNIQUE (challenge_date, code),
    CONSTRAINT fk_daily_challenges_definition FOREIGN KEY (definition_id)
        REFERENCES daily_challenge_definitions (id) ON DELETE CASCADE,
    CONSTRAINT ck_daily_challenges_requirement_type CHECK (
        requirement_type IN ('MISSIONS_COMPLETED', 'PUZZLES_SOLVED', 'XP_EARNED',
                             'COINS_EARNED', 'BOSSES_DEFEATED')
    ),
    CONSTRAINT ck_daily_challenges_value CHECK (requirement_value > 0),
    CONSTRAINT ck_daily_challenges_rewards CHECK (xp_reward >= 0 AND coin_reward >= 0)
);

CREATE INDEX idx_daily_challenges_date ON daily_challenges (challenge_date);

COMMENT ON TABLE daily_challenges IS
    'The frozen set of objectives for one business date, created on first request of that day in the configured business timezone.';

-- ---------------------- daily_challenge_progress -----------------------
CREATE TABLE daily_challenge_progress (
    id                  UUID                     NOT NULL,
    user_id             UUID                     NOT NULL,
    daily_challenge_id  UUID                     NOT NULL,
    progress            INTEGER                  NOT NULL DEFAULT 0,
    completed           BOOLEAN                  NOT NULL DEFAULT FALSE,
    completed_at        TIMESTAMP WITH TIME ZONE NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_daily_challenge_progress PRIMARY KEY (id),
    -- One progress row per player per objective. A replayed evaluation updates
    -- this row; it cannot create a second one, so the reward paid on
    -- completion can only ever be triggered once.
    CONSTRAINT uq_daily_challenge_progress_user_challenge UNIQUE (user_id, daily_challenge_id),
    CONSTRAINT fk_daily_challenge_progress_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_daily_challenge_progress_challenge FOREIGN KEY (daily_challenge_id)
        REFERENCES daily_challenges (id) ON DELETE CASCADE,
    CONSTRAINT ck_daily_challenge_progress_progress CHECK (progress >= 0),
    CONSTRAINT ck_daily_challenge_progress_completion CHECK (
        (completed = TRUE AND completed_at IS NOT NULL)
        OR (completed = FALSE AND completed_at IS NULL)
    )
);

CREATE INDEX idx_daily_challenge_progress_user ON daily_challenge_progress (user_id);

COMMENT ON TABLE daily_challenge_progress IS
    'A player''s progress toward one objective. The value is copied from player_daily_counters by the server; there is no endpoint that writes it.';

-- =====================================================================
-- Seeds
-- =====================================================================

-- ------------------------------ achievements ---------------------------
-- Thirty milestones across eight categories. Thresholds are chosen against the
-- shipped content: 15 missions, 30 items, 12 skills with a maximum level of 5,
-- 5 equipment slots and 5 bosses.
INSERT INTO achievements (id, code, name, description, category, requirement_type,
                          requirement_value, xp_reward, coin_reward, icon, sort_order) VALUES
-- MISSIONS ------------------------------------------------------------------
('51111111-0000-4000-8000-000000000001', 'FIRST_STEPS', 'First Steps',
 'Complete your first mission. Every operation starts with one door.',
 'MISSIONS', 'MISSIONS_COMPLETED', 1, 50, 25, '◈', 10),
('51111111-0000-4000-8000-000000000002', 'FIELD_OPERATIVE', 'Field Operative',
 'Complete 10 missions.', 'MISSIONS', 'MISSIONS_COMPLETED', 10, 150, 75, '◈', 20),
('51111111-0000-4000-8000-000000000003', 'VETERAN_HACKER', 'Veteran Hacker',
 'Complete 25 missions.', 'MISSIONS', 'MISSIONS_COMPLETED', 25, 400, 200, '◈', 30),
('51111111-0000-4000-8000-000000000004', 'MISSION_MASTER', 'Mission Master',
 'Complete 50 missions.', 'MISSIONS', 'MISSIONS_COMPLETED', 50, 900, 450, '◈', 40),

-- PUZZLES ------------------------------------------------------------------
('51111111-0000-4000-8000-000000000005', 'FIRST_SOLVE', 'First Solve',
 'Solve your first puzzle.', 'PUZZLES', 'PUZZLES_SOLVED', 1, 40, 20, '◇', 50),
('51111111-0000-4000-8000-000000000006', 'PUZZLE_MASTER', 'Puzzle Master',
 'Solve 50 puzzles.', 'PUZZLES', 'PUZZLES_SOLVED', 50, 600, 300, '◇', 60),
('51111111-0000-4000-8000-000000000007', 'CIPHER_BREAKER', 'Cipher Breaker',
 'Solve 10 cipher puzzles.', 'PUZZLES', 'CIPHER_SOLVED', 10, 200, 100, '◆', 70),
('51111111-0000-4000-8000-000000000008', 'LOGIC_SPECIALIST', 'Logic Specialist',
 'Solve 10 logic puzzles.', 'PUZZLES', 'LOGIC_SOLVED', 10, 200, 100, '◆', 80),
('51111111-0000-4000-8000-000000000009', 'PATTERN_ANALYST', 'Pattern Analyst',
 'Solve 10 pattern puzzles.', 'PUZZLES', 'PATTERN_SOLVED', 10, 200, 100, '◆', 90),
('51111111-0000-4000-8000-000000000010', 'SEQUENCE_READER', 'Sequence Reader',
 'Solve 10 sequence puzzles.', 'PUZZLES', 'SEQUENCE_SOLVED', 10, 200, 100, '◆', 100),
('51111111-0000-4000-8000-000000000011', 'TIME_BENDER', 'Time Bender',
 'Solve 5 timed puzzles before the window closes.', 'PUZZLES', 'TIMED_SOLVED', 5, 250, 125, '◆', 110),

-- PROGRESSION --------------------------------------------------------------
('51111111-0000-4000-8000-000000000012', 'RISING_HACKER', 'Rising Hacker',
 'Reach level 5.', 'PROGRESSION', 'PLAYER_LEVEL', 5, 100, 50, '▲', 120),
('51111111-0000-4000-8000-000000000013', 'ELITE_OPERATIVE', 'Elite Operative',
 'Reach level 10.', 'PROGRESSION', 'PLAYER_LEVEL', 10, 300, 150, '▲', 130),
('51111111-0000-4000-8000-000000000014', 'VETERAN', 'Veteran',
 'Reach level 25.', 'PROGRESSION', 'PLAYER_LEVEL', 25, 750, 375, '▲', 140),
('51111111-0000-4000-8000-000000000015', 'LEGEND', 'Legend',
 'Reach level 50.', 'PROGRESSION', 'PLAYER_LEVEL', 50, 2000, 1000, '▲', 150),

-- ECONOMY ------------------------------------------------------------------
('51111111-0000-4000-8000-000000000016', 'FIRST_EARNINGS', 'First Earnings',
 'Earn 100 coins in total.', 'ECONOMY', 'COINS_EARNED', 100, 80, 0, '◉', 160),
('51111111-0000-4000-8000-000000000017', 'SELF_FUNDED', 'Self Funded',
 'Earn 1,000 coins in total.', 'ECONOMY', 'COINS_EARNED', 1000, 300, 0, '◉', 170),
('51111111-0000-4000-8000-000000000018', 'COLLECTOR', 'Collector',
 'Own 5 different items.', 'ECONOMY', 'ITEMS_OWNED', 5, 120, 60, '◉', 180),
('51111111-0000-4000-8000-000000000019', 'WAREHOUSE', 'Warehouse',
 'Own 15 different items.', 'ECONOMY', 'ITEMS_OWNED', 15, 400, 200, '◉', 190),
('51111111-0000-4000-8000-000000000020', 'HOARDER', 'Hoarder',
 'Own every item in the catalogue.', 'ECONOMY', 'ITEMS_OWNED', 30, 1200, 600, '◉', 200),

-- EQUIPMENT ----------------------------------------------------------------
('51111111-0000-4000-8000-000000000021', 'FIRST_EQUIP', 'First Equip',
 'Equip an item in any slot.', 'EQUIPMENT', 'ITEMS_EQUIPPED', 1, 60, 30, '▣', 210),
('51111111-0000-4000-8000-000000000022', 'FULLY_LOADED', 'Fully Loaded',
 'Fill all five equipment slots at once.', 'EQUIPMENT', 'ITEMS_EQUIPPED', 5, 350, 175, '▣', 220),

-- SKILLS -------------------------------------------------------------------
('51111111-0000-4000-8000-000000000023', 'FIRST_UPGRADE', 'First Upgrade',
 'Upgrade your first skill.', 'SKILLS', 'SKILLS_UNLOCKED', 1, 70, 35, '⬢', 230),
('51111111-0000-4000-8000-000000000024', 'SPECIALIST', 'Specialist',
 'Take one skill to level 5.', 'SKILLS', 'SKILL_LEVEL', 5, 250, 125, '⬢', 240),
('51111111-0000-4000-8000-000000000025', 'SKILL_MASTER', 'Skill Master',
 'Take one skill to its maximum level of 5.', 'SKILLS', 'SKILL_LEVEL', 5, 500, 250, '⬢', 250),

-- BOSSES -------------------------------------------------------------------
('51111111-0000-4000-8000-000000000026', 'FIRST_BLOOD', 'First Blood',
 'Defeat your first boss.', 'BOSSES', 'BOSSES_DEFEATED', 1, 200, 100, '☠', 260),
('51111111-0000-4000-8000-000000000027', 'BOSS_HUNTER', 'Boss Hunter',
 'Defeat 5 bosses.', 'BOSSES', 'BOSSES_DEFEATED', 5, 600, 300, '☠', 270),
('51111111-0000-4000-8000-000000000028', 'NIGHTMARE_SURVIVOR', 'Nightmare Survivor',
 'Defeat 10 bosses.', 'BOSSES', 'BOSSES_DEFEATED', 10, 1500, 750, '☠', 280),

-- DAILY --------------------------------------------------------------------
('51111111-0000-4000-8000-000000000029', 'FIRST_DAY', 'First Day',
 'Complete your first daily challenge.', 'DAILY', 'DAILY_CHALLENGES_COMPLETED', 1, 100, 50, '◐', 290),
('51111111-0000-4000-8000-000000000030', 'DAILY_DEVOTEE', 'Daily Devotee',
 'Complete 30 daily challenges.', 'DAILY', 'DAILY_CHALLENGES_COMPLETED', 30, 800, 400, '◐', 300);

-- SPECIALIST and SKILL_MASTER deliberately share requirement SKILL_LEVEL = 5.
-- That is not a copy-paste error: 5 is the maximum level in the shipped tree,
-- so "level 5 on one skill" and "a skill maxed out" are the same event today.
-- They are separate rows so the two can diverge the moment a skill is given a
-- sixth level, and the tree's max_level remains the source of truth in code.

-- ---------------------- daily_challenge_definitions --------------------
-- Nine objectives, three drawn per day. Requirements are small enough to be
-- reachable in a sitting and varied enough that no two days feel identical.
INSERT INTO daily_challenge_definitions (id, code, title, description,
                                         requirement_type, requirement_value,
                                         xp_reward, coin_reward, sort_order) VALUES
('61111111-0000-4000-8000-000000000001', 'DAILY_MISSIONS_1', 'Sweep the Board',
 'Complete 1 mission.', 'MISSIONS_COMPLETED', 1, 50, 25, 10),
('61111111-0000-4000-8000-000000000002', 'DAILY_MISSIONS_2', 'Two Doors',
 'Complete 2 missions.', 'MISSIONS_COMPLETED', 2, 75, 35, 20),
('61111111-0000-4000-8000-000000000003', 'DAILY_MISSIONS_3', 'Busy Night',
 'Complete 3 missions.', 'MISSIONS_COMPLETED', 3, 100, 50, 30),
('61111111-0000-4000-8000-000000000004', 'DAILY_PUZZLES_2', 'Two Locks',
 'Solve 2 puzzles.', 'PUZZLES_SOLVED', 2, 60, 30, 40),
('61111111-0000-4000-8000-000000000005', 'DAILY_PUZZLES_3', 'Three Locks',
 'Solve 3 puzzles.', 'PUZZLES_SOLVED', 3, 90, 45, 50),
('61111111-0000-4000-8000-000000000006', 'DAILY_XP_100', 'Warmed Up',
 'Earn 100 XP.', 'XP_EARNED', 100, 80, 40, 60),
('61111111-0000-4000-8000-000000000007', 'DAILY_XP_250', 'Heavy Traffic',
 'Earn 250 XP.', 'XP_EARNED', 250, 140, 70, 70),
('61111111-0000-4000-8000-000000000008', 'DAILY_COINS_100', 'Paid Work',
 'Earn 100 coins.', 'COINS_EARNED', 100, 70, 35, 80),
('61111111-0000-4000-8000-000000000009', 'DAILY_BOSS_1', 'Face Something',
 'Defeat 1 boss.', 'BOSSES_DEFEATED', 1, 150, 75, 90);

COMMENT ON TABLE daily_challenge_definitions IS
    'The authored pool of daily objectives. Selection is deterministic per business date, so every player sees the same three and none of them is randomly impossible.';

-- --------------------------- streak milestones -------------------------
-- Milestones live in code, not here: they are a short fixed list rather than
-- content, and RewardService is the only thing that should pay them. The table
-- that stops a repeat payment is streak_milestone_awards, above.
