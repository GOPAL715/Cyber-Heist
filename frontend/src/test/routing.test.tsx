import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from '@/routes/guards'
import { AuthProvider } from '@/context/AuthContext'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, tokens } from './helpers'

/** Renders the guards and reports which route the router settled on. */
function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route element={<ProtectedRoute />}>
            <Route path="/dashboard" element={<p>Secret dashboard</p>} />
          </Route>
          <Route element={<PublicOnlyRoute />}>
            <Route path="/login" element={<p>Login page</p>} />
          </Route>
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('ProtectedRoute', () => {
  it('redirects an unauthenticated visitor away from /dashboard to /login', async () => {
    renderAt('/dashboard')

    expect(await screen.findByText('Login page')).toBeInTheDocument()
    expect(screen.queryByText('Secret dashboard')).not.toBeInTheDocument()
  })

  it('lets a signed-in player reach /dashboard', async () => {
    saveSession({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      user: tokens.user,
    })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: true, data: tokens.user }),
    )

    renderAt('/dashboard')

    expect(await screen.findByText('Secret dashboard')).toBeInTheDocument()
  })
})

describe('PublicOnlyRoute', () => {
  it('sends a signed-in player from /login to the protected area', async () => {
    saveSession({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      user: tokens.user,
    })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: true, data: tokens.user }),
    )

    renderAt('/login')

    // Redirected away from /login and landed on the protected route.
    expect(await screen.findByText('Secret dashboard')).toBeInTheDocument()
    expect(screen.queryByText('Login page')).not.toBeInTheDocument()
  })

  it('shows the login page to an anonymous visitor', async () => {
    renderAt('/login')

    expect(await screen.findByText('Login page')).toBeInTheDocument()
  })
})