import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '@/context/AuthContext'
import type { AuthTokens } from '@/types'

/** Wraps a page in the router and auth context it expects. */
export function renderWithProviders(
  ui: React.ReactElement,
  { route = '/' }: { route?: string } = {},
) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <Routes>
          <Route path="/" element={ui} />
          <Route path="/dashboard" element={<div>Dashboard loaded</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

/** A signed-in player, used across the auth and dashboard tests. */
export const tokens: AuthTokens = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: '11111111-1111-1111-1111-111111111111',
    username: 'shadow',
    email: 'shadow@example.com',
    role: 'PLAYER',
  },
}

/** Minimal stand-in for the parts of `Response` that the API client uses. */
export function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(JSON.stringify(body)),
  } as Response
}