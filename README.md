# CYBER HEIST

A cyberpunk-themed browser game.

- **Phase 1** — technical foundation and authentication.
- **Phase 2 (current)** — player progression (XP, levels, coins, energy) and the
  mission system.

Implemented gameplay loop:

> View missions → start mission → complete mission → receive server-calculated
> rewards → update XP/coins/energy → level up

Puzzle mechanics, inventory, shop, skill tree, bosses, leaderboards,
multiplayer and AI are **out of scope** and are not implemented. The schema and
code are laid out so those systems can be added later without a redesign.

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

### Mission flow

```text
MissionController
        ↓   caller resolved from the security context (never from a request)
MissionService ──── transactional, pessimistic lock on the progress row
        ↓
RewardService      ── decides the payout from server-owned mission data
        ↓
ProgressionService ── applies XP and recomputes the level (pure arithmetic)
        ↓
PlayerProfile      ── the only writer of level, XP, coins and energy
```

**The client never determines rewards.** Start and complete accept no request
body at all: the client asks for an action and the server decides eligibility,
energy cost and payout. A client that POSTs `{"xp": 999999}` is ignored — see
`MissionCompleteIntegrationTest.ignoresClientSuppliedRewards`.

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
| PostgreSQL | 17+ (local 18 verified) |
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
# Backend — 113 tests
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
and applies the *same* Flyway migrations (V1, V2, V3), so no external service is
required and the schema under test is the schema that ships.

> **PostgreSQL verification status.** The migrations have been authored
> PostgreSQL-compatibly, but they have **not** been executed against a real
> PostgreSQL instance in this environment: the local PostgreSQL 18 service
> requires a password that was not available, and a second instance could not be
> started (Windows shared-memory allocation conflict). See *Known limitations*.

---

## Database schema

Managed entirely by Flyway. Hibernate runs with `ddl-auto: validate` and never
creates or alters anything — `create` and `update` are deliberately not used.

| Migration | Contents |
| --- | --- |
| `V1__create_core_schema.sql` | `users`, `player_profiles`, `refresh_tokens` |
| `V2__create_mission_system.sql` | `missions`, `mission_progress` |
| `V3__seed_missions.sql` | 15 seeded missions across 5 categories |

V1 is never modified.

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
| `GET` | `/player/profile` | bearer | `200` | The authenticated player's profile |
| `GET` | `/player/missions` | bearer | `200` | Mission catalogue (optional `?category=`) |
| `GET` | `/player/missions/{id}` | bearer | `200` | One mission with the caller's status |
| `GET` | `/player/missions/{id}/progress` | bearer | `200` | The caller's progress on that mission |
| `POST` | `/player/missions/{id}/start` | bearer | `200` | Start a mission, spend its energy |
| `POST` | `/player/missions/{id}/complete` | bearer | `200` | Complete it and claim the reward |

**No mission endpoint accepts a user id or a reward amount.** Start and complete
take no request body at all.

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

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "0Yl1iXk3...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "refreshExpiresIn": 604800,
    "user": {
      "id": "…",
      "username": "shadow",
      "email": "shadow@example.com",
      "role": "PLAYER"
    }
  },
  "message": "Login successful"
}
```

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
    "energy": 90
  }
}
```

`xpIntoLevel` and `xpForNextLevel` are supplied so the client can draw a
progress bar without reimplementing the level curve. `experience` is cumulative.

### `GET /api/v1/player/missions`

```json
{
  "success": true,
  "data": [
    {
      "id": "…",
      "code": "RECON_PERIMETER",
      "title": "Scan the Perimeter",
      "description": "Sweep the outer ring of the target building and log every sensor…",
      "category": "RECON",
      "difficulty": "EASY",
      "requiredLevel": 1,
      "xpReward": 50,
      "coinReward": 25,
      "energyCost": 10,
      "estimatedDurationSeconds": 180,
      "status": "NOT_STARTED",
      "locked": false,
      "lockReason": null,
      "startable": true,
      "blockedReason": null
    }
  ],
  "message": "OK"
}
```

