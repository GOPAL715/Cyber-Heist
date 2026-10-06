import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import { DashboardPage } from '@/pages/DashboardPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import type { EquipmentLoadout, Mission, PlayerProfile } from '@/types'
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
  /** Granted one per level gained; Phase 5 added it to the profile response. */
  skillPoints: 2,
}

/** A mission the player can act on right away. */
const availableMission: Mission = mission()

/**
 * A loadout with one filled slot, as the server sends it.
 *
 * <p>The percentages are the server's aggregated, capped figures. The dashboard
 * renders them verbatim rather than summing item effects itself.
 */
const loadout: EquipmentLoadout = {
  equipment: [
    {
      slot: 'MAIN_DEVICE',
      item: {
        inventoryId: 'inv-1',
        itemId: '21111111-0000-4000-8000-000000000001',
        code: 'BASIC_LAPTOP',
        name: 'Basic Laptop',
        description: 'A refurbished deck.',
        category: 'DEVICE',
        rarity: 'COMMON',
        slot: 'MAIN_DEVICE',
        quantity: 1,
        equipped: true,
        equippedIn: 'MAIN_DEVICE',
        effects: [{ type: 'MISSION_SPEED', value: 5 }],
      },
    },
    { slot: 'PROCESSOR', item: null },
    { slot: 'SECURITY', item: null },
    { slot: 'SOFTWARE', item: null },
    { slot: 'NETWORK', item: null },
  ],
  bonuses: [{ type: 'MISSION_SPEED', percent: 5 }],
}

/**
 * Mocks the calls the dashboard makes in order: session restore, then the
 * profile and loadout pair, then the mission board list.
 *
 * <p>The loadout is now part of the dashboard because it reports the bonuses
 * actually applied to rewards, so the profile and loadout are fetched together.
 */
function mockDashboardRequests(overrides: {
  profile?: PlayerProfile
  loadout?: EquipmentLoadout
  missions?: Mission[]
} = {}) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(
      jsonResponse({ success: true, data: overrides.profile ?? profile }),
    )
    .mockResolvedValueOnce(
      jsonResponse({ success: true, data: overrides.loadout ?? loadout }),
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

    // Scoped to the progress panel: the mission board also labels a coin column,
    // so a page-wide "Coins" query would be ambiguous.
    const progress = screen.getByLabelText('Player progress')
    expect(within(progress).getByText('Coins')).toBeInTheDocument()
    expect(within(progress).getByText('Level')).toBeInTheDocument()
    expect(within(progress).getByText('Skill Points')).toBeInTheDocument()
    // Skill points are rendered from the API, not computed on the client.
    expect(within(progress).getByText('2')).toBeInTheDocument()

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
      // Loadout, fetched alongside the profile.
      .mockResolvedValueOnce(jsonResponse({ success: true, data: loadout }))
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
        expect.stringContaining('/api/v1/player/equipment'),
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

  it('summarises the loadout and links to the shop and inventory', async () => {
    signIn()
    mockDashboardRequests()

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/player loadout/i)).toBeInTheDocument()
    // The server's aggregated bonus appears in the bonuses footer. The same
    // effect also shows on the slot itself, because per-item and aggregate are
    // different figures and both are rendered.
    expect(screen.getAllByText('+5% Mission Speed').length).toBeGreaterThan(0)
    expect(screen.getByLabelText('Active bonuses')).toHaveTextContent('+5% Mission Speed')
    expect(screen.getByRole('link', { name: /visit the shop/i })).toHaveAttribute('href', '/shop')
    expect(screen.getByRole('link', { name: /open your inventory/i })).toHaveAttribute(
      'href',
      '/inventory',
    )
    // The Phase 4 placeholder is gone now the systems exist.
    expect(screen.queryByText(/upgrades & skill tree/i)).not.toBeInTheDocument()
  })

  it('omits empty slots from the compact dashboard loadout', async () => {
    signIn()
    mockDashboardRequests()

    renderWithProviders(<DashboardPage />)

    expect(await screen.findByText(/player loadout/i)).toBeInTheDocument()
    expect(screen.getByText('Basic Laptop')).toBeInTheDocument()
    expect(screen.queryByText('Empty')).not.toBeInTheDocument()
  })
})