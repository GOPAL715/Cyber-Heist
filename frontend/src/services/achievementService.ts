import type {
  Achievement,
  DailyOverview
} from '@/types/phase7'

/** Reads achievement and daily state for the authenticated caller. */
export const achievementService = {
  list(token: string): Promise<Achievement[]> {
    return request<Achievement[]>('/api/v1/player/achievements', { token })
  },

  detail(token: string, code: string): Promise<Achievement> {
    return request<Achievement>(`/api/v1/player/achievements/${code}`, { token })
  },

  recent(token: string): Promise<Achievement[]> {
    return request<Achievement[]>('/api/v1/player/achievements/recent', { token })
  },
}

/** Today's objectives and the caller's streak. */
export const dailyService = {
  today(token: string): Promise<DailyOverview> {
    return request<DailyOverview>('/api/v1/player/daily', { token })
  },

  evaluate(token: string): Promise<DailyOverview> {
    return request<DailyOverview>('/api/v1/player/daily/evaluate', {
      method: 'POST',
      token,
    })
  },
}

async function request<T>(
  url: string,
  options: { method?: string; token: string; body?: unknown } = {
    method: 'GET',
    token: '',
  },
): Promise<T> {
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${options.token}`,
      'Content-Type': 'application/json',
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  })

  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: 'Request failed' }))
    throw new Error(error.message ?? `Request failed: ${response.status}`)
  }

  return response.json()
}
