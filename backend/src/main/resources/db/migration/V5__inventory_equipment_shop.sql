-- =====================================================================
-- Cyber Heist - Phase 4: item catalogue, inventory, equipment and shop
--
-- Four tables:
--   items              server-owned catalogue; no player state lives here
--   item_effects       typed per-item bonuses (percent)
--   player_inventory   ownership: one row per (player, item)
--   player_equipment   loadout: one row per (player, slot)
--
-- Hibernate runs with ddl-auto=validate, so this file is the only place the
-- schema is defined. Every rule the application also enforces in Java is
-- repeated here as a CHECK constraint, because the database is the last line
-- of defence against a bug or a second code path bypassing the service layer.
--
-- Equipment is deliberately one row per (player, item) with quantity fixed at
-- 1. Consumables are out of scope for Phase 4; the quantity column exists only
-- so a future consumable can be added without a schema redesign.
-- =====================================================================

-- ------------------------------- items --------------------------------
CREATE TABLE items (
    id             UUID                     NOT NULL,
    code           VARCHAR(64)              NOT NULL,
    name           VARCHAR(120)             NOT NULL,
    description    VARCHAR(500)             NOT NULL,
    category       VARCHAR(32)              NOT NULL,
    rarity         VARCHAR(16)              NOT NULL,
    equipment_slot VARCHAR(16)              NOT NULL,
    price          BIGINT                   NOT NULL,
    active         BOOLEAN                  NOT NULL DEFAULT TRUE,
    stackable      BOOLEAN                  NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_items PRIMARY KEY (id),
    CONSTRAINT uq_items_code UNIQUE (code),
    CONSTRAINT ck_items_category CHECK (
        category IN ('DEVICE', 'PROCESSOR', 'SECURITY', 'SOFTWARE', 'NETWORK')
    ),
    CONSTRAINT ck_items_rarity CHECK (
        rarity IN ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY')
    ),
    CONSTRAINT ck_items_slot CHECK (
        equipment_slot IN ('MAIN_DEVICE', 'PROCESSOR', 'SECURITY', 'SOFTWARE', 'NETWORK')
    ),
    -- A free item (the starter laptop) is the only legitimate zero.
    CONSTRAINT ck_items_price CHECK (price >= 0),
    CONSTRAINT ck_items_active CHECK (active IN (TRUE, FALSE)),
    CONSTRAINT ck_items_stackable CHECK (stackable IN (TRUE, FALSE))
);

COMMENT ON TABLE items IS 'Server-authoritative item catalogue. Players own items, this table defines them.';
COMMENT ON COLUMN items.price IS 'Price in coins. Read from this column only; never taken from a request.';
COMMENT ON COLUMN items.active IS 'Inactive items are hidden from the shop and cannot be bought.';

CREATE INDEX idx_items_active ON items (active);
CREATE INDEX idx_items_category ON items (category);
CREATE INDEX idx_items_rarity ON items (rarity);

-- ---------------------------- item_effects ----------------------------
-- Typed rather than a JSON blob: these values drive reward and energy math in
-- a later phase, so they need CHECK constraints, a foreign key and an index,
-- none of which are available for free-form JSON.
CREATE TABLE item_effects (
    id           UUID        NOT NULL,
    item_id      UUID        NOT NULL,
    effect_type  VARCHAR(32) NOT NULL,
    effect_value INTEGER     NOT NULL,
    CONSTRAINT pk_item_effects PRIMARY KEY (id),
    -- One row per effect per item, so aggregation can never double count.
    CONSTRAINT uq_item_effects_item_type UNIQUE (item_id, effect_type),
    CONSTRAINT fk_item_effects_item FOREIGN KEY (item_id)
        REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT ck_item_effects_type CHECK (
        effect_type IN ('MISSION_SPEED', 'EXPERIENCE_BONUS', 'COIN_BONUS',
                        'ENERGY_EFFICIENCY', 'PUZZLE_BONUS')
    ),
    -- Percentages. Bounded at 100 because a single item contributing more than
    -- a whole multiplier is a data-entry mistake, not a balance option.
    CONSTRAINT ck_item_effects_value CHECK (effect_value >= 0 AND effect_value <= 100)
);

COMMENT ON TABLE item_effects IS 'Per-item bonus percentages, aggregated and capped by EquipmentBonusService.';
CREATE INDEX idx_item_effects_item_id ON item_effects (item_id);

-- -------------------------- player_inventory --------------------------
CREATE TABLE player_inventory (
    id         UUID                     NOT NULL,
    user_id    UUID                     NOT NULL,
    item_id    UUID                     NOT NULL,
    quantity   INTEGER                  NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_inventory PRIMARY KEY (id),
    -- Phase 4 rule: one copy of any item per player. This is what makes a
    -- duplicate purchase a 409 rather than a second row.
    CONSTRAINT uq_player_inventory_user_item UNIQUE (user_id, item_id),
    CONSTRAINT fk_player_inventory_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT: an item still owned by a player must not be deletable from the
    -- catalogue, because doing so would orphan their inventory.
    CONSTRAINT fk_player_inventory_item FOREIGN KEY (item_id)
        REFERENCES items (id) ON DELETE RESTRICT,
    CONSTRAINT ck_player_inventory_quantity CHECK (quantity > 0)
);

