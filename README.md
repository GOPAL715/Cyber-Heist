# CYBER HEIST

A cyberpunk-themed browser game.

- **Phase 1** — technical foundation and authentication.
- **Phase 2** — player progression (XP, levels, coins, energy) and the mission system.
- **Phase 3 (current)** — the puzzle engine and passive energy regeneration.

Implemented gameplay loop:

> View missions → start mission → **receive a puzzle** → solve it → submit →
> server validates → rewards → update XP/coins/energy → level up

Inventory, shop, skill tree, bosses, achievements, leaderboards, multiplayer,
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
           └─ one transaction, so a player never exists without game state

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

## Technology stack

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
# Backend — 263 tests
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
and applies the *same* Flyway migrations (V1–V4), so no external service is
required and the schema under test is the schema that ships.

What the Phase 3 suites cover:

| Suite | Covers |
| --- | --- |
| `PuzzleProviderContractTest` | Registry completeness, determinism, generality, answer/option validity across all five types |
| `CipherPuzzleProviderTest` | Correct, wrong, invalid answers; shift reversibility |
| `SequencePuzzleProviderTest` | Correct, wrong, deterministic generation, all three rule families |
| `PatternPuzzleProviderTest` | Correct, wrong, row/column rule reconstruction |
| `LogicPuzzleProviderTest` | Correct, wrong, exactly one unreachable node |
| `TimedPuzzleProviderTest` | Before expiry, after expiry, window lengths |
| `PuzzleLifecycleIntegrationTest` | start → solve → reward; wrong answer; expiry; restart supersedes |
| `PuzzleSecurityIntegrationTest` | Wrong player, wrong mission, fake id, duplicate submit, bypass, unauthenticated |
| `EnergyServiceTest` | Start at 100, spend, one interval, many intervals, cap, no negative, long inactivity, backwards clock |
| `EnergyRegenerationIntegrationTest` | Server-clock regeneration through the API, cap, partial interval |

Every Phase 2 regression test still passes, including the 8-thread concurrent
completion test, duplicate-completion protection, ownership checks, XP
progression and mission-reachability tests. Phase 2 mission tests were rewritten
to drive the puzzle loop — none was removed or weakened.

> **PostgreSQL verification status.** The migrations have been authored
> PostgreSQL-compatibly and were executed through Flyway against H2 in PostgreSQL
> mode, but they have **not** been executed against a real PostgreSQL instance.
> In this environment the local PostgreSQL 18 service requires `scram-sha-256`
> authentication on every connection path (`local`, `127.0.0.1`, `::1`) and no
> credentials were available, while the Docker daemon was not running so
> `docker compose up postgres` was unavailable. **H2 in PostgreSQL mode is not
> PostgreSQL runtime verification** and is not claimed as such. See *Known
> limitations*.

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

V1–V3 are never modified; Phase 3 is entirely additive.

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
| `201` | Account created |
| `400` | Validation failure, malformed JSON, energy too low, not started, inactive mission, **completing without solving the puzzle** |
| `401` | Missing/invalid/expired token, bad credentials |
| `403` | Authenticated but below the mission's required level |
| `404` | Unknown endpoint or resource, **including a puzzle that is not this player's or not this mission's** |
| `409` | Duplicate username or email, mission already completed, **puzzle already submitted** |
| `429` | Too many authentication attempts |
| `500` | Unexpected error (details logged, never returned) |

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
│   │   ├── reward/        RewardService — the single payout path
│   │   ├── progression/   LevelCurve, ProgressionService, ProgressionResult
│   │   ├── common/        ApiResponse, ApiErrorResponse, auditing base
│   │   └── exception/     Domain exceptions + GlobalExceptionHandler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/  V1 core · V2 mission system · V3 seed · V4 puzzles + regen
│   ├── src/test/          263 tests
│   ├── Dockerfile
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/    UI primitives, MissionBoard, puzzle.tsx, EnergyMeter
│   │   ├── pages/         Login, Register, Dashboard, Profile, 404
│   │   ├── layouts/       Auth and dashboard shells
│   │   ├── services/      apiClient, auth/user/player/mission services
│   │   ├── context/       AuthContext (incl. transparent token refresh)
│   │   ├── routes/        Route table and auth guards
│   │   ├── types/         Shared TypeScript types
│   │   ├── utils/         Client-side validation
│   │   └── test/          Vitest suites — 75 tests
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

---

## Known limitations

- **PostgreSQL has not been executed against a real instance.** The migrations
  ran through Flyway against H2 in PostgreSQL mode and use portable constructs,
  but the local PostgreSQL 18 service requires `scram-sha-256` credentials that
  were not available in this environment, and the Docker daemon was not running.
  H2-in-PostgreSQL-mode is **not** PostgreSQL runtime verification. Before
  deploying, run `mvn test` and the full HTTP flow against a real instance.
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
