# CYBER HEIST

A cyberpunk-themed browser game.

- **Phase 1** — technical foundation and authentication.
- **Phase 2** — player progression (XP, levels, coins, energy) and the mission system.
- **Phase 3** — the puzzle engine and passive energy regeneration.
- **Phase 4 (current)** — the item catalogue, shop, inventory and equipment.

Implemented gameplay loop:

> View missions → start mission → **receive a puzzle** → solve it → submit →
> server validates → rewards → update XP/coins/energy → level up →
> **spend coins in the shop** → equip gear → **rewards improve**

Skill trees, bosses, achievements, leaderboards, multiplayer, PvP,
AI-generated content and payments are **out of scope** and are not implemented.
The schema and code are laid out so those systems can be added later without a
redesign.

---

## Architecture

Two independent applications behind one versioned REST API.

```text
┌──────────────────────┐        HTTPS / JSON        ┌────────────────────────┐
│  React 19 + Vite SPA │ ───────────────────────▶  │  Spring Boot REST API  │
│  TS · Tailwind · RR  │ ◀───────────────────────  │  Security · JPA        │
└──────────────────────┘                           └───────────┬────────────┘
                                                             │ JDBC
                                                   ┌─────────▼──────────┐
                                                   │   PostgreSQL 17+   │
                                                   │  Flyway migrations │
                                                   └────────────────────┘
```

**Backend layering** — each package has one job:

| Layer | Responsibility |
| --- | --- |
| controller | HTTP in/out only. No business logic. |
| service | Business rules, transactions, ownership checks. |
| repository | Parameterised data access via Spring Data JPA. |
| entity | JPA mappings. Never serialised to JSON directly. |
| dto | The only shapes that cross the API boundary. |
| security | JWT creation/validation, filters, current-user resolution. |
| exception | Typed failures plus one central handler. |

**Frontend layering** — components never call `fetch` directly:

```text
pages / layouts  →  useAuth() context  →  services/  →  apiClient (fetch)
```

`apiClient.ts` is the only file that touches `fetch`; it attaches the bearer
token, unwraps the response envelope and converts failures into a typed
`ApiError`.

### Authentication flow

```text
register ─▶ create User (BCrypt hash, PLAYER) + create PlayerProfile
           └─ one transaction, so a player never exists without game state,
              and the free Basic Laptop is granted and equipped in the same
              transaction

login    ─▶ verify password ─▶ issue JWT access token + opaque refresh token
                                 (only the SHA-256 hash of the refresh
                                  token is written to the database)

refresh  ─▶ validate hash → check expiry + revocation → revoke the presented
            token → issue a new access token AND a new refresh token

logout   ─▶ revoke the refresh token server-side
```

### Level curve

Advancing **from** level `L` costs `round(baseExperience × growthMultiplier^(L-1))`.
With the defaults (100, 1.5) that is 100, 150, 225, 338, 506, … XP per level.
Those per-level costs sum to the cumulative total needed to *hold* a level:
0, 100, 250, 475, 813, …

**XP is cumulative and never resets.** A player on 90 XP who earns 50 reaches
level 2 holding 140 XP. The stored `experience` column is therefore a running
total and the level is derived from it, so the two can never drift apart.

The curve lives in `LevelCurve` as pure arithmetic with no persistence, is
configurable via `app.progression.*`, and is unit tested independently.

---

## Puzzle engine

### The strategy pattern

Adding a puzzle type is **one new `@Component`**. `MissionService` contains no
`switch` on puzzle type and no knowledge of any individual puzzle.

```text
                        MissionController
                               │
                        MissionService ──── owns the lifecycle, energy,
                               │             progress and rewards
                               ▼
                        PuzzleService ───── registry + generation + validation
                               │
        ┌──────────┬───────────┼───────────┬──────────────┐
        ▼          ▼           ▼           ▼              ▼
   CipherPuzzle  Sequence    Pattern     LogicPuzzle    TimedPuzzle
    Provider      Provider    Provider     Provider       Provider
```

| Type | File | Shape of the challenge | Window (EASY→ELITE) |
| --- | --- | --- | --- |
| `CIPHER` | `puzzle/provider/CipherPuzzleProvider.java` | Caesar-shifted word; decode it | 240–360 s |
| `SEQUENCE` | `puzzle/provider/SequencePuzzleProvider.java` | Arithmetic, multiplicative or alternating rule; the question names the rule | 120–210 s |
| `PATTERN` | `puzzle/provider/PatternPuzzleProvider.java` | 3×3 or 4×4 symbol grid with one gap | 150–300 s |
| `LOGIC` | `puzzle/provider/LogicPuzzleProvider.java` | Node/edge graph; one island head is the answer, all distractors lie on the reachable path | 180–330 s |
| `TIMED` | `puzzle/provider/TimedPuzzleProvider.java` | Recall a displayed code before it is hidden | 45–12 s |

The contract each provider implements (`puzzle/PuzzleProvider.java`):

```java
PuzzleType       type();                                    // which type it claims
PuzzleChallenge  create(MissionDifficulty d, long seed);   // deterministic challenge
int              timeLimitSeconds(MissionDifficulty d);    // server-side window
```

`PuzzleService` collects every `PuzzleProvider` bean at startup into an
`EnumMap<PuzzleType, PuzzleProvider>` and **fails fast at boot** if a declared
type has no provider, or if two beans claim the same type. A misconfiguration is
a startup crash, never a silent runtime surprise. The startup line to look for:

```text
Puzzle engine ready with providers: [CIPHER, SEQUENCE, PATTERN, LOGIC, TIMED]
```

Mission → puzzle type is a column, not a conditional:

| Mission category | Puzzle type |
| --- | --- |
| `CRYPTOGRAPHY` | `CIPHER` |
| `INTELLIGENCE` | `LOGIC` |
| `NETWORK` | `PATTERN` |
| `RECON` | `SEQUENCE` |
| `EXPLOIT_WEAK_LINK` | `TIMED` |
| any other `EXPLOIT` | `SEQUENCE` |

### Where the answer lives

> **The correct answer is never stored and never sent.**

The challenge is a pure function of `(type, difficulty, seed)`, and **only the
seed is persisted**:

```text
seed (SecureRandom, 64-bit) ──▶ provider.create(difficulty, seed) ──▶ challenge
                                      │
                                      └── a deterministic function of the seed,
                                          using java.util.Random's JDK-fixed algorithm
```

Validating a submission re-derives the challenge from the stored seed and
compares against the freshly derived answer. Consequences:

- A database dump cannot leak an answer — there is nothing to leak.
- DevTools cannot reveal an answer — it is never in a response body.
- There is no hash to crack and no plaintext to store.
- A provider **must** stay deterministic. `PuzzleProviderContractTest` asserts
  this for every registered type across many seeds and all four difficulties.

`PuzzleChallengeView` is the single projection point where the answer is
dropped, which makes it the one file to audit for leakage. It has no `answer`
field at all — not a null one.

The seed is drawn from a single shared `SecureRandom`, not `Random`: a guessable
seed would let a client regenerate the puzzle locally and read the answer off the
objects it builds.

### Difficulty and validated generation

Difficulty is the mission's difficulty, passed straight through to the provider.
`PuzzleGeneratorSupport` validates every generated challenge before it reaches a
player, guaranteeing that:

- the answer is not one of the distractors,
- all options are distinct,
- the answer appears exactly once in the option list,
- there are enough distinct distractors to fill the list.

### Mission lifecycle

