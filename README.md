# CYBER HEIST

A cyberpunk-themed browser game. This repository contains **Phase 1: the
technical foundation and authentication system**.

Phase 1 deliberately implements only:

> Registration → Login → Authentication → Protected Player Dashboard

Missions, puzzles, bosses, inventory, shops, skill trees, leaderboards,
multiplayer and AI features are **out of scope** and are not implemented. The
database and code structure are laid out so those systems can be added later
without a redesign.

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
# Backend — 54 tests
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
and applies the *same* Flyway migrations, so no external service is required and
the schema under test is the schema that ships.

---

## Database schema

Managed entirely by Flyway. Hibernate runs with `ddl-auto: validate` and never
creates or alters anything — `create` and `update` are deliberately not used.

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
    "level": 1,
    "experience": 0,
    "coins": 100,
    "energy": 100
  }
}
```

### Status codes

| Code | Used for |
| --- | --- |
| `200` | Successful read or token operation |
| `201` | Account created |
| `400` | Validation failure, malformed JSON |
| `401` | Missing/invalid/expired token, bad credentials |
| `403` | Authenticated but not permitted |
| `404` | Unknown endpoint or resource |
| `409` | Duplicate username or email |
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
│   │   ├── config/       Security, CORS, rate limit, typed properties
│   │   ├── security/     JWT provider, filters, CurrentUser
│   │   ├── auth/         Auth controller, services, tokens, DTOs
│   │   ├── user/         User entity, repository, /users/me
│   │   ├── player/       Profile entity, repository, /player/profile
│   │   ├── common/       ApiResponse, ApiErrorResponse, auditing base
│   │   └── exception/    Typed exceptions + GlobalExceptionHandler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/ V1__create_core_schema.sql
│   ├── src/test/         54 tests
│   ├── Dockerfile
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/   UI primitives and game widgets
│   │   ├── pages/        Login, Register, Dashboard, Profile, 404
│   │   ├── layouts/      Auth and dashboard shells
│   │   ├── services/     apiClient, auth/user/player services, session storage
│   │   ├── context/      AuthContext (incl. transparent token refresh)
│   │   ├── routes/       Route table and auth guards
│   │   ├── types/        Shared TypeScript types
│   │   ├── utils/        Client-side validation
│   │   └── test/         Vitest suites
│   └── package.json
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## Known limitations

- Rate limiting state is in memory, so it is per-instance. Moving to a shared
  store (Redis) is required before running multiple backend instances.
- Access and refresh tokens are held in `localStorage` for convenience. That is
  acceptable for this game, but a production deployment handling real money or
  PII should prefer an `httpOnly` cookie with CSRF protection.
- Refresh tokens are not yet garbage-collected; `RefreshTokenRepository` exposes
  `deleteByExpiresAtBefore` for a scheduled cleanup job in a later phase.
- There is no "forgot password" or email verification flow in Phase 1.
- `@EnableMethodSecurity` is configured and `ROLE_ADMIN` exists, but no
  admin-only endpoints are exposed yet.

---

## License

Private project. All rights reserved.