COMMENT ON TABLE player_inventory IS 'Item ownership. Exactly one row per (player, item) in Phase 4.';
CREATE INDEX idx_player_inventory_user_id ON player_inventory (user_id);
CREATE INDEX idx_player_inventory_item_id ON player_inventory (item_id);

-- -------------------------- player_equipment --------------------------
CREATE TABLE player_equipment (
    id                UUID                     NOT NULL,
    user_id           UUID                     NOT NULL,
    slot              VARCHAR(16)              NOT NULL,
    inventory_item_id UUID                     NOT NULL,
    equipped_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_player_equipment PRIMARY KEY (id),
    -- One item per slot per player.
    CONSTRAINT uq_player_equipment_user_slot UNIQUE (user_id, slot),
    -- An inventory row cannot be equipped into two slots at once.
    CONSTRAINT uq_player_equipment_inventory UNIQUE (inventory_item_id),
    CONSTRAINT fk_player_equipment_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- CASCADE: deleting an inventory row takes the loadout entry with it.
    CONSTRAINT fk_player_equipment_inventory FOREIGN KEY (inventory_item_id)
        REFERENCES player_inventory (id) ON DELETE CASCADE,
    CONSTRAINT ck_player_equipment_slot CHECK (
        slot IN ('MAIN_DEVICE', 'PROCESSOR', 'SECURITY', 'SOFTWARE', 'NETWORK')
    )
);

COMMENT ON TABLE player_equipment IS 'Loadout. One row per (player, slot).';
CREATE INDEX idx_player_equipment_user_id ON player_equipment (user_id);

-- =====================================================================
-- Seed catalogue: 15 items across five rarities and five slots.
--
-- Economy, measured against the Phase 2 rewards in V3 (the 15 missions pay
-- 1,133 coins in total, between 25 and 190 each) and the 100 coins a new player
-- starts with:
--
--   COMMON     0 / 40 / 60       2-3 missions in
--   UNCOMMON   150 / 180 / 220   4-9 missions in
--   RARE       550 / 650 / 750   6-14 missions in, or a few replays
--   EPIC       1200 / 1350 / 1500 13+ missions in: past a single playthrough
--   LEGENDARY  3200 / 4000 / 5000 35-60 missions in
--
-- The whole catalogue costs 18,850 coins against 1,133 earned from clearing
-- every mission once, so nothing past EPIC is reachable without replaying the
-- board. A minimal five-slot loadout using the cheapest item per slot costs 1,950
-- (the starter laptop is free); the strongest possible loadout costs 15,050.
-- That is the intended shape: early items arrive within a session, and the top
-- of the catalogue is a long-term goal rather than a purchase.
--
-- Item ids are fixed rather than generated so tests and later phases can
-- address items by a stable id.
-- =====================================================================

INSERT INTO items (id, code, name, description, category, rarity, equipment_slot, price, active, stackable) VALUES
-- ---------------------------------- COMMON -------------------------------
('21111111-0000-4000-8000-000000000001', 'BASIC_LAPTOP', 'Basic Laptop',
 'A refurbished deck with a cracked bezel. Every rookie starts on one.',
 'DEVICE', 'COMMON', 'MAIN_DEVICE', 0, TRUE, FALSE),
('21111111-0000-4000-8000-000000000002', 'BASIC_PROCESSOR', 'Basic Processor',
 'Stock silicon with the serials filed off. It works. That is the review.',
 'PROCESSOR', 'COMMON', 'PROCESSOR', 40, TRUE, FALSE),
('21111111-0000-4000-8000-000000000003', 'BASIC_FIREWALL', 'Basic Firewall',
 'Freeware packet filter. Blocks the obvious probes and nothing clever.',
 'SECURITY', 'COMMON', 'SECURITY', 60, TRUE, FALSE),
-- -------------------------------- UNCOMMON ------------------------------
('21111111-0000-4000-8000-000000000004', 'RECON_LAPTOP', 'Recon Laptop',
 'Field deck with a passive antenna array bolted to the chassis.',
 'DEVICE', 'UNCOMMON', 'MAIN_DEVICE', 180, TRUE, FALSE),
('21111111-0000-4000-8000-000000000005', 'ENCRYPTED_PROCESSOR', 'Encrypted Processor',
 'Keeps key material off the bus. Nobody watching the bus learns anything.',
 'PROCESSOR', 'UNCOMMON', 'PROCESSOR', 150, TRUE, FALSE),
('21111111-0000-4000-8000-000000000006', 'ADAPTIVE_FIREWALL', 'Adaptive Firewall',
 'Learns what normal traffic looks like, then flags everything else.',
 'SECURITY', 'UNCOMMON', 'SECURITY', 220, TRUE, FALSE),
