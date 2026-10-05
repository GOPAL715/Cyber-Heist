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
 * reimplements the level curve.
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
  energy: number
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