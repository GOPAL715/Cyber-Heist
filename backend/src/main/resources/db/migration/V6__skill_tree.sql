-- =====================================================================
-- Cyber Heist - Phase 5: skill tree and player abilities
--
-- Four new tables plus one column on player_profiles:
--   skills               the skill definitions, grouped into branches
--   skill_levels         what each level costs and what it grants (the balance)
--   skill_prerequisites  the dependency edges between skills
--   player_skills        what a player has actually unlocked
--   player_profiles.skill_points  the unspent currency
--
-- V1-V5 are never modified; Phase 5 is entirely additive.
--
-- Design notes
-- ------------
-- Effects live in `skill_levels`, not in Java, so rebalancing is a migration
-- rather than a code change and a service rewrite. There is no formula
-- execution and nothing user-defined: every row names one of five controlled
-- effect types with an integer percentage, the same shape Phase 4 uses for
-- equipment, so the two systems aggregate through one code path.
--
-- `player_skills` stores only what a player has *earned*. Absence of a row means
-- level 0. That keeps the table the size of what was actually unlocked instead
-- of pre-seeding a row per skill per player, and it means a newly added skill
-- does not need backfilling.
-- =====================================================================

-- ----------------------------- skills ---------------------------------
CREATE TABLE skills (
    id          UUID                     NOT NULL,
    code        VARCHAR(64)              NOT NULL,
    name        VARCHAR(120)             NOT NULL,
    description VARCHAR(500)             NOT NULL,
    branch      VARCHAR(20)              NOT NULL,
    max_level   INTEGER                  NOT NULL,
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_skills PRIMARY KEY (id),
    CONSTRAINT uq_skills_code UNIQUE (code),
    CONSTRAINT ck_skills_branch CHECK (
        branch IN ('SPEED', 'INTELLIGENCE', 'DEFENSE', 'NETWORK')
    ),
    -- A skill with no levels could never be learned, so the column is bounded.
    CONSTRAINT ck_skills_max_level CHECK (max_level > 0 AND max_level <= 100),
    CONSTRAINT ck_skills_active CHECK (active IN (TRUE, FALSE))
);

COMMENT ON TABLE skills IS 'Server-owned skill definitions. Players unlock them; they never define them.';
COMMENT ON COLUMN skills.max_level IS 'Highest level this skill can reach. A row must exist in skill_levels for each level up to it.';

CREATE INDEX idx_skills_active ON skills (active);
CREATE INDEX idx_skills_branch ON skills (branch);

-- --------------------------- skill_levels -----------------------------
CREATE TABLE skill_levels (
    id               UUID        NOT NULL,
    skill_id         UUID        NOT NULL,
    level            INTEGER     NOT NULL,
    skill_point_cost INTEGER     NOT NULL,
    effect_type      VARCHAR(32) NOT NULL,
    effect_value     INTEGER     NOT NULL,
    CONSTRAINT pk_skill_levels PRIMARY KEY (id),
    -- One definition per level, so a lookup is unambiguous and the effect of a
    -- level cannot depend on insertion order.
    CONSTRAINT uq_skill_levels_skill_level UNIQUE (skill_id, level),
    CONSTRAINT fk_skill_levels_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_levels_level CHECK (level > 0),
    -- Level 1 must be affordable, so the cost is at least one point.
    CONSTRAINT ck_skill_levels_cost CHECK (skill_point_cost > 0),
    -- The same five controlled types the equipment catalogue uses.
    CONSTRAINT ck_skill_levels_effect_type CHECK (
        effect_type IN ('MISSION_SPEED', 'EXPERIENCE_BONUS', 'COIN_BONUS',
                        'ENERGY_EFFICIENCY', 'PUZZLE_BONUS')
    ),
    CONSTRAINT ck_skill_levels_effect_value CHECK (effect_value >= 0 AND effect_value <= 100)
);

COMMENT ON TABLE skill_levels IS 'Per-level cost and bonus. The balance lives here, not in Java.';
CREATE INDEX idx_skill_levels_skill_id ON skill_levels (skill_id);

