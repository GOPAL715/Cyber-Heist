import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { DashboardPage } from '@/pages/DashboardPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import type { Mission, PlayerProfile } from '@/types'
import { mission } from './missionFixtures'

/**
 * The profile as the backend now sends it.
 *
 * <p>Energy arrives with the regeneration policy attached, so the header can
 * show the balance and the rate without hardcoding either number.
 */
const profile: PlayerProfile = {
  id: '22222222-2222-2222-2222-222222222222',
  username: 'shadow',
  displayName: 'shadow',
  level: 1,
  experience: 0,
  xpIntoLevel: 0,
  xpForNextLevel: 100,
  coins: 100,
  energy: 100,
  energyMaximum: 100,
  energyRegenerationEnabled: true,
  energyRegenerationAmount: 1,
  energyRegenerationIntervalSeconds: 300,
  nextEnergyAt: new Date(Date.now() + 120_000).toISOString(),
}

/** A mission the player can act on right away. */
const availableMission: Mission = mission()

/**
 * Mocks the calls the dashboard makes in order: session restore, profile, and
 * the mission board list.
 */
function mockDashboardRequests(overrides: {
  profile?: PlayerProfile
  missions?: Mission[]
} = {}) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(
      jsonResponse({ success: true, data: overrides.profile ?? profile }),
    )
    .mockResolvedValueOnce(
      jsonResponse({
        success: true,
        data: overrides.missions ?? [availableMission],
      }),
    )
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
    mockDashboardRequests()

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/welcome back/i)).toBeInTheDocument()
    expect(screen.getByText('shadow')).toBeInTheDocument()
    expect(screen.getByText('Coins')).toBeInTheDocument()
    expect(screen.getByText('Level')).toBeInTheDocument()
    expect(screen.getByText('Coins')).toBeInTheDocument()

    // Energy is shown as a balance against the cap, with the regeneration rate
    // read from the server rather than hardcoded.
    const meter = screen.getByRole('progressbar', { name: 'Energy remaining' })
    expect(meter).toHaveAttribute('aria-valuenow', '100')
    expect(meter).toHaveAttribute('aria-valuemax', '100')
    expect(screen.getByText(/⚡ 100 \/ 100/)).toBeInTheDocument()
    expect(screen.getByText(/\+1 every 5 min/)).toBeInTheDocument()
    expect(screen.getByText('0 / 100')).toBeInTheDocument()
  })

  it('renders the mission board with the player\'s missions', async () => {
    signIn()
    mockDashboardRequests()

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText(/mission board/i)).toBeInTheDocument()
    expect(screen.getByText('Start mission')).toBeInTheDocument()
    // Rewards are displayed as the server sent them.
    expect(screen.getByText('+50')).toBeInTheDocument()
    expect(screen.getByText('+25')).toBeInTheDocument()
    expect(screen.getByText('-10')).toBeInTheDocument()
  })

  it('warns when the player is out of energy', async () => {
    signIn()
    mockDashboardRequests({
      profile: {
        ...profile,
        energy: 0,
        nextEnergyAt: new Date(Date.now() + 180_000).toISOString(),
      },
    })

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/⚡ 0 \/ 100/)).toBeInTheDocument()
    expect(screen.getByText(/not enough energy to start a mission/i)).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Energy remaining' })).toHaveAttribute(
      'aria-valuenow',
      '0',
    )
  })

  it('says so plainly when regeneration is switched off', async () => {
    signIn()
    mockDashboardRequests({
      profile: { ...profile, energyRegenerationEnabled: false, nextEnergyAt: null },
    })

    renderWithProviders(<DashboardPage />)

    expect(
      await screen.findByText(/regeneration is switched off/i),
    ).toBeInTheDocument()
    expect(screen.queryByText(/\+1 every/i)).not.toBeInTheDocument()
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
      // Mission board list.
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [availableMission] }))

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/welcome back/i)).toBeInTheDocument()

    // The recovery path must actually call the refresh endpoint before the
    // profile request, proving the session was recovered rather than dropped.
    await waitFor(() =>
      expect(fetchSpy.mock.calls.map(([url]) => String(url))).toEqual([
        expect.stringContaining('/api/v1/users/me'),
        expect.stringContaining('/api/v1/auth/refresh'),
        expect.stringContaining('/api/v1/player/profile'),
        expect.stringContaining('/api/v1/player/missions'),
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
    mockDashboardRequests()

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText('Scan the Perimeter')).toBeInTheDocument()
    // The puzzle engine is built now, so its placeholder is gone.
    expect(screen.queryByText(/puzzle engine/i)).not.toBeInTheDocument()
    expect(screen.getByText(/upgrades & skill tree/i)).toBeInTheDocument()
  })
})