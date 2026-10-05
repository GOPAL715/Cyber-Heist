import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { ApiError } from '@/services/apiClient'
import { authService, userService } from '@/services'
import { clearSession, loadSession, saveSession } from '@/services/sessionStorage'
import type { AuthState, AuthTokens, AuthUser, LoginPayload, RegisterPayload } from '@/types'

export interface AuthContextValue {
  user: AuthUser | null
  isAuthenticated: boolean
  /** True while the stored session is being validated on first load. */
  isInitialising: boolean
  login: (payload: LoginPayload) => Promise<void>
  register: (payload: RegisterPayload) => Promise<void>
  logout: () => Promise<void>
  /**
   * Sends a request with a valid access token, transparently refreshing it
   * once if it has expired.
   */
  authorizedRequest: <T>(operation: (token: string) => Promise<T>) => Promise<T>
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthState | null>(null)
  const [isInitialising, setIsInitialising] = useState(true)
  // Guards against several concurrent refreshes all using the same token.
  const refreshInFlight = useRef<Promise<AuthTokens> | null>(null)

  const applySession = useCallback((next: AuthState | null) => {
    setSession(next)
    if (next) saveSession(next)
    else clearSession()
  }, [])

  // On mount, restore any stored session and confirm the backend still agrees.
  useEffect(() => {
    let cancelled = false

    async function restore() {
      const stored = loadSession()
      if (!stored) {
        setIsInitialising(false)
        return
      }

      try {
        const user = await userService.me(stored.accessToken)
        if (cancelled) return
        applySession({ ...stored, user })
      } catch (error) {
        // An expired access token is normal, not a reason to sign the player
        // out: the refresh token is very likely still valid. Try to recover
        // once before discarding the session.
        if (error instanceof ApiError && error.isUnauthorized) {
          try {
            const tokens = await authService.refresh(stored.refreshToken)
            if (cancelled) return
            applySession({
              accessToken: tokens.accessToken,
              refreshToken: tokens.refreshToken,
              user: tokens.user,
            })
            return
          } catch {
            // Refresh token also rejected: the session is genuinely over.
          }
        }
        if (!cancelled) applySession(null)
      } finally {
        if (!cancelled) setIsInitialising(false)
      }
    }

    void restore()
    return () => {
      cancelled = true
    }
  }, [applySession])

  const login = useCallback(
    async (payload: LoginPayload) => {
      const tokens = await authService.login(payload)
      applySession({
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
        user: tokens.user,
      })
    },
    [applySession],
  )

  const register = useCallback(
    async (payload: RegisterPayload) => {
      // Registration does not return tokens, so sign in straight afterwards.
      await authService.register(payload)
      await login({ email: payload.email, password: payload.password })
    },
    [login],
  )

  const logout = useCallback(async () => {
    const current = session
    applySession(null)

    if (!current) return
    try {
      await authService.logout(current.refreshToken)
    } catch (error) {
      // The local session is already gone; a failed server-side revoke is
      // surfaced in the log rather than blocking the user.
      console.warn('Server-side logout failed:', error instanceof Error ? error.message : error)
    }
  }, [applySession, session])

  const refreshAccessToken = useCallback(async (): Promise<AuthTokens> => {
    const current = loadSession()
    if (!current) throw new ApiError(401, 'Your session has expired. Please sign in again.')

    // Share one in-flight refresh so parallel requests do not race on rotation.
    if (!refreshInFlight.current) {
      refreshInFlight.current = authService
        .refresh(current.refreshToken)
        .finally(() => {
          refreshInFlight.current = null
        })
    }

    const tokens = await refreshInFlight.current
    applySession({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      user: tokens.user,
    })
    return tokens
  }, [applySession])

  const authorizedRequest = useCallback(
    async <T,>(operation: (token: string) => Promise<T>): Promise<T> => {
      const current = loadSession()
      if (!current) throw new ApiError(401, 'Your session has expired. Please sign in again.')

      try {
        return await operation(current.accessToken)
      } catch (error) {
        // One retry after rotating the token, which covers a normal expiry.
        if (error instanceof ApiError && error.isUnauthorized) {
          const tokens = await refreshAccessToken()
          return operation(tokens.accessToken)
        }
        throw error
      }
    },
    [refreshAccessToken],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      isAuthenticated: session !== null,
      isInitialising,
      login,
      register,
      logout,
      authorizedRequest,
    }),
    [session, isInitialising, login, register, logout, authorizedRequest],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** Access the auth state from any component below {@link AuthProvider}. */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within an AuthProvider')
  return context
}