```text
POST /missions/{id}/start
   └─ lock player → verify level / reachability / energy → charge energy
      → mark progress IN_PROGRESS → supersede any previous puzzle
      → generate a new puzzle from a fresh seed
   └─ 200 { …mission fields…, puzzle: {…}, attemptCount, player }

POST /missions/{id}/puzzle/submit   { puzzleId, answer }
   1  authenticate                                  → 401
   2  progress must be IN_PROGRESS                  → 400
   3  puzzle must belong to this player AND mission → 404
   4  puzzle must not already be submitted          → 409
   5  window must still be open (server clock)      → 200 EXPIRED
   6  validate the answer server-side               → 200 SOLVED | INCORRECT

   SOLVED ─▶ RewardService ─▶ ProgressionService ─▶ PlayerProfile
        └─ mission COMPLETED, XP + coins granted, level recomputed
```

Every one of those six checks is a decision the server makes. The request body
has exactly two fields (`puzzleId`, `answer`) — there is no `success`, `score`,
`xp` or `coins` field for a client to populate, because a request DTO that can
express them is a request DTO somebody eventually will.

**A mission can only be completed by solving its puzzle.** `POST /{id}/complete`
is retained for backward compatibility but can no longer pay anything: it throws
`400 PuzzleRequiredException` ("Solve the mission's puzzle to complete it") when
the mission is not `COMPLETED`, and otherwise returns zero rewards with
`alreadyCompleted: true`. There is no `start → complete → rewards` path.

**Wrong and expired never pay.** A wrong answer leaves the mission `IN_PROGRESS`
and burns that puzzle; `canRetry: true` tells the client a restart produces a new
one. An expired answer marks the puzzle `EXPIRED`, pays nothing and cannot be
replayed.

**Restarting re-charges.** Starting an `IN_PROGRESS` mission charges energy again
and marks the previous puzzle `EXPIRED`, so only one puzzle per mission is ever
live.

**Repeat submission is not an error.** Submitting again against a puzzle that was
already solved returns `200` with `alreadySolved: true` and zero rewards.
Submitting against a `FAILED`, `EXPIRED` or already-spent puzzle returns `409` — this includes puzzles superseded by a mission restart, which `MissionService` marks `EXPIRED` before issuing a new challenge.

### Reward and progression flow

Providers decide only whether an answer is right. They never touch XP, coins,
level, energy or mission progress, and they never see a `PuzzleAttempt`:

```text
PuzzleProvider ─▶ "is this right?" ─▶ MissionService ─▶ RewardService
                                                          │
                                                          ▼
                                                  ProgressionService
                                                          │
                                                          ▼
                                                    PlayerProfile
```

`RewardService` and `ProgressionService` are the Phase 2 classes, unchanged.
Phase 3 reuses them rather than reimplementing any payout arithmetic.

---

## Energy regeneration

Passive, **server-authoritative**, computed lazily. There is no background job
sweeping every player every minute — regeneration is applied when player state is
read or mutated.

```yaml
app:
  energy:
    maximum: 100
    regeneration:
      enabled: true          # ENERGY_REGEN_ENABLED
      amount: 1              # ENERGY_REGEN_AMOUNT
      interval-minutes: 5    # ENERGY_REGEN_INTERVAL_MINUTES
```

The algorithm, executed inside the same transaction that reads or writes the
profile:

```text
elapsed   = serverNow − lastEnergyUpdate
intervals = floor(elapsed / interval)
newEnergy = min(maximum, energy + intervals × amount)
newStamp  = lastEnergyUpdate + intervals × interval     ← not "now"
```

`player_profiles.last_energy_update` is the anchor. Three properties matter, and
each is unit-tested:

- **No lost fractions.** The stamp advances by whole consumed intervals, so the
  remainder carries forward: 4 min then 2 min is +1, not +0 then +0.
- **Never above maximum, never below zero.** Both ends are clamped.
- **A backwards clock cannot mint energy.** If `lastEnergyUpdate` is in the
  future, the anchor is re-set to now instead of subtracting from it.

Concurrency: mission start and every energy read take a **pessimistic write lock**
on the profile row (`PlayerProfileRepository.findByUserIdForUpdate`), so two
simultaneous starts cannot both observe 78 energy and both spend 12.

---

## Inventory, equipment and the shop

Phase 4 gives coins a purpose: the shop sells a server-owned catalogue of items,
the inventory records what a player owns, and five equipment slots decide which
of those items are actually active.

```text
MISSION ─▶ XP + coins ─▶ SHOP ─▶ item enters INVENTORY ─▶ EQUIP ─▶ bonuses
                        ▲                                                     │
                        └─────────────────────────────────────────────────────┘
```

### The trust rule

The frontend is never authoritative for any of the following. Each is decided by
the server from its own tables:

| Value | Decided by |
| --- | --- |
| Coin balance | `player_profiles.coins`, changed only by `RewardService` and `PurchaseService` |
| Item ownership | `player_inventory`, written only by `PurchaseService` and `StarterEquipmentService` |
| Item price | `items.price` |
| Equipped state | `player_equipment`, written only by `EquipmentService` |
| Item rarity | `items.rarity` |
| Item bonuses | `item_effects.effect_value`, aggregated by `EquipmentBonusService` |
| Purchase success | `PurchaseService`, in one transaction |
| Equipment effects | `EquipmentBonusService`, the only aggregation in the codebase |

The purchase endpoint is the clearest expression of this. It takes an item id
and **nothing else** — there is no request DTO, so a body carrying
`{"price": 1, "coins": 1000000, "bonus": 999999}` has no field it could bind to.
The client does not supply a price that gets ignored; it has no way to supply
one at all.

### Categories, rarity and slots

| | Values |
| --- | --- |
| **Category** | `DEVICE`, `PROCESSOR`, `SECURITY`, `SOFTWARE`, `NETWORK` |
| **Rarity** | `COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY` (no `MYTHIC` yet) |
| **Slot** | `MAIN_DEVICE`, `PROCESSOR`, `SECURITY`, `SOFTWARE`, `NETWORK` |

An item's category determines its slot, and rarity is a property of the item
definition. Neither is ever chosen by a client. Adding a category or a slot later
means a new enum constant, a new CHECK constraint in a new migration, and a seed
row — no redesign, because nothing else keys off the closed set.

### Item effects

Effects are **typed and relational**, not a JSON blob: these values drive reward
and energy arithmetic, so they need a foreign key, CHECK constraints and an index
that free-form JSON would not give.

| Effect type | Applies to |
| --- | --- |
| `EXPERIENCE_BONUS` | Mission XP payout |
| `COIN_BONUS` | Mission coin payout |
| `ENERGY_EFFICIENCY` | Mission energy **cost** (a discount) |
| `MISSION_SPEED` | Aggregated and exposed; **not yet applied to any mechanic** |
| `PUZZLE_BONUS` | Aggregated and exposed; **not yet applied to puzzle generation** |

`MISSION_SPEED` and `PUZZLE_BONUS` are stored, aggregated, capped and returned to
the client, but Phase 4 deliberately does not wire them into a mechanic. Mission
duration is advisory and puzzles are generated from a seed, so there is nothing
honest for either to modify yet. They are infrastructure, and the UI labels them
without implying an effect that does not exist. Puzzle providers were **not**
modified to read equipment.

### Bonus calculation and caps

`EquipmentBonusService` is the single place an equipped item's percentage is
read. Every mechanic that a bonus touches goes through it, so there is exactly
one definition of what a player's loadout is worth.

Totals are clamped per effect type before anyone sees them:

| Effect | Cap |
| --- | --- |
| `EXPERIENCE_BONUS` | 50% |
| `COIN_BONUS` | 50% |
| `ENERGY_EFFICIENCY` | 30% |
| `MISSION_SPEED` | 30% |
| `PUZZLE_BONUS` | 30% |

