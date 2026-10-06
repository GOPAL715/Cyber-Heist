/** Matches the backend `Role` enum. */
export type Role = 'PLAYER' | 'ADMIN'

/** Safe account representation; the backend never returns a password hash. */
export interface AuthUser {
  id: string
  username: string
  email: string
  role: Role
}

/** Token pair returned by login and refresh. */
export interface AuthTokens {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  refreshExpiresIn?: number
  user: AuthUser
}

/** The authenticated account as kept in app state. */
export interface AuthState {
  accessToken: string
  refreshToken: string
  user: AuthUser
}

/** Envelope used by every successful backend response. */
export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
  timestamp: string
}

/** Envelope used by every backend error response. */
export interface ApiErrorResponse {
  success: boolean
  message: string
  errors?: Record<string, string>
  path?: string
  timestamp: string
}

/**
 * The calling player's own game state.
 *
 * <p>`xpIntoLevel` and `xpForNextLevel` come from the server so the UI never
 * reimplements the level curve. The energy fields do the same for
 * regeneration, so the header can show "82 / 100" and "+1 every 5 min" without
 * hardcoding either number.
 */
export interface PlayerProfile {
  id: string
  username: string
  displayName: string
  level: number
  /** Total cumulative XP; never reset on level up. */
  experience: number
  xpIntoLevel: number
  xpForNextLevel: number
  coins: number
  /** Refreshed by the server on every read; never computed locally. */
  energy: number
  energyMaximum: number
  energyRegenerationEnabled: boolean
  energyRegenerationAmount: number
  energyRegenerationIntervalSeconds: number
  /** Advisory only. The server re-checks affordability when a mission starts. */
  nextEnergyAt: string | null
}

export interface RegisterPayload {
  username: string
  email: string
  password: string
}

export interface LoginPayload {
  email: string
  password: string
}

// ---------------------------------------------------------------------------
// Missions
// ---------------------------------------------------------------------------

export type MissionCategory =
  | 'RECON'
  | 'EXPLOIT'
  | 'CRYPTOGRAPHY'
  | 'NETWORK'
  | 'INTELLIGENCE'

export type MissionDifficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'ELITE'

export type MissionStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'

/** The puzzle families the backend engine can generate. */
export type PuzzleType = 'CIPHER' | 'SEQUENCE' | 'PATTERN' | 'LOGIC' | 'TIMED'

/**
 * A mission as the server presents it, already decorated with the caller's
 * level-gating. The client never computes rewards or eligibility.
 */
export interface Mission {
  id: string
  code: string
  title: string
  description: string
  category: MissionCategory
  difficulty: MissionDifficulty
  requiredLevel: number
  xpReward: number
  coinReward: number
  energyCost: number
  estimatedDurationSeconds: number
  status: MissionStatus
  locked: boolean
  lockReason: string | null
  startable: boolean
  blockedReason: string | null
  /** Which puzzle family this mission serves, so the board can label it. */
  puzzleType: PuzzleType
}

/**
 * A puzzle as the player is shown it.
 *
 * There is deliberately no `answer` or `correctAnswer` field, and the backend
 * does not send one: the answer is re-derived on the server from a seed that is
 * never transmitted, so it cannot be read out of devtools, the network tab or a
 * database dump.
 *
 * `sequence` carries the display tokens for every family - ciphertext, sequence
 * terms, grid rows, the node/edge list, the code - so each one renders without
 * special-casing the payload shape.
 */
export interface PuzzleChallenge {
  puzzleId: string
  type: PuzzleType
  difficulty: MissionDifficulty
  title: string
  question: string
  sequence: string[]
  /** Empty when the player types the answer instead of choosing. */
  options: string[]
  startedAt: string
  expiresAt: string
  /** For drawing the countdown. The server decides whether time is up. */
  timeLimitSeconds: number
}

/**
 * The response to starting a mission.
 *
 * Deliberately flat: it repeats every `Mission` field so a Phase 2 client
 * keeps working, and adds the puzzle. Nesting the mission under a key would
 * silently move every existing field.
 */
export type MissionStart = Mission & {
  puzzle: PuzzleChallenge
  attemptCount: number
  player: EnergySnapshot
}

/** Server-computed energy figures, returned with the profile and after actions. */
export interface EnergySnapshot {
  energy: number
  maximum: number
  regenerationEnabled: boolean
  regenerationAmount: number
  regenerationIntervalSeconds: number
  nextRegenerationAt: string | null
}

/** The result of completing a mission, with every value computed server-side. */
export interface MissionCompletion {
  mission: {
    id: string
    code: string
    title: string
  }
  rewards: {
    experience: number
    coins: number
  }
  progression: {
    levelBefore: number
    levelAfter: number
    experience: number
    xpIntoLevel: number
    xpForNextLevel: number
    leveledUp: boolean
    levelsGained: number
  }
  player: {
    level: number
    experience: number
    coins: number
    energy: number
  }
  alreadyCompleted: boolean
}

/**
 * What the server decided about a submitted answer.
 *
 * Only `SOLVED` carries a reward. A wrong answer does not reveal what the right
 * one was, so the challenge keeps its value on a retry.
 */
export type PuzzleOutcome = 'SOLVED' | 'INCORRECT' | 'EXPIRED'

export interface PuzzleSubmission {
  mission: {
    id: string
    code: string
    title: string
  }
  puzzleType: PuzzleType
  outcome: PuzzleOutcome
  message: string
  rewards: {
    experience: number
    coins: number
  }
  progression: {
    levelBefore: number
    levelAfter: number
    experience: number
    xpIntoLevel: number
    xpForNextLevel: number
    leveledUp: boolean
    levelsGained: number
  }
  player: EnergySnapshot
  missionCompleted: boolean
  /** True for a repeat submission: reported honestly, paid nothing. */
  alreadySolved: boolean
  /** True when starting the mission again produces a new puzzle. */
  canRetry: boolean
}