import type { AuthState } from '@/types'

const STORAGE_KEY = 'cyber-heist.auth'

/**
 * Persisted session, so a page refresh does not sign the player out.
 *
 * <p>The access token is intentionally kept in memory-friendly local storage:
 * the backend enforces all real authorization, so this is a convenience, not
 * a security boundary. The refresh token is what actually matters, and the
 * server revokes it on logout.
 */
export function loadSession(): AuthState | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null

    const parsed = JSON.parse(raw) as AuthState
    if (!parsed?.accessToken || !parsed?.refreshToken || !parsed?.user?.id) {
      return null
    }
    return parsed
  } catch {
    // Corrupt or unavailable storage must not break the app.
    return null
  }
}

export function saveSession(state: AuthState): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // Storage may be disabled; the session then lasts until reload.
  }
}

export function clearSession(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing to do.
  }
}