-- ------------------------ skill_prerequisites -------------------------
CREATE TABLE skill_prerequisites (
    skill_id           UUID    NOT NULL,
    required_skill_id  UUID    NOT NULL,
    required_level     INTEGER NOT NULL,
    CONSTRAINT pk_skill_prerequisites PRIMARY KEY (skill_id, required_skill_id),
    CONSTRAINT fk_skill_prerequisites_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT fk_skill_prerequisites_required FOREIGN KEY (required_skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_prerequisites_no_self CHECK (skill_id <> required_skill_id),
    CONSTRAINT ck_skill_prerequisites_required_level CHECK (required_level > 0)
);

COMMENT ON TABLE skill_prerequisites IS 'Dependency edges. A skill is locked until every prerequisite is met.';
-- Self-reference and duplicate edges are impossible above. Cycles *across*
-- several rows cannot be expressed as a CHECK constraint; SkillTreeService
-- rejects a cyclic graph at startup rather than letting a player deadlock
-- against an unreachable skill.

-- --------------------------- player_skills ---------------------------
CREATE TABLE player_skills (
    id             UUID                     NOT NULL,
    user_id        UUID                     NOT NULL,
    skill_id       UUID                     NOT NULL,
    current_level  INTEGER                  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_skills PRIMARY KEY (id),
    -- One row per player per skill: the representation of "how far along".
    CONSTRAINT uq_player_skills_user_skill UNIQUE (user_id, skill_id),
    CONSTRAINT fk_player_skills_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_player_skills_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_skills_current_level CHECK (current_level > 0)
);

COMMENT ON TABLE player_skills IS 'Unlocked skills. No row means level 0; rows are created on first upgrade.';
CREATE INDEX idx_player_skills_user_id ON player_skills (user_id);
CREATE INDEX idx_player_skills_skill_id ON player_skills (skill_id);

-- -------------------- player_profiles.skill_points --------------------
-- DEFAULT 0 and NOT NULL: existing players are given a safe zero by the ALTER
-- itself, so no backfill statement is needed and no row can be left null.
ALTER TABLE player_profiles
    ADD COLUMN skill_points INTEGER NOT NULL DEFAULT 0;

ALTER TABLE player_profiles
    ADD CONSTRAINT ck_player_profiles_skill_points CHECK (skill_points >= 0);

COMMENT ON COLUMN player_profiles.skill_points IS
    'Unspent skill points. Granted one per level gained; spent only by SkillTreeService.';

-- =====================================================================
-- Seed: four branches of three skills, five levels each.
--
-- Economy. A player earns one point per level gained, and the level cap is
-- 100, so 99 points exist across a full run. Each skill costs 1+1+2+2+3 = 9
-- points to max, and there are 12 skills, for 108 points of content. The tree
-- is therefore deliberately larger than the currency: a player cannot max
-- everything and has to choose. Requiring levels 2 and 3 of the previous skill
-- compounds that, so a single branch cannot be rushed on its own.
--
-- Each skill grants exactly one effect per level, and the value rises with the
-- level, so "one level" always means "a little more of the same thing".
--
-- Two of the five effect types - MISSION_SPEED and PUZZLE_BONUS - are stored,
-- aggregated, capped and displayed but do not yet change any mechanic. See the
-- README: there is no honest lever for them, and inventing one to justify the
-- number would be worse than reporting it honestly.
--
-- Skill ids are fixed so tests and later phases can address them stably.
-- =====================================================================

INSERT INTO skills (id, code, name, description, branch, max_level, active) VALUES
-- --------------------------------- SPEED --------------------------------
('31111111-0000-4000-8000-000000000001', 'RAPID_EXECUTION', 'Rapid Execution',
 'Trim every avoidable second from a run. Muscle memory, applied to keystrokes.',
 'SPEED', 5, TRUE),
('31111111-0000-4000-8000-000000000002', 'QUICK_RESPONSE', 'Quick Response',
 'Anticipate the alarm instead of waiting for it. Faster recovery between systems.',
 'SPEED', 5, TRUE),
('31111111-0000-4000-8000-000000000003', 'OVERCLOCK', 'Overclock',
 'Hold the clock past its rated limit. The hardware will cope. Probably.',
 'SPEED', 5, TRUE),
-- ---------------------------- INTELLIGENCE ----------------------------
('31111111-0000-4000-8000-000000000004', 'CIPHER_MASTERY', 'Cipher Mastery',
 'Read a cipher like prose. Shallow structure becomes obvious once you know where to look.',
 'INTELLIGENCE', 5, TRUE),
('31111111-0000-4000-8000-000000000005', 'PATTERN_ANALYSIS', 'Pattern Analysis',
 'Every target repeats itself. Find the shape and the answer is already written.',
 'INTELLIGENCE', 5, TRUE),
('31111111-0000-4000-8000-000000000006', 'NEURAL_PROCESSING', 'Neural Processing',
 'Run the trace in parallel with your hands. Two minds, one rig.',
 'INTELLIGENCE', 5, TRUE),
-- ------------------------------- DEFENSE -------------------------------
('31111111-0000-4000-8000-000000000007', 'ENERGY_SHIELD', 'Energy Shield',
 'Absorb the first spike before it reaches the core. Cheaper than the alternative.',
 'DEFENSE', 5, TRUE),
('31111111-0000-4000-8000-000000000008', 'EFFICIENT_SYSTEMS', 'Efficient Systems',
 'Nothing runs that does not need to. The savings accumulate quietly.',
 'DEFENSE', 5, TRUE),
('31111111-0000-4000-8000-000000000009', 'HARDENED_CORE', 'Hardened Core',
 'A core that shrugs off the load. Built to be run hot, for a long time.',
 'DEFENSE', 5, TRUE),
-- ------------------------------- NETWORK -------------------------------
('31111111-0000-4000-8000-000000000010', 'SIGNAL_TRACING', 'Signal Tracing',
 'Follow the leak backwards until it points at something worth stealing.',
 'NETWORK', 5, TRUE),
('31111111-0000-4000-8000-000000000011', 'PACKET_ANALYSIS', 'Packet Analysis',
 'Read the traffic between the lines. Timing tells on a careless sender.',
 'NETWORK', 5, TRUE),
('31111111-0000-4000-8000-000000000012', 'DEEP_ACCESS', 'Deep Access',
 'Stop asking permission from systems that never had the authority to refuse.',
 'NETWORK', 5, TRUE);

-- ---------------------------- skill_levels ----------------------------
-- One block per skill, levels 1-5. Costs run 1,1,2,2,3 across every skill so
-- early levels are reachable for a new player and the last level is a
-- commitment rather than an afterthought.
INSERT INTO skill_levels (id, skill_id, level, skill_point_cost, effect_type, effect_value) VALUES
-- RAPID_EXECUTION: MISSION_SPEED 2/4/6/8/10 (displayed only in Phase 5)
('32111111-0000-4000-8000-000000000001', '31111111-0000-4000-8000-000000000001', 1, 1, 'MISSION_SPEED',       2),
('32111111-0000-4000-8000-000000000002', '31111111-0000-4000-8000-000000000001', 2, 1, 'MISSION_SPEED',       4),
('32111111-0000-4000-8000-000000000003', '31111111-0000-4000-8000-000000000001', 3, 2, 'MISSION_SPEED',       6),
('32111111-0000-4000-8000-000000000004', '31111111-0000-4000-8000-000000000001', 4, 2, 'MISSION_SPEED',       8),
('32111111-0000-4000-8000-000000000005', '31111111-0000-4000-8000-000000000001', 5, 3, 'MISSION_SPEED',      10),
-- QUICK_RESPONSE: ENERGY_EFFICIENCY 3/5/7/9/12
('32111111-0000-4000-8000-000000000006', '31111111-0000-4000-8000-000000000002', 1, 1, 'ENERGY_EFFICIENCY',   3),
('32111111-0000-4000-8000-000000000007', '31111111-0000-4000-8000-000000000002', 2, 1, 'ENERGY_EFFICIENCY',   5),
('32111111-0000-4000-8000-000000000008', '31111111-0000-4000-8000-000000000002', 3, 2, 'ENERGY_EFFICIENCY',   7),
('32111111-0000-4000-8000-000000000009', '31111111-0000-4000-8000-000000000002', 4, 2, 'ENERGY_EFFICIENCY',   9),
('32111111-0000-4000-8000-000000000010', '31111111-0000-4000-8000-000000000002', 5, 3, 'ENERGY_EFFICIENCY',  12),
-- OVERCLOCK: ENERGY_EFFICIENCY 5/7/9/11/15
('32111111-0000-4000-8000-000000000011', '31111111-0000-4000-8000-000000000003', 1, 1, 'ENERGY_EFFICIENCY',   5),
('32111111-0000-4000-8000-000000000012', '31111111-0000-4000-8000-000000000003', 2, 1, 'ENERGY_EFFICIENCY',   7),
('32111111-0000-4000-8000-000000000013', '31111111-0000-4000-8000-000000000003', 3, 2, 'ENERGY_EFFICIENCY',   9),
('32111111-0000-4000-8000-000000000014', '31111111-0000-4000-8000-000000000003', 4, 2, 'ENERGY_EFFICIENCY',  11),
('32111111-0000-4000-8000-000000000015', '31111111-0000-4000-8000-000000000003', 5, 3, 'ENERGY_EFFICIENCY',  15),
-- CIPHER_MASTERY: PUZZLE_BONUS 2/4/6/8/10 (displayed only in Phase 5)
('32111111-0000-4000-8000-000000000016', '31111111-0000-4000-8000-000000000004', 1, 1, 'PUZZLE_BONUS',        2),
('32111111-0000-4000-8000-000000000017', '31111111-0000-4000-8000-000000000004', 2, 1, 'PUZZLE_BONUS',        4),
('32111111-0000-4000-8000-000000000018', '31111111-0000-4000-8000-000000000004', 3, 2, 'PUZZLE_BONUS',        6),
('32111111-0000-4000-8000-000000000019', '31111111-0000-4000-8000-000000000004', 4, 2, 'PUZZLE_BONUS',        8),
('32111111-0000-4000-8000-000000000020', '31111111-0000-4000-8000-000000000004', 5, 3, 'PUZZLE_BONUS',       10),
-- PATTERN_ANALYSIS: EXPERIENCE_BONUS 3/6/9/12/15
('32111111-0000-4000-8000-000000000021', '31111111-0000-4000-8000-000000000005', 1, 1, 'EXPERIENCE_BONUS',    3),
('32111111-0000-4000-8000-000000000022', '31111111-0000-4000-8000-000000000005', 2, 1, 'EXPERIENCE_BONUS',    6),
('32111111-0000-4000-8000-000000000023', '31111111-0000-4000-8000-000000000005', 3, 2, 'EXPERIENCE_BONUS',    9),
('32111111-0000-4000-8000-000000000024', '31111111-0000-4000-8000-000000000005', 4, 2, 'EXPERIENCE_BONUS',   12),
('32111111-0000-4000-8000-000000000025', '31111111-0000-4000-8000-000000000005', 5, 3, 'EXPERIENCE_BONUS',   15),
-- NEURAL_PROCESSING: EXPERIENCE_BONUS 5/8/11/14/18
('32111111-0000-4000-8000-000000000026', '31111111-0000-4000-8000-000000000006', 1, 1, 'EXPERIENCE_BONUS',    5),
('32111111-0000-4000-8000-000000000027', '31111111-0000-4000-8000-000000000006', 2, 1, 'EXPERIENCE_BONUS',    8),
('32111111-0000-4000-8000-000000000028', '31111111-0000-4000-8000-000000000006', 3, 2, 'EXPERIENCE_BONUS',   11),
('32111111-0000-4000-8000-000000000029', '31111111-0000-4000-8000-000000000006', 4, 2, 'EXPERIENCE_BONUS',   14),
('32111111-0000-4000-8000-000000000030', '31111111-0000-4000-8000-000000000006', 5, 3, 'EXPERIENCE_BONUS',   18),
-- ENERGY_SHIELD: ENERGY_EFFICIENCY 3/6/9/12/15
('32111111-0000-4000-8000-000000000031', '31111111-0000-4000-8000-000000000007', 1, 1, 'ENERGY_EFFICIENCY',   3),
('32111111-0000-4000-8000-000000000032', '31111111-0000-4000-8000-000000000007', 2, 1, 'ENERGY_EFFICIENCY',   6),
('32111111-0000-4000-8000-000000000033', '31111111-0000-4000-8000-000000000007', 3, 2, 'ENERGY_EFFICIENCY',   9),
('32111111-0000-4000-8000-000000000034', '31111111-0000-4000-8000-000000000007', 4, 2, 'ENERGY_EFFICIENCY',  12),
('32111111-0000-4000-8000-000000000035', '31111111-0000-4000-8000-000000000007', 5, 3, 'ENERGY_EFFICIENCY',  15),
-- EFFICIENT_SYSTEMS: COIN_BONUS 3/6/9/12/14
('32111111-0000-4000-8000-000000000036', '31111111-0000-4000-8000-000000000008', 1, 1, 'COIN_BONUS',           3),
('32111111-0000-4000-8000-000000000037', '31111111-0000-4000-8000-000000000008', 2, 1, 'COIN_BONUS',           6),
('32111111-0000-4000-8000-000000000038', '31111111-0000-4000-8000-000000000008', 3, 2, 'COIN_BONUS',           9),
('32111111-0000-4000-8000-000000000039', '31111111-0000-4000-8000-000000000008', 4, 2, 'COIN_BONUS',          12),
('32111111-0000-4000-8000-000000000040', '31111111-0000-4000-8000-000000000008', 5, 3, 'COIN_BONUS',          14),
-- HARDENED_CORE: COIN_BONUS 5/8/11/14/18
('32111111-0000-4000-8000-000000000041', '31111111-0000-4000-8000-000000000009', 1, 1, 'COIN_BONUS',           5),
('32111111-0000-4000-8000-000000000042', '31111111-0000-4000-8000-000000000009', 2, 1, 'COIN_BONUS',           8),
('32111111-0000-4000-8000-000000000043', '31111111-0000-4000-8000-000000000009', 3, 2, 'COIN_BONUS',          11),
('32111111-0000-4000-8000-000000000044', '31111111-0000-4000-8000-000000000009', 4, 2, 'COIN_BONUS',          14),
('32111111-0000-4000-8000-000000000045', '31111111-0000-4000-8000-000000000009', 5, 3, 'COIN_BONUS',          18),
-- SIGNAL_TRACING: COIN_BONUS 2/4/6/8/10
('32111111-0000-4000-8000-000000000046', '31111111-0000-4000-8000-000000000010', 1, 1, 'COIN_BONUS',           2),
('32111111-0000-4000-8000-000000000047', '31111111-0000-4000-8000-000000000010', 2, 1, 'COIN_BONUS',           4),
('32111111-0000-4000-8000-000000000048', '31111111-0000-4000-8000-000000000010', 3, 2, 'COIN_BONUS',           6),
('32111111-0000-4000-8000-000000000049', '31111111-0000-4000-8000-000000000010', 4, 2, 'COIN_BONUS',           8),
('32111111-0000-4000-8000-000000000050', '31111111-0000-4000-8000-000000000010', 5, 3, 'COIN_BONUS',          10),
-- PACKET_ANALYSIS: EXPERIENCE_BONUS 2/4/6/8/10
('32111111-0000-4000-8000-000000000051', '31111111-0000-4000-8000-000000000011', 1, 1, 'EXPERIENCE_BONUS',    2),
('32111111-0000-4000-8000-000000000052', '31111111-0000-4000-8000-000000000011', 2, 1, 'EXPERIENCE_BONUS',    4),
('32111111-0000-4000-8000-000000000053', '31111111-0000-4000-8000-000000000011', 3, 2, 'EXPERIENCE_BONUS',    6),
('32111111-0000-4000-8000-000000000054', '31111111-0000-4000-8000-000000000011', 4, 2, 'EXPERIENCE_BONUS',    8),
('32111111-0000-4000-8000-000000000055', '31111111-0000-4000-8000-000000000011', 5, 3, 'EXPERIENCE_BONUS',   10),
-- DEEP_ACCESS: EXPERIENCE_BONUS 5/8/11/14/18
('32111111-0000-4000-8000-000000000056', '31111111-0000-4000-8000-000000000012', 1, 1, 'EXPERIENCE_BONUS',    5),
('32111111-0000-4000-8000-000000000057', '31111111-0000-4000-8000-000000000012', 2, 1, 'EXPERIENCE_BONUS',    8),
('32111111-0000-4000-8000-000000000058', '31111111-0000-4000-8000-000000000012', 3, 2, 'EXPERIENCE_BONUS',   11),
('32111111-0000-4000-8000-000000000059', '31111111-0000-4000-8000-000000000012', 4, 2, 'EXPERIENCE_BONUS',   14),
('32111111-0000-4000-8000-000000000060', '31111111-0000-4000-8000-000000000012', 5, 3, 'EXPERIENCE_BONUS',   18);

-- ------------------------ skill_prerequisites ------------------------
-- Three linear chains, one per branch. Each skill needs level 2 of the one
-- before it, and the third needs level 3 of the second, so no branch can be
-- rushed by spending points in the wrong order.
--
-- `skill_id` is the gated skill; `required_skill_id` is what must be reached
-- first.
INSERT INTO skill_prerequisites (skill_id, required_skill_id, required_level) VALUES
('31111111-0000-4000-8000-000000000002', '31111111-0000-4000-8000-000000000001', 2),
('31111111-0000-4000-8000-000000000003', '31111111-0000-4000-8000-000000000002', 3),
('31111111-0000-4000-8000-000000000005', '31111111-0000-4000-8000-000000000004', 2),
('31111111-0000-4000-8000-000000000006', '31111111-0000-4000-8000-000000000005', 3),
('31111111-0000-4000-8000-000000000008', '31111111-0000-4000-8000-000000000007', 2),
('31111111-0000-4000-8000-000000000009', '31111111-0000-4000-8000-000000000008', 3),
('31111111-0000-4000-8000-000000000011', '31111111-0000-4000-8000-000000000010', 2),
('31111111-0000-4000-8000-000000000012', '31111111-0000-4000-8000-000000000011', 3);