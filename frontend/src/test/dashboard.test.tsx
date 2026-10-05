import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { DashboardPage } from '@/pages/DashboardPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import type { PlayerProfile } from '@/types'

const profile: PlayerProfile = {
  id: '22222222-2222-2222-2222-222222222222',
  username: 'shadow',
  displayName: 'shadow',
  level: 1,
  experience: 0,
  coins: 100,
  energy: 100,
}

/** Persists a session so AuthProvider treats the visitor as signed in. */
function signIn(accessToken = tokens.accessToken) {
  saveSession({
    accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('DashboardPage', () => {
  it('renders level, XP, coins and energy from the API', async () => {
    signIn()

    vi.spyOn(globalThis, 'fetch')
      // Session restore
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      // Profile
      .mockResolvedValueOnce(jsonResponse({ success: true, data: profile }))

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/welcome back/i)).toBeInTheDocument()
    // Exact matches avoid colliding with the "coming soon" copy below.
    expect(screen.getByText('shadow')).toBeInTheDocument()
    expect(screen.getByText('Coins')).toBeInTheDocument()
    expect(screen.getByText('Energy')).toBeInTheDocument()
    expect(screen.getByText('Level')).toBeInTheDocument()
    // Coins and Energy are both 100, so assert the pair explicitly.
    expect(screen.getAllByText('100')).toHaveLength(2)
    expect(screen.getByText('0 / 100')).toBeInTheDocument()
  })

  it('refreshes the access token when the profile call is unauthorised', async () => {
    // The stored access token has expired, so restoring the session fails.
    signIn('expired-token')

    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      // Session restore rejected.
      .mockResolvedValueOnce(jsonResponse({ success: false, message: 'Authentication required' }, 401))
      // Refresh using the stored refresh token.
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens }))
      // Profile with the rotated access token.
      .mockResolvedValueOnce(jsonResponse({ success: true, data: profile }))

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/welcome back/i)).toBeInTheDocument()

    // The recovery path must actually call the refresh endpoint before the
    // profile request, proving the session was recovered rather than dropped.
    await waitFor(() =>
      expect(fetchSpy.mock.calls.map(([url]) => String(url))).toEqual([
        expect.stringContaining('/api/v1/users/me'),
        expect.stringContaining('/api/v1/auth/refresh'),
        expect.stringContaining('/api/v1/player/profile'),
      ]),
    )
  })

  it('shows an error message when the profile cannot be loaded', async () => {
    signIn()

    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Profile unavailable right now' }, 500),
      )

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText('Profile unavailable right now')).toBeInTheDocument()
  })

  it('marks the not-yet-built systems as coming soon', async () => {
    signIn()

    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: profile }))

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/mission system/i)).toBeInTheDocument()
    expect(screen.getByText(/upgrades & skill tree/i)).toBeInTheDocument()
  })
})