The caps live in Java rather than in the database because a database cannot
constrain a sum across rows. Without them, five legendary items would stack
without limit; with them, +50% XP is the ceiling no matter how the loadout is
built.

### Rounding

All arithmetic is integer, and halves round **up**:

```text
final = base + (base × percent + 50) / 100          // XP, coins
cost  = (base × (100 − percent) + 50) / 100, min 1  // energy
```

Floating point is rejected deliberately: `50 × 0.10` is not exactly `5` in
binary floating point, and a payout that varied with the representation of a
decimal would be a rounding bug waiting to be reported as a wrong reward. 50 XP
at +10% is 55; 50 XP at +5% is 53; a 20-energy mission at +10% efficiency costs
18, and never less than 1.

### Purchase flow

```text
POST /player/shop/items/{itemId}/purchase          request body: none
   1  authenticate                             → 401
   2  item must exist                          → 404
   3  item must be active                      → 400
   4  LOCK the player's profile row            (pessimistic write)
   5  player must not already own it           → 409
   6  balance must cover items.price           → 400
   7  deduct coins + insert the inventory row  (one transaction)
```

Steps 5–7 happen under the profile lock, so two simultaneous requests cannot
both pass the affordability check, and the duplicate-ownership check cannot be
lost to a race. Every rejection occurs before the first write, so a refused
purchase costs the player nothing and leaves no trace. There is no
catch-the-constraint-violation fallback, because a failed statement marks the
transaction rollback-only and the retry could not commit either.

### Equip / unequip flow

```text
POST   /player/equipment/{slot}   { "inventoryItemId": "…" }
DELETE /player/equipment/{slot}
```

Equip locks the profile row first (serialising all loadout changes for that
player), then verifies ownership **by id and owner in one query**, then the slot,
then writes. An inventory row belonging to another player is simply not found, so
the response cannot confirm that a guessed id is real. Unequip deletes only the
loadout row: the item stays owned and the bonus disappears because aggregation
reads the loadout, not the inventory. Unequipping an empty slot is a no-op, so a
double-click cannot produce an error the player cannot act on.

### Starting equipment

Every newly registered player is granted a free **Basic Laptop** and it is
equipped immediately. `StarterEquipmentService` looks it up by a hard-coded code,
so a registration request cannot name or choose a starter item, and the grant
runs inside the registration transaction, so a failure rolls the account back
rather than leaving a player with no gear.

The rationale: bonuses only matter once something is equipped. Without a free
device a new player would see five empty slots and no bonuses anywhere, with no
way to tell what the system is for. Equipping it costs nothing and grants a small
mission-speed bonus from the very first loadout screen.

### The catalogue and the economy

15 seeded items. Each has exactly one effect, so the first loadout a player builds
reads as a set of distinct trade-offs rather than a stack of the same number.

| Rarity | Items | Price | Roughly |
| --- | --- | --- | --- |
| `COMMON` | Basic Laptop, Basic Processor, Basic Firewall | 0 / 40 / 60 | 2–3 missions |
| `UNCOMMON` | Recon Laptop, Encrypted Processor, Adaptive Firewall | 150 / 180 / 220 | 4–9 missions |
| `RARE` | Stealth Laptop, Neural Processor, Advanced Intrusion Suite | 550 / 650 / 750 | 6–14 missions |
| `EPIC` | Quantum Processor, Ghost Protocol, Military Firewall | 1200 / 1350 / 1500 | 13+ missions |
| `LEGENDARY` | Cyber Phantom Deck, Quantum Core, Zero-Day Toolkit | 3200 / 4000 / 5000 | 35–60 missions |

Measured against the Phase 2 rewards — 15 missions paying **1133 coins** in total,
25 to 190 each, with a new player starting on 100:

- The full catalogue costs **18,850**, so nothing past `EPIC` is reachable without
  replaying the board. The player is always saving for something.
- A minimal five-slot loadout, cheapest item per slot, costs **1,950** (the
  starter laptop is free). The strongest possible loadout costs **15,050**.
- Early gear arrives within a session; the top of the catalogue is a long-term
  goal rather than a purchase.

Item ids are fixed rather than generated, so tests and later phases can address
an item by a stable id.

---

**Backend**

| Concern | Choice |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Security | Spring Security + JJWT 0.12 (HS256) |
| Persistence | Spring Data JPA / Hibernate |
| Database | PostgreSQL 17+ |
| Migrations | Flyway 11 |
| Password hashing | BCrypt (cost 12) |
| Build | Maven |
| Tests | JUnit 5, MockMvc, AssertJ |

**Frontend**

| Concern | Choice |
| --- | --- |
| Framework | React 19 + TypeScript |
| Build | Vite 6 |
| Styling | Tailwind CSS 3 |
| Routing | React Router 7 |
| Tests | Vitest + Testing Library + jsdom |
| Lint | ESLint 9 |

No state-management or HTTP library beyond `fetch` is used — the requirements do
not justify them yet.

---

## Prerequisites

| Tool | Version used |
| --- | --- |
| Java | 21 (tested on 21 and 25) |
| Maven | 3.9+ |
| Node.js | 20+ (tested on 24) |
| npm | 10+ |
| PostgreSQL | 17+ |
| Docker | optional, for PostgreSQL only |

---

## Environment variables

Copy the example file and edit it:

```bash
cp .env.example .env
```

`.env` is git-ignored. **Never commit real credentials.**

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `DB_URL` | yes | `jdbc:postgresql://localhost:5432/cyberheist` | JDBC URL |
| `DB_USERNAME` | yes | `cyberheist` | Database user |
| `DB_PASSWORD` | yes | — | Database password |
| `JWT_SECRET` | **yes, no default** | — | HMAC signing key, ≥ 32 bytes |
| `JWT_ISSUER` | no | `cyber-heist` | `iss` claim |
| `JWT_ACCESS_EXPIRATION` | no | `PT15M` | Access token lifetime (ISO-8601) |
| `REFRESH_TOKEN_EXPIRATION` | no | `P7D` | Refresh token lifetime |
| `CORS_ALLOWED_ORIGINS` | no | `http://localhost:5173` | Comma-separated origins |
| `AUTH_RATE_LIMIT_ENABLED` | no | `true` | Toggle auth rate limiting |
| `AUTH_RATE_LIMIT_MAX_ATTEMPTS` | no | `20` | Attempts per window |
| `AUTH_RATE_LIMIT_WINDOW` | no | `PT1M` | Window length |
| `ENERGY_REGEN_ENABLED` | no | `true` | Toggle energy regeneration |
| `ENERGY_REGEN_AMOUNT` | no | `1` | Energy per interval |
| `ENERGY_REGEN_INTERVAL_MINUTES` | no | `5` | Interval length |
| `SERVER_PORT` | no | `8080` | HTTP port |
| `VITE_API_BASE_URL` | no | `http://localhost:8080` | API URL seen by the browser |

The application **refuses to start** if `JWT_SECRET` is missing or shorter than
32 bytes. Generating one:

```bash
openssl rand -base64 64
```

---

## Running PostgreSQL

**Option A — Docker (recommended for a fresh machine)**

```bash
cp .env.example .env      # then set POSTGRES_PASSWORD
docker compose up -d postgres
```

**Option B — an existing local PostgreSQL**

```sql
CREATE DATABASE cyberheist;
CREATE USER cyberheist WITH PASSWORD 'choose-a-strong-password';
GRANT ALL PRIVILEGES ON DATABASE cyberheist TO cyberheist;
```