-- ---------------------------------- RARE --------------------------------
('21111111-0000-4000-8000-000000000007', 'STEALTH_LAPTOP', 'Stealth Laptop',
 'Low-emission deck wrapped in absorbing mesh. Quiet in more than one sense.',
 'DEVICE', 'RARE', 'MAIN_DEVICE', 550, TRUE, FALSE),
('21111111-0000-4000-8000-000000000008', 'NEURAL_PROCESSOR', 'Neural Processor',
 'Predicts a lock before it resolves. You are already through the door.',
 'PROCESSOR', 'RARE', 'PROCESSOR', 750, TRUE, FALSE),
('21111111-0000-4000-8000-000000000009', 'INTRUSION_SUITE', 'Advanced Intrusion Suite',
 'Probe library and a replay harness. Boring tools that never miss.',
 'SOFTWARE', 'RARE', 'SOFTWARE', 650, TRUE, FALSE),
-- ---------------------------------- EPIC --------------------------------
('21111111-0000-4000-8000-000000000010', 'QUANTUM_PROCESSOR', 'Quantum Processor',
 'Collapses the keyspace and reads off whatever is left standing.',
 'PROCESSOR', 'EPIC', 'PROCESSOR', 1500, TRUE, FALSE),
('21111111-0000-4000-8000-000000000011', 'GHOST_PROTOCOL', 'Ghost Protocol',
 'Leaves no flow records behind. The target logs an absence, not a visit.',
 'NETWORK', 'EPIC', 'NETWORK', 1200, TRUE, FALSE),
('21111111-0000-4000-8000-000000000012', 'MILITARY_FIREWALL', 'Military Firewall',
 'Decommissioned perimeter armour. Rated against attacks that do not exist yet.',
 'SECURITY', 'EPIC', 'SECURITY', 1350, TRUE, FALSE),
-- ------------------------------- LEGENDARY ------------------------------
('21111111-0000-4000-8000-000000000013', 'CYBER_PHANTOM_DECK', 'Cyber Phantom Deck',
 'Never manufactured. No record of purchase. It simply appeared on your desk.',
 'DEVICE', 'LEGENDARY', 'MAIN_DEVICE', 4000, TRUE, FALSE),
('21111111-0000-4000-8000-000000000014', 'QUANTUM_CORE', 'Quantum Core',
 'A caged singularity in a box you should not be carrying.',
 'NETWORK', 'LEGENDARY', 'NETWORK', 3200, TRUE, FALSE),
('21111111-0000-4000-8000-000000000015', 'ZERO_DAY_TOOLKIT', 'Zero-Day Toolkit',
 'Three exploits with no vendor, no patch and no name.',
 'SOFTWARE', 'LEGENDARY', 'SOFTWARE', 5000, TRUE, FALSE);

-- ------------------------------- effects -------------------------------
-- One effect per item, inserted after every item exists so the foreign key
-- can be satisfied. Each item is deliberately a single bonus, so the first
-- loadout a player builds reads as a set of distinct trade-offs.
INSERT INTO item_effects (id, item_id, effect_type, effect_value) VALUES
('22111111-0000-4000-8000-000000000001', '21111111-0000-4000-8000-000000000001', 'MISSION_SPEED',          5),
('22111111-0000-4000-8000-000000000002', '21111111-0000-4000-8000-000000000002', 'EXPERIENCE_BONUS',       5),
('22111111-0000-4000-8000-000000000003', '21111111-0000-4000-8000-000000000003', 'ENERGY_EFFICIENCY',      5),
('22111111-0000-4000-8000-000000000004', '21111111-0000-4000-8000-000000000004', 'COIN_BONUS',             8),
('22111111-0000-4000-8000-000000000005', '21111111-0000-4000-8000-000000000005', 'ENERGY_EFFICIENCY',      8),
('22111111-0000-4000-8000-000000000006', '21111111-0000-4000-8000-000000000006', 'ENERGY_EFFICIENCY',      8),
('22111111-0000-4000-8000-000000000007', '21111111-0000-4000-8000-000000000007', 'MISSION_SPEED',         12),
('22111111-0000-4000-8000-000000000008', '21111111-0000-4000-8000-000000000008', 'EXPERIENCE_BONUS',      10),
('22111111-0000-4000-8000-000000000009', '21111111-0000-4000-8000-000000000009', 'PUZZLE_BONUS',          10),
('22111111-0000-4000-8000-000000000010', '21111111-0000-4000-8000-000000000010', 'EXPERIENCE_BONUS',      15),
('22111111-0000-4000-8000-000000000011', '21111111-0000-4000-8000-000000000011', 'COIN_BONUS',            12),
('22111111-0000-4000-8000-000000000012', '21111111-0000-4000-8000-000000000012', 'ENERGY_EFFICIENCY',     10),
('22111111-0000-4000-8000-000000000013', '21111111-0000-4000-8000-000000000013', 'MISSION_SPEED',         15),
('22111111-0000-4000-8000-000000000014', '21111111-0000-4000-8000-000000000014', 'EXPERIENCE_BONUS',      15),
('22111111-0000-4000-8000-000000000015', '21111111-0000-4000-8000-000000000015', 'COIN_BONUS',            15);