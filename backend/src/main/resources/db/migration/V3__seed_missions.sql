-- =====================================================================
-- Cyber Heist - Phase 2: initial mission catalogue
--
-- 15 missions across the five categories, tuned so early game stays fast.
--
-- Level gates are ordered so that every mission stays reachable: completing all
-- missions at a given level always yields enough XP to unlock the next tier.
-- Total XP from every mission except the ELITE one is 1885, which is level 6, so
-- the ELITE mission is gated at level 6 rather than 7 - gating it at 7 (2078 XP)
-- would make it permanently unreachable.
--
-- Rewards use the server-authoritative values declared in V2.
-- UUIDs are fixed so tests and future phases can reference missions stably.
-- =====================================================================

-- ------------------------------- RECON --------------------------------
INSERT INTO missions (id, code, title, description, category, difficulty, required_level, xp_reward, coin_reward, energy_cost, estimated_duration_seconds, active) VALUES
('11111111-0000-4000-8000-000000000001', 'RECON_PERIMETER', 'Scan the Perimeter',
 'Sweep the outer ring of the target building and log every sensor, camera and guard rotation you can see.',
 'RECON', 'EASY', 1, 50, 25, 10, 180, TRUE),

('11111111-0000-4000-8000-000000000002', 'RECON_NETWORK_MAP', 'Map the Network',
 'Chart the internal topology from stolen floor plans and mark the nodes that matter.',
 'RECON', 'EASY', 1, 60, 30, 12, 240, TRUE),

('11111111-0000-4000-8000-000000000003', 'RECON_TARGET_ID', 'Identify the Target',
 'Put a face to the account you have been paid to ruin. Names, habits, schedule.',
 'RECON', 'MEDIUM', 2, 90, 45, 15, 300, TRUE);

-- ------------------------------ EXPLOIT -------------------------------
INSERT INTO missions (id, code, title, description, category, difficulty, required_level, xp_reward, coin_reward, energy_cost, estimated_duration_seconds, active) VALUES
('11111111-0000-4000-8000-000000000004', 'EXPLOIT_GATEWAY', 'Breach the Gateway',
 'Find the forgotten maintenance credential and slip past the lobby authentication.',
 'EXPLOIT', 'MEDIUM', 2, 110, 55, 18, 360, TRUE),

('11111111-0000-4000-8000-000000000005', 'EXPLOIT_AUTH_BYPASS', 'Bypass Authentication',
 'Replay a captured token and walk straight through the second checkpoint.',
 'EXPLOIT', 'MEDIUM', 3, 140, 70, 20, 420, TRUE),

('11111111-0000-4000-8000-000000000006', 'EXPLOIT_WEAK_LINK', 'Exploit the Weak Link',
 'An unpatched service on an overlooked subnet. Get in, get the keys, get out clean.',
 'EXPLOIT', 'HARD', 4, 190, 95, 26, 540, TRUE);

-- --------------------------- CRYPTOGRAPHY -----------------------------
INSERT INTO missions (id, code, title, description, category, difficulty, required_level, xp_reward, coin_reward, energy_cost, estimated_duration_seconds, active) VALUES
('11111111-0000-4000-8000-000000000007', 'CRYPTO_TRANSMISSION', 'Decode the Transmission',
 'A burst of hand-rolled cipher. Break it before the window closes.',
 'CRYPTOGRAPHY', 'EASY', 1, 55, 28, 10, 200, TRUE),

('11111111-0000-4000-8000-000000000008', 'CRYPTO_CIPHER', 'Break the Cipher',
 'Substitution layers wrapped around a key schedule. Peel them one at a time.',
 'CRYPTOGRAPHY', 'MEDIUM', 3, 150, 75, 22, 480, TRUE),

('11111111-0000-4000-8000-000000000009', 'CRYPTO_SECRET', 'Recover the Secret',
 'The master key sits behind a chain of derived hashes. Recreate the chain and take it.',
 'CRYPTOGRAPHY', 'HARD', 5, 240, 120, 30, 660, TRUE);

-- ------------------------------ NETWORK -------------------------------
INSERT INTO missions (id, code, title, description, category, difficulty, required_level, xp_reward, coin_reward, energy_cost, estimated_duration_seconds, active) VALUES
('11111111-0000-4000-8000-000000000010', 'NETWORK_SIGNAL', 'Trace the Signal',
 'Follow the leak back to its source across three hops and a spoofed header.',
 'NETWORK', 'EASY', 2, 70, 35, 12, 260, TRUE),

('11111111-0000-4000-8000-000000000011', 'NETWORK_PACKET', 'Intercept the Packet',
 'Snag the scheduled transfer in flight and pull the payload before it lands.',
 'NETWORK', 'MEDIUM', 3, 130, 65, 20, 400, TRUE),

('11111111-0000-4000-8000-000000000012', 'NETWORK_FIREWALL', 'Navigate the Firewall',
 'Rewrite the rule set from the inside, one over-permissive range at a time.',
 'NETWORK', 'HARD', 5, 210, 105, 28, 600, TRUE);

-- --------------------------- INTELLIGENCE -----------------------------
INSERT INTO missions (id, code, title, description, category, difficulty, required_level, xp_reward, coin_reward, energy_cost, estimated_duration_seconds, active) VALUES
('11111111-0000-4000-8000-000000000013', 'INTEL_EXTRACT', 'Extract Intelligence',
 'Turn the seized drive into names, dates and a map of who talks to whom.',
 'INTELLIGENCE', 'MEDIUM', 3, 120, 60, 18, 380, TRUE),

('11111111-0000-4000-8000-000000000014', 'INTEL_INFORMANT', 'Locate the Informant',
 'A source inside the building knows the schedule. Find them before they bolt.',
 'INTELLIGENCE', 'HARD', 6, 270, 135, 32, 720, TRUE),

('11111111-0000-4000-8000-000000000015', 'INTEL_RECOVER_DATA', 'Recover the Data',
 'The whole job hinges on one vault. Get the payload out and leave no trace.',
 'INTELLIGENCE', 'ELITE', 6, 380, 190, 40, 900, TRUE);