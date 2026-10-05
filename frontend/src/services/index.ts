import { request } from './apiClient'
import type {
  AuthTokens,
  AuthUser,
  LoginPayload,
  Mission,
  MissionCategory,
  MissionCompletion,
  PlayerProfile,
  RegisterPayload,
} from '@/types'

/** Registration, login, refresh and logout calls. */
export const authService = {
  register(payload: RegisterPayload): Promise<AuthUser> {
    return request<AuthUser>('/api/v1/auth/register', {
      method: 'POST',
      body: payload,
    })
  },

  login(payload: LoginPayload): Promise<AuthTokens> {
    return request<AuthTokens>('/api/v1/auth/login', {
      method: 'POST',
      body: payload,
    })
  },

  refresh(refreshToken: string): Promise<AuthTokens> {
    return request<AuthTokens>('/api/v1/auth/refresh', {
      method: 'POST',
      body: { refreshToken },
    })
  },

  logout(refreshToken: string): Promise<void> {
    return request<void>('/api/v1/auth/logout', {
      method: 'POST',
      body: { refreshToken },
    })
  },
}

/** Endpoints that act on the authenticated caller only. */
export const userService = {
  me(token: string): Promise<AuthUser> {
    return request<AuthUser>('/api/v1/users/me', { token })
  },
}

/**
 * Player endpoints.
 *
 * <p>No method accepts a user id: the backend always resolves the caller from
 * the access token, so the UI cannot request another player's data.
 */
export const playerService = {
  profile(token: string): Promise<PlayerProfile> {
    return request<PlayerProfile>('/api/v1/player/profile', { token })
  },
}

/**
 * Mission endpoints.
 *
 * <p>Start and complete send no body at all: the client asks for an action and
 * the server decides eligibility, energy cost and rewards. There is no method
 * that could pass a user id or a reward amount.
 */
export const missionService = {
  list(token: string, category?: MissionCategory): Promise<Mission[]> {
    const query = category ? `?category=${encodeURIComponent(category)}` : ''
    return request<Mission[]>(`/api/v1/player/missions${query}`, { token })
  },

  detail(token: string, missionId: string): Promise<Mission> {
    return request<Mission>(`/api/v1/player/missions/${missionId}`, { token })
  },

  start(token: string, missionId: string): Promise<Mission> {
    return request<Mission>(`/api/v1/player/missions/${missionId}/start`, {
      method: 'POST',
      token,
    })
  },

  complete(token: string, missionId: string): Promise<MissionCompletion> {
    return request<MissionCompletion>(`/api/v1/player/missions/${missionId}/complete`, {
      method: 'POST',
      token,
    })
  },
}