Filters: `?category=RECON|EXPLOIT|CRYPTOGRAPHY|NETWORK|INTELLIGENCE`. An unknown
value is rejected with `400`. Inactive missions are hidden entirely.

`lockReason` is a player-safe explanation such as `"Requires level 7"` — it never
exposes internals.

### `POST /api/v1/player/missions/{id}/complete`

Request body: **none**. The client only asks to complete a mission.

```json
{
  "success": true,
  "data": {
    "mission": { "id": "…", "code": "RECON_PERIMETER", "title": "Scan the Perimeter" },
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
    "player": { "level": 2, "experience": 140, "coins": 125, "energy": 90 },
    "alreadyCompleted": false
  },
  "message": "Mission complete"
}
```

Submitting a completed mission again returns `200` with `alreadyCompleted: true`
and `rewards: { "experience": 0, "coins": 0 }` — never a double payout.

### Status codes

| Code | Used for |
| --- | --- |
| `200` | Successful read or token operation |
| `201` | Account created |
| `400` | Validation failure, malformed JSON, energy too low, not started, inactive mission |
| `401` | Missing/invalid/expired token, bad credentials |
| `403` | Authenticated but below the mission's required level |
| `404` | Unknown endpoint or resource |
| `409` | Duplicate username or email, mission already completed |
| `429` | Too many authentication attempts |
| `500` | Unexpected error (details logged, never returned) |

---

## Security model

| Concern | Implementation |
| --- | --- |
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

## Project layout

```text
cyber-heist/
├── backend/
│   ├── src/main/java/com/cyberheist/
│   │   ├── config/        Security, CORS, rate limit, progression properties
│   │   ├── security/      JWT provider, filters, CurrentUser
│   │   ├── auth/          Auth controller, services, tokens, DTOs
│   │   ├── user/          User entity, repository, /users/me
│   │   ├── player/        Profile entity, repository, /player/profile
│   │   ├── mission/       Mission + progress, controller, service, DTOs
│   │   ├── reward/        RewardService — the single payout path
│   │   ├── progression/   LevelCurve, ProgressionService, ProgressionResult
│   │   ├── common/        ApiResponse, ApiErrorResponse, auditing base
│   │   └── exception/     Domain exceptions + GlobalExceptionHandler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/  V1 core · V2 mission system · V3 seed missions
│   ├── src/test/          115 tests
│   ├── Dockerfile
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/    UI primitives, MissionBoard, MissionCard, overlay
│   │   ├── pages/         Login, Register, Dashboard, Profile, 404
│   │   ├── layouts/       Auth and dashboard shells
│   │   ├── services/      apiClient, auth/user/player/mission services
│   │   ├── context/       AuthContext (incl. transparent token refresh)
│   │   ├── routes/        Route table and auth guards
│   │   ├── types/         Shared TypeScript types
│   │   ├── utils/         Client-side validation
│   │   └── test/          Vitest suites
│   └── package.json
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## Known limitations

- **No energy regeneration.** Energy starts at 100 and is only ever spent.
  The 15 seeded missions cost 313 energy in total, so a single session affords
  roughly **7 of the 15** before the player is stuck at zero. Regeneration (over
  time, on level-up, or from a consumable) belongs to a later phase — Phase 2
  deliberately implements only spending and the non-negative check. This is the
  main gap to close in Phase 3.
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
- **PostgreSQL has not been executed against a real instance.** The migrations
  ran successfully against H2 in PostgreSQL mode via Flyway, and use only
  portable constructs, but real-PostgreSQL verification was blocked in this
  environment (see the note under *Running the tests*).
- Starting a mission that is already `IN_PROGRESS` charges energy again and
  increments `attempt_count`. That makes double-clicking a deliberate cost, but
  it also means an interrupted player pays twice to resume.

---

## License

Private project. All rights reserved.