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

/** The calling player's own game state. */
export interface PlayerProfile {
  id: string
  username: string
  displayName: string
  level: number
  experience: number
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