Migrations are applied automatically on startup, so no manual DDL is needed.

---

## Running the backend

```bash
cd backend

# export the required secrets first
export DB_USERNAME=cyberheist
export DB_PASSWORD='choose-a-strong-password'
export JWT_SECRET="$(openssl rand -base64 64)"

mvn spring-boot:run
```

Expected startup output:

```text
Flyway: Migrating schema "public" to version "1 - create core schema"
Flyway: Migrating schema "public" to version "5 - inventory equipment shop"
Puzzle engine ready with providers: [CIPHER, SEQUENCE, PATTERN, LOGIC, TIMED]
Started CyberHeistApplication
```

The API listens on <http://localhost:8080>.

---

## Running the frontend

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. The browser calls the API directly at
`VITE_API_BASE_URL`, so that URL must be listed in `CORS_ALLOWED_ORIGINS`.

---

## Running the tests

```bash
# Backend — 332 tests
cd backend
mvn test

# Backend production jar
mvn clean package

# Frontend — typecheck, lint, unit tests, production build
cd frontend
npm run typecheck
npm run lint
npm test
npm run build
```

The backend suite runs against an in-memory H2 database in **PostgreSQL mode**
and applies the *same* Flyway migrations (V1–V5), so no external service is
required and the schema under test is the schema that ships.

What the Phase 4 suites cover:

| Suite | Covers |
| --- | --- |
| `ShopCatalogueIntegrationTest` | Active items only, inactive hidden, item detail, unknown id, auth required, owned flags |
| `PurchaseIntegrationTest` | Success, server price, insufficient coins, retired item, duplicate `409` with no charge, price/body tampering, caller's balance only |
| `ShopOwnershipIntegrationTest` | Inventory scoping, cannot equip another's row, cannot unequip another's slot, cannot spend another's coins, client-supplied `userId` ignored |
| `EquipmentIntegrationTest` | Equip, equipped state, slot replacement, incompatible slot, unknown slot, retired item, unequip keeps the item, idempotent unequip, re-equip |
| `ConcurrentPurchaseIntegrationTest` | Two simultaneous purchases of different items against insufficient funds; two of the same item; two equips into one slot |
| `EquipmentBonusRulesTest` | Rounding table, determinism sweep, energy floor at 1, cap clamping, reward modification |
| `StarterAndBonusIntegrationTest` | Free starter item, auto-equipped, grants a bonus, mission reward = base + bonus, unequip removes it, energy discount, bonus injection ignored |

Every Phase 1–3 regression test still passes. No test was removed or weakened;
the Phase 4 count is 332 against a 263 baseline.

The concurrency test is the one that matters most economically. With 800 coins,
it fires two simultaneous requests for items costing 750 and 550 — 1300 coins of
intent against 800 of balance — and asserts that exactly one succeeds, exactly one
inventory row is created beyond the starter laptop, and the balance reflects one
price and never goes negative.

> **PostgreSQL verification status.** The migrations have been authored
> PostgreSQL-compatibly and were executed through Flyway against H2 in PostgreSQL
> mode, but they have **not** been executed against a real PostgreSQL instance.
> In this environment the local PostgreSQL 18 service is listening on 5432 but
> requires password authentication and no credentials were available (there is no
> `.env`), and the Docker daemon had no `postgres` container to start.
> **H2 in PostgreSQL mode is not PostgreSQL runtime verification** and is not
> claimed as such. See *Known limitations*.

### Live HTTP verification

`backend/verify-live.ps1` starts the real application and exercises the Phase 4
API over HTTP with real bearer tokens, then shuts it down:

```bash
cd backend
mvn clean package
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
powershell -File verify-live.ps1
```

It runs 28 checks covering registration, login, starting coins and gear, the
catalogue, item detail, purchase and deduction, equipping, the loadout, bonus
aggregation, a full mission played to a server-calculated payout, unequipping,
duplicate purchase, price tampering, bonus tampering, cross-player access in both
directions, insufficient coins, retired items and unauthenticated access.

Because no PostgreSQL credentials are available, the script points the
application at an H2 in-memory database in PostgreSQL mode. **This verifies the
API and the business rules, not PostgreSQL.**

---

## Database schema

Managed entirely by Flyway. Hibernate runs with `ddl-auto: validate` and never
creates or alters anything — `create` and `update` are deliberately not used.

| Migration | Contents |
| --- | --- |
| `V1__create_core_schema.sql` | `users`, `player_profiles`, `refresh_tokens` |
| `V2__create_mission_system.sql` | `missions`, `mission_progress` |
| `V3__seed_missions.sql` | 15 seeded missions across 5 categories |
| `V4__puzzle_attempts_and_energy_regen.sql` | `missions.puzzle_type`, `player_profiles.last_energy_update`, `puzzle_attempts` |
| `V5__inventory_equipment_shop.sql` | `items`, `item_effects`, `player_inventory`, `player_equipment` + 15 seeded items |

V1–V4 are never modified; Phases 3 and 4 are entirely additive.

### `users`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `username` | `VARCHAR(32)` | Unique |
| `email` | `VARCHAR(255)` | Unique (stored lower-cased) |
| `password_hash` | `VARCHAR(100)` | BCrypt hash only |
| `role` | `VARCHAR(20)` | `PLAYER` \| `ADMIN`, check-constrained |
| `enabled` | `BOOLEAN` | Disabled accounts cannot sign in |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

### `player_profiles`

| Column | Type | Initial value |
| --- | --- | --- |
| `id` | `UUID` | |
| `user_id` | `UUID` | Unique, FK → `users` (cascade) |
| `display_name` | `VARCHAR(32)` | Copy of the username |
| `level` | `INTEGER` | `1` |
| `experience` | `BIGINT` | `0` |
| `coins` | `BIGINT` | `100` |
| `energy` | `INTEGER` | `100` |
| `last_energy_update` | `TIMESTAMPTZ` | **added in V4** — regeneration anchor |

Created in the same transaction as the user, ready for future game systems.

### `refresh_tokens`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `user_id` | `UUID` | FK → `users` (cascade) |
| `token_hash` | `VARCHAR(64)` | Unique **SHA-256** of the token |
| `expires_at` | `TIMESTAMPTZ` | |
| `revoked` | `BOOLEAN` | Set by logout and by rotation |
| `created_at` | `TIMESTAMPTZ` | |

Indexes on `user_id` and `expires_at`.

### `missions`

Server-owned catalogue. These columns are the authoritative reward values the
backend applies; there is no endpoint that creates or edits them.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `code` | `VARCHAR(64)` | Unique, stable external id |
| `title` | `VARCHAR(120)` | |
| `description` | `VARCHAR(500)` | |
| `category` | `VARCHAR(32)` | `RECON` \| `EXPLOIT` \| `CRYPTOGRAPHY` \| `NETWORK` \| `INTELLIGENCE` |
| `difficulty` | `VARCHAR(16)` | `EASY` \| `MEDIUM` \| `HARD` \| `ELITE` |
| `puzzle_type` | `VARCHAR(16)` | **added in V4** — `CIPHER` \| `SEQUENCE` \| `PATTERN` \| `LOGIC` \| `TIMED` |
| `required_level` | `INTEGER` | ≥ 1; `403` when the player is below it |
| `xp_reward` | `INTEGER` | ≥ 0 |
| `coin_reward` | `BIGINT` | ≥ 0 |
| `energy_cost` | `INTEGER` | 0–1000, charged on start |
| `estimated_duration_seconds` | `INTEGER` | > 0, advisory only |
| `active` | `BOOLEAN` | Inactive missions are hidden and cannot be started |

CHECK constraints enforce every enum and range. Indexed on `category`,
`difficulty` and `active`.

### `mission_progress`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `user_id` | `UUID` | FK → `users` (cascade) |
| `mission_id` | `UUID` | FK → `missions` (cascade) |
| `status` | `VARCHAR(16)` | `NOT_STARTED` \| `IN_PROGRESS` \| `COMPLETED` |
| `started_at` | `TIMESTAMPTZ` | Null until started |
| `completed_at` | `TIMESTAMPTZ` | Set on completion |
| `attempt_count` | `INTEGER` | ≥ 0, incremented on each start |

**`UNIQUE (user_id, mission_id)`** — the guarantee that a player can never hold
two independent progress rows for the same mission, even under concurrent
starts. A CHECK constraint requires both timestamps on any `COMPLETED` row.

Ownership is derived from the authenticated principal: no client supplies
`user_id`.

### `puzzle_attempts`

One generated puzzle, owned by one player, belonging to one mission.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `user_id` | `UUID` | FK → `users` (cascade) |
| `mission_id` | `UUID` | FK → `missions` (cascade) |
| `puzzle_id` | `UUID` | **Unique.** The id handed to the client |
| `puzzle_type` | `VARCHAR(16)` | Which provider generated it |
| `difficulty` | `VARCHAR(16)` | The mission's difficulty |
| `seed` | `BIGINT` | The **only** input the challenge is derived from |
| `status` | `VARCHAR(16)` | `ACTIVE` \| `SUCCEEDED` \| `FAILED` \| `EXPIRED` |
| `started_at` | `TIMESTAMPTZ` | Server time the challenge was issued |
| `expires_at` | `TIMESTAMPTZ` | Server time the window closes |
| `submitted_at` | `TIMESTAMPTZ` | Null until the one allowed submission |
| `attempt_number` | `INTEGER` | ≥ 1, incremented per restart |

**There is no `answer` column.** That is the whole point: the answer is
re-derived from `seed` rather than stored.

Constraints that make duplicate reward structurally impossible:

- `UNIQUE (puzzle_id)` — a puzzle has exactly one addressable id.
- `UNIQUE (user_id, mission_id, attempt_number)` — one attempt number per player
  per mission.
- `CHECK (expires_at > started_at)` — a window cannot be inverted.
- `CHECK (attempt_number >= 1)` plus CHECK constraints on every enum column.
- Indexes on `user_id`, `mission_id` and `status`.

### `items`

The **server-owned catalogue**. It defines items; it holds no player state.
There is no endpoint that creates, edits or prices an item.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key, fixed per seeded item |
| `code` | `VARCHAR(64)` | **Unique**, stable external id |
| `name` | `VARCHAR(120)` | |
| `description` | `VARCHAR(500)` | |
| `category` | `VARCHAR(32)` | `DEVICE` \| `PROCESSOR` \| `SECURITY` \| `SOFTWARE` \| `NETWORK` |
| `rarity` | `VARCHAR(16)` | `COMMON` \| `UNCOMMON` \| `RARE` \| `EPIC` \| `LEGENDARY` |
| `equipment_slot` | `VARCHAR(16)` | The slot the item goes in |
| `price` | `BIGINT` | ≥ 0; **the only price the server will ever charge** |
| `active` | `BOOLEAN` | Inactive items are hidden and cannot be bought |
| `stackable` | `BOOLEAN` | Always `false` in Phase 4; reserved for consumables |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

CHECK constraints enforce the category, rarity, slot, non-negative price and both
booleans. Indexed on `active`, `category` and `rarity`.

### `item_effects`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `item_id` | `UUID` | FK → `items` (cascade) |
| `effect_type` | `VARCHAR(32)` | The five controlled effect types |
| `effect_value` | `INTEGER` | 0–100, a percentage |

**`UNIQUE (item_id, effect_type)`** — one row per effect per item, so aggregation
cannot double count. `CHECK (effect_value >= 0 AND effect_value <= 100)`, and the
type is CHECK-constrained. Indexed on `item_id`.

### `player_inventory`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key; this is the `inventoryItemId` an equip request sends |
| `user_id` | `UUID` | FK → `users` (cascade) |
| `item_id` | `UUID` | FK → `items` (**restrict**) |
| `quantity` | `INTEGER` | `> 0`; fixed at 1 in Phase 4 |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

**`UNIQUE (user_id, item_id)`** — the Phase 4 rule that a player owns at most one
copy of any item, and the reason a duplicate purchase is a `409` rather than a
second row. `ON DELETE RESTRICT` on the item means an item still owned by a player
cannot be deleted out from under their inventory.

The `quantity` column exists only so a future consumable needs no schema redesign;
equipment never increments it.

### `player_equipment`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `UUID` | Primary key |
| `user_id` | `UUID` | FK → `users` (cascade) |
| `slot` | `VARCHAR(16)` | CHECK-constrained to the five slots |
| `inventory_item_id` | `UUID` | FK → `player_inventory` (cascade) |
| `equipped_at` | `TIMESTAMPTZ` | Replaced in place on a swap |

- **`UNIQUE (user_id, slot)`** — one item per slot.
- **`UNIQUE (inventory_item_id)`** — the same ownership row cannot occupy two slots.

The row references an *inventory* row rather than an item, so the join back to
the item definition is explicit and the loadout cannot point at something the
player does not own.

---

## API overview

All endpoints are under `/api/v1`. Success bodies use
`{ "success": true, "data": ..., "message": ... }`; errors use
`{ "success": false, "message": "...", "errors": { "field": "..." } }`.

| Method | Endpoint | Auth | Success | Description |
| --- | --- | --- | --- | --- |
| `POST` | `/auth/register` | none | `201` | Create a player account |
| `POST` | `/auth/login` | none | `200` | Exchange credentials for tokens |
| `POST` | `/auth/refresh` | none | `200` | Rotate tokens, new access token |
| `POST` | `/auth/logout` | none | `200` | Revoke a refresh token |
| `GET` | `/users/me` | bearer | `200` | The authenticated account |
| `GET` | `/player/profile` | bearer | `200` | Profile **and** live energy state |
| `GET` | `/player/missions` | bearer | `200` | Mission catalogue (optional `?category=`) |
| `GET` | `/player/missions/{id}` | bearer | `200` | One mission with the caller's status |
| `GET` | `/player/missions/{id}/progress` | bearer | `200` | The caller's progress on that mission |
| `POST` | `/player/missions/{id}/start` | bearer | `200` | Start a mission, spend energy, **return the puzzle** |
| `GET` | `/player/missions/{id}/puzzle` | bearer | `200` | Re-read the active puzzle after a reload |
| `POST` | `/player/missions/{id}/puzzle/submit` | bearer | `200` | Submit an answer for that mission's puzzle |
| `POST` | `/player/missions/{id}/complete` | bearer | `200` | **Cannot pay.** Kept for compatibility |
| `GET` | `/player/shop` | bearer | `200` | Active item catalogue + balance + owned flags |
| `GET` | `/player/shop/items/{itemId}` | bearer | `200` | One item's server-defined detail |
| `POST` | `/player/shop/items/{itemId}/purchase` | bearer | `201` | Buy at the catalogue price. **No request body** |
| `GET` | `/player/inventory` | bearer | `200` | Owned items with rarity, effects, equipped state |
| `GET` | `/player/equipment` | bearer | `200` | All five slots plus the aggregated bonuses |
| `POST` | `/player/equipment/{slot}` | bearer | `200` | Equip `{ inventoryItemId }`, replacing the slot |
| `DELETE` | `/player/equipment/{slot}` | bearer | `200` | Empty a slot; the item stays owned |

Energy is exposed on `/player/profile` rather than through a separate endpoint,
so the client needs one request to draw the meter and decide whether a mission
is startable.

**No mission endpoint accepts a user id or a reward amount.** The puzzle submit
body has exactly `puzzleId` and `answer`.

`403` from a mission endpoint means the player is below `requiredLevel`.
`409` from `start` means the mission is already completed.

### `POST /api/v1/auth/register`

```json
{ "username": "shadow", "email": "shadow@example.com", "password": "StrongPassword123!" }
```

Validation: username 3–32 chars of `[a-zA-Z0-9_]`; valid email; password 8–72
chars containing a lowercase letter, an uppercase letter, a digit and a symbol.
Duplicate username or email → `409`.

> There is **no** `role` field in the request. `PLAYER` is assigned server-side.

### `POST /api/v1/auth/login`

```json
{ "email": "shadow@example.com", "password": "StrongPassword123!" }
```

Returns `accessToken`, `refreshToken`, `tokenType`, `expiresIn`,
`refreshExpiresIn` and the `user` summary. Emails are normalised to lower case.

### `GET /api/v1/player/missions`

Filters: `?category=RECON|EXPLOIT|CRYPTOGRAPHY|NETWORK|INTELLIGENCE`. An unknown
value is rejected with `400`. Inactive missions are hidden entirely. Each entry
now carries `puzzleType` so the board can label the challenge before starting.

`lockReason` is a player-safe explanation such as `"Requires level 7"` — it never
exposes internals.

### `GET /api/v1/player/profile`

```json
{
  "success": true,
  "data": {
    "id": "…",
    "username": "shadow",
    "displayName": "shadow",
    "level": 2,
    "experience": 140,
    "xpIntoLevel": 40,
    "xpForNextLevel": 150,
    "coins": 125,
    "energy": 82,
    "energyMaximum": 100,
    "energyRegenerationEnabled": true,
    "energyRegenerationAmount": 1,
    "energyRegenerationIntervalSeconds": 300,
    "nextEnergyAt": "2026-10-06T04:32:16.977Z"
  }
}
```

`energy` is always the **regenerated** value: reading the profile applies pending
regeneration before responding. `nextEnergyAt` lets the client show a hint
without reimplementing the rule. `experience` is cumulative.

### `POST /api/v1/player/missions/{id}/start`

Request body: **none**. The response is deliberately **flattened** — every
Phase 2 mission field is repeated at the top level and `puzzle` is added, so a
deployed Phase 2 client sees a strict superset rather than a reshaped body.

```json
{
  "success": true,
  "data": {
    "id": "…",
    "code": "RECON_PERIMETER",
    "title": "Scan the Perimeter",
    "description": "…",
    "category": "RECON",
    "difficulty": "EASY",
    "puzzleType": "SEQUENCE",
    "requiredLevel": 1,
    "xpReward": 50,
    "coinReward": 25,
    "energyCost": 10,
    "estimatedDurationSeconds": 180,
    "status": "IN_PROGRESS",
    "locked": false,
    "startable": true,
    "puzzle": {
      "puzzleId": "…",
      "type": "SEQUENCE",
      "difficulty": "EASY",
      "title": "Find the next number",
      "question": "Each term is multiplied by the same factor. What number comes next?",
      "sequence": ["2", "4", "8", "16", "?"],
      "options": ["32", "33", "31", "34"],
      "startedAt": "2026-10-06T04:18:28.915Z",
      "expiresAt": "2026-10-06T04:20:28.915Z",
      "timeLimitSeconds": 120,
      "multipleChoice": true
    },
    "attemptCount": 1,
    "player": {
      "energy": 90,
      "maximum": 100,
      "regenerationEnabled": true,
      "regenerationAmount": 1,
      "regenerationIntervalSeconds": 300,
      "nextRegenerationAt": "2026-10-06T04:23:26.640Z",
      "full": false
    }
  },
  "message": "Mission started"
}
```

`sequence` carries display tokens for **every** type — the ciphertext, the terms,
the grid rows, the nodes and edges, the code — so the client renders a type
without special-casing the payload shape. `options` is empty for types that are
typed rather than chosen. `timeLimitSeconds` is for drawing a countdown only;
whether the window has actually closed is decided by the server.

**There is no `answer`, `correctAnswer` or `seed` field anywhere in this
response.**

### `GET /api/v1/player/missions/{id}/puzzle`

Re-reads the active puzzle so a page reload does not lose it. Same
client-safe shape, no answer.

### `POST /api/v1/player/missions/{id}/puzzle/submit`

```json
{ "puzzleId": "…", "answer": "32" }
```

Solved:

```json
{
  "success": true,
  "data": {
    "mission": { "id": "…", "code": "RECON_PERIMETER", "title": "Scan the Perimeter" },
    "puzzleType": "SEQUENCE",
    "outcome": "SOLVED",
    "message": "Security bypassed.",
    "rewards": { "experience": 50, "coins": 25 },
    "progression": {
      "levelBefore": 1,
      "levelAfter": 2,
      "experience": 140,
      "xpIntoLevel": 40,
      "xpForNextLevel": 150,
      "leveledUp": true,
      "levelsGained": 1
    },
    "player": { "energy": 80, "maximum": 100, "…": "…" },
    "missionCompleted": true,
    "alreadySolved": false,
    "canRetry": false
  }
}
```

Rejected:

```json
{
  "outcome": "INCORRECT",
  "message": "Incorrect answer. No rewards earned.",
  "rewards": { "experience": 0, "coins": 0 },
  "missionCompleted": false,
  "alreadySolved": false,
  "canRetry": true
}
```

| `outcome` | Meaning | Rewards | `canRetry` |
| --- | --- | --- | --- |
| `SOLVED` | Correct answer, mission completed | granted | `false` |
| `INCORRECT` | Wrong answer, puzzle spent, mission still open | zero | `true` |
| `EXPIRED` | Window closed on the server clock | zero | `false` |

A failed submission tells the player it was wrong, **not what the right answer
was**, so the challenge keeps its value on a retry. Headlines match the result
screen: `MISSION COMPLETE` / `ACCESS DENIED` / `CONNECTION TIMEOUT`.

### Status codes

| Code | Used for |
| --- | --- |
| `200` | Successful read or token operation, including a rejected answer |
| `201` | Account created, **item purchased** |
| `400` | Validation failure, malformed JSON, energy too low, not started, inactive mission/item, **not enough coins**, item does not fit the slot, unknown slot |
| `401` | Missing/invalid/expired token, bad credentials |
| `403` | Authenticated but below the mission's required level |
| `404` | Unknown endpoint or resource, **including a puzzle that is not this player's or not this mission's, or an inventory row belonging to someone else** |
| `409` | Duplicate username or email, mission already completed, puzzle already submitted, **item already owned** |
| `429` | Too many authentication attempts |
| `500` | Unexpected error (details logged, never returned) |

### `GET /api/v1/player/shop`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "21111111-0000-4000-8000-000000000008",
        "code": "NEURAL_PROCESSOR",
        "name": "Neural Processor",
        "description": "Predicts a lock before it resolves.",
        "category": "PROCESSOR",
        "rarity": "RARE",
        "slot": "PROCESSOR",
        "price": 750,
        "owned": false,
        "effects": [{ "type": "EXPERIENCE_BONUS", "value": 10 }]
      }
    ],
    "coins": 1250
  }
}
```

Inactive items are excluded by the query, not by a display filter, so a retired
item cannot leak through a sorting or paging bug. `coins` is included so the shop
screen does not need a second round trip that could disagree with the prices on
screen.

### `POST /api/v1/player/shop/items/{itemId}/purchase`

Request body: **none**. The response echoes the server's own arithmetic:

```json
{
  "success": true,
  "data": {
    "inventoryId": "…",
    "itemId": "21111111-0000-4000-8000-000000000008",
    "code": "NEURAL_PROCESSOR",
    "name": "Neural Processor",
    "pricePaid": 750,
    "coins": 500
  },
  "message": "Purchased NEURAL_PROCESSOR"
}
```

`pricePaid` is read from `items.price`, never from the request. Rejections:

| Situation | Status |
| --- | --- |
| No such item | `404` |
| Item retired | `400` |
| Already owned — nothing charged | `409` |
| Not enough coins — nothing deducted | `400` |

### `GET /api/v1/player/inventory`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "inventoryId": "…",
        "itemId": "21111111-0000-4000-8000-000000000008",
        "code": "NEURAL_PROCESSOR",
        "name": "Neural Processor",
        "description": "…",
        "category": "PROCESSOR",
        "rarity": "RARE",
        "slot": "PROCESSOR",
        "quantity": 1,
        "equipped": false,
        "equippedIn": null,
        "effects": [{ "type": "EXPERIENCE_BONUS", "value": 10 }]
      }
    ]
  }
}
```

`equipped` is derived from the loadout rather than stored on the inventory row, so
the two can never disagree. `inventoryId` is the only value a client sends back,
and the server verifies it belongs to the caller.

### `GET /api/v1/player/equipment`

Every slot is returned, including empty ones, in a fixed order:

```json
{
  "success": true,
  "data": {
    "equipment": [
      { "slot": "MAIN_DEVICE", "item": { "code": "BASIC_LAPTOP", "…": "…" } },
      { "slot": "PROCESSOR", "item": null },
      { "slot": "SECURITY", "item": null },
      { "slot": "SOFTWARE", "item": null },
      { "slot": "NETWORK", "item": null }
    ],
    "bonuses": [
      { "type": "EXPERIENCE_BONUS", "percent": 10 },
      { "type": "MISSION_SPEED", "percent": 5 }
    ]
  }
}
```

`bonuses` are the **capped aggregates the server actually applies** to rewards and
energy. A client that re-summed the item effects could disagree with the engine —
and would be wrong whenever an item was capped.

### `POST /api/v1/player/equipment/{slot}`

```json
{ "inventoryItemId": "…" }
```

That is the entire body. It cannot express a rarity, a bonus, a price or an
ownership claim. An unknown slot name is rejected as a bad path variable before
any service runs.

---

## Security model

| Concern | Implementation |
| --- | --- |
| Answer secrecy | The answer is never stored and never serialised. Only a seed is persisted; the challenge is re-derived to validate. |
| Client trust | The submit DTO has two fields. No score, XP, coins, success flag or client timer is accepted anywhere. |
| Timing | Expiry is judged with the server `Clock`. A client-supplied elapsed time does not exist in the API. |
| Puzzle ownership | A puzzle is found by `(puzzleId, userId, missionId)`. Wrong player, wrong mission and fake id all return the identical `404 "Puzzle not found for this mission"` — the response does not confirm that the id exists. |
| Duplicate reward | `UNIQUE (puzzle_id)` plus a single `status` transition out of `ACTIVE`; a solved puzzle replayed returns `alreadySolved: true` with zero rewards. |
| Bypass | `/complete` cannot award without `COMPLETED` status, which only a solved puzzle can produce. |
| Energy integrity | Energy is only ever changed by `PlayerProfile`; the timestamp anchor is server-set; regeneration is clamped at both ends. |
| Concurrency | Pessimistic write locks, always taken in the order profile → progress → puzzle attempt, so concurrent starts cannot double-spend. |
| Password storage | BCrypt, cost 12. Plaintext is never stored or logged. |
| Access tokens | HS256 JWT, 15-minute default. Claims are only `sub`, `role`, `iat`, `exp`, `iss`, `typ`, `jti` — no email or username. |
| Refresh tokens | 256-bit random opaque strings. Only the SHA-256 hash is persisted, so a database leak cannot be replayed. |
| Token rotation | Every refresh revokes the presented token and issues a new one. Reuse is detected and revokes every session for that user. |
| Logout | Revokes the refresh token server-side; not merely a client-side state wipe. |
| Client-side role selection | Impossible — no `role` field exists in the registration request. |
| Ownership | `/player/profile` takes no user id; the caller is resolved from the security context. |
| Item price | Read from `items.price`. The purchase endpoint declares no request body, so no price, coin total or quantity can be supplied. |
| Item rarity and effects | Read from `items` and `item_effects`. No endpoint writes either table, so there is no path by which a client can influence a bonus. |
| Inventory ownership | Every query is keyed on the authenticated `userId`. Another player's `inventoryId` is not found, and the identical `404` is returned for a guessed id that exists nowhere. |
| Equipped state | Written only by `EquipmentService`, which locks the profile row and verifies ownership and slot compatibility before writing. |
| Duplicate purchase | `UNIQUE (user_id, item_id)` plus a check under the profile lock, so two simultaneous attempts cannot both succeed. Rejection charges nothing. |
| Purchase atomicity | Coin deduction and inventory insert commit together or not at all, so a player is never charged for an item they did not receive. |
| Bonus aggregation | Exactly one implementation, capped per effect type. No mechanic may add a percentage to a reward on its own. |
| Deterministic payouts | Integer arithmetic with halves rounding up; no floating point in any reward or cost calculation. |
| Starter item | Granted by a hard-coded code inside the registration transaction. A registration request cannot name or choose it. |
| User enumeration | Unknown accounts and wrong passwords return an identical `401` message, and the unknown-account path still performs a hash comparison so timings match. |
| Disabled accounts | Rejected at login, and any already-issued access token stops working immediately. |
| Error leakage | Stack traces, SQL and JWT internals are logged server-side only. |
| CORS | Explicit origin list. Never `*`. |
| CSRF | Disabled by design: the API is stateless bearer-token only, with no ambient credential to ride on. |
| Rate limiting | Fixed-window counter per client IP on `/auth/login` and `/auth/register`; only failures count, so a correct login never locks a user out. |
| SQL injection | Not possible — all access goes through parameterised JPA queries. |

---

## Frontend

```text
MISSION BOARD  ──start──▶  PUZZLE SCREEN  ──submit──▶  RESULT
                                                        │
                        ┌───────────────────────────────┤
                   SOLVED (rewards,        INCORRECT / EXPIRED
                   level-up, CONTINUE)     (TRY AGAIN / back to missions)
```

| Component | Role |
| --- | --- |
| `components/MissionBoard.tsx` | Owns the board → puzzle → result state machine |
| `components/puzzle.tsx` | `PuzzlePanel`, `PuzzleResultOverlay`, `EnergyMeter` |
| `components/puzzleConstants.ts` | Type labels, outcome headlines, countdown formatting |
| `components/missions.tsx` | Mission card, now labelled with its puzzle type |
| `components/equipment.tsx` | `ShopItemCard`, `InventoryItemCard`, `LoadoutPanel`, `RarityBadge`, `EffectList`, `BonusChip` |
| `components/equipmentConstants.ts` | Slot labels, effect labels, rarity colours. **No prices or bonuses** |

Routes are `/dashboard`, `/missions`, `/shop`, `/inventory` and `/profile`, all
inside the authenticated layout. Missions remain the primary action on the
dashboard; the shop and inventory are reachable from the navigation and from the
dashboard's gear panel.

The frontend holds **no price table, no rarity list and no bonus arithmetic**.
Rarity colours and slot labels are the only things it decides. Prices come from
the catalogue response, affordability is a comparison of two server figures used
only to disable a button, and the bonuses shown are the server's capped
aggregates rather than a local sum. Nothing toggles equipped state locally
either — the inventory re-reads after every action, so a rejected request cannot
leave the screen claiming otherwise.

The result screen shows rewards and level-ups on success, and never reveals the
correct answer on failure. The energy meter renders `⚡ 82 / 100` with
`+1 every 5 min` underneath, and the insufficient-energy panel states what is
required, what is available, and that regeneration is running. The frontend
countdown is presentation only — it never decides an outcome, and the client
re-reads authoritative state from the backend rather than trusting its own tick.

---

## Project layout

```text
cyber-heist/
├── backend/
│   ├── src/main/java/com/cyberheist/
│   │   ├── config/        Security, CORS, rate limit, progression, energy properties
│   │   ├── security/      JWT provider, filters, CurrentUser
│   │   ├── auth/          Auth controller, services, tokens, DTOs
│   │   ├── user/          User entity, repository, /users/me
│   │   ├── player/        Profile entity, repository, /player/profile
│   │   ├── mission/       Mission + progress, controller, service, DTOs
│   │   ├── puzzle/        PuzzleType, PuzzleProvider, PuzzleService, PuzzleAttempt
│   │   │   ├── provider/  The five providers + shared PuzzleGeneratorSupport
│   │   │   └── dto/       PuzzleChallengeView — the answer is dropped here
│   │   ├── energy/        EnergyService, EnergySnapshot — lazy regeneration
│   │   ├── shop/          Item catalogue, inventory, equipment, ShopController
│   │   ├── reward/        RewardService — the single payout path
│   │   ├── progression/   LevelCurve, ProgressionService, ProgressionResult
│   │   ├── common/        ApiResponse, ApiErrorResponse, auditing base
│   │   └── exception/     Domain exceptions + GlobalExceptionHandler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/  V1 core · V2 mission system · V3 seed · V4 puzzles + regen
│   │                      · V5 inventory + equipment + shop
│   ├── src/test/          332 tests
│   ├── verify-live.ps1    Live HTTP verification of the whole Phase 4 flow
│   ├── Dockerfile
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/    UI primitives, MissionBoard, puzzle.tsx, equipment.tsx
│   │   ├── pages/         Login, Register, Dashboard, Missions, Shop, Inventory, Profile, 404
│   │   ├── layouts/       Auth and dashboard shells (incl. main navigation)
│   │   ├── services/      apiClient, auth/user/player/mission/shop/equipment services
│   │   ├── context/       AuthContext (incl. transparent token refresh)
│   │   ├── routes/        Route table and auth guards
│   │   ├── types/         Shared TypeScript types
│   │   ├── utils/         Client-side validation
│   │   └── test/          Vitest suites — 100 tests
│   └── package.json
├── docker-compose.yml
├── .env.example
└── README.md
```

### Adding a new puzzle type

1. Add the constant to `PuzzleType` and a CHECK constraint to a new migration.
2. Create `puzzle/provider/YourPuzzleProvider.java` as a `@Component`
   implementing `PuzzleProvider`. `create()` **must** be a pure function of
   `(difficulty, seed)` — the whole no-stored-answer design depends on it.
3. Extend `PuzzleGeneratorSupport` if the type needs distractors or options, so
   generation is validated the same way as the others.
4. Map a mission category to the new type, or seed missions with it directly.

`PuzzleService` picks the bean up automatically and refuses to start if the type
is unclaimed. `MissionService`, the controller, the DTOs and the database need
no change. Add a contract test to `PuzzleProviderContractTest` and a provider
test; the shared contract suite will check determinism and option validity for
the new type for free.

### Adding a new item, category, slot or effect

1. Add the constant to `ItemCategory`, `ItemRarity`, `EquipmentSlot` or
   `ItemEffectType`, and extend the matching CHECK constraint in a **new**
   migration. Never edit V1–V5.
2. Seed the item row, and its `item_effects` rows after it, so the foreign key can
   be satisfied.
3. Nothing else changes. The shop, inventory and loadout read the tables
   generically, and `LoadoutService` iterates `EquipmentSlot.values()`.

If the new effect type should modify a mechanic, apply it in
`EquipmentBonusService` **and only there** — add a cap in the `switch`, then have
the owning service read `bonusFor(userId, TYPE)`. Do not add a percentage in a
second place, and do not read equipment from inside a puzzle provider.

### Adding a Phase 4-style reward source

Grant XP or coins through `RewardService.grant`, having first applied equipment
with `RewardService.applyBonuses(base, bonusesFor(userId))`. That keeps the
"base, then equipment, then final" rule in one implementation and means a new
source gets equipment bonuses without any change of its own.

---

## Known limitations

- **PostgreSQL has not been executed against a real instance.** The migrations
  ran through Flyway against H2 in PostgreSQL mode and use portable constructs,
  but a local PostgreSQL 18 server is listening on 5432 while requiring password
  authentication for which no credentials were available, and the Docker daemon
  had no `postgres` container. H2-in-PostgreSQL-mode is **not** PostgreSQL
  runtime verification. Before deploying, run `mvn test` and the full HTTP flow
  against a real instance.
- `MISSION_SPEED` and `PUZZLE_BONUS` are defined, capped and surfaced but do not
  yet change anything. Mission duration is advisory and puzzles are generated
  from a seed, so there is no mechanic for them to influence yet. They will
  appear in the UI as bonuses that are counted but not spent.
- A player can own only one copy of each item, so equipment cannot be stacked
  and there is nothing to upgrade. The `quantity` column and the
  `UNIQUE (user_id, item_id)` constraint would both need revisiting if stacking
  or consumables are ever introduced.
- There is no selling, refunding or trading, so coins are a one-way sink. A
  player who buys the wrong item keeps it.
- The catalogue is seeded by migration and has no admin surface. Retiring an item
  means a migration or a direct database edit; `items.active` exists and is
  honoured everywhere, but nothing writes it at runtime.
- Items cannot be traded or bound to an account permanently, and there is no
  cooldown on re-equipping, which makes loadout swapping free.
- The economy has been sanity-checked against mission rewards but not playtested
  at scale; prices are plausible, not tuned by telemetry.
- Every puzzle type is currently multiple-choice or short-answer with one fixed
  rule family. There is no adaptive difficulty, no hint system, and no scoring
  beyond the binary correct/incorrect that the mission reward depends on.
- Restarting an in-progress mission charges energy again and supersedes the
  previous puzzle. That makes double-clicking a deliberate cost, but an
  interrupted player pays twice to resume.
- A player who runs out of energy mid-mission cannot retry until regeneration
  catches up; there is no consumable or level-up refill yet.
- Rate limiting state is in memory, so it is per-instance. Moving to a shared
  store (Redis) is required before running multiple backend instances.
- Access and refresh tokens are held in `localStorage` for convenience. That is
  acceptable for this game, but a production deployment handling real money or
  PII should prefer an `httpOnly` cookie with CSRF protection.
- Refresh tokens are not yet garbage-collected; `RefreshTokenRepository` exposes
  `deleteByExpiresAtBefore` for a scheduled cleanup job in a later phase.
- There is no "forgot password" or email verification flow.
- `@EnableMethodSecurity` is configured and `ROLE_ADMIN` exists, but no
  admin-only endpoints are exposed yet — mission CRUD is not exposed at all.

---

## License

Private project. All rights reserved.
