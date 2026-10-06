import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BossBoardPage } from '@/pages/BossBoardPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import {
  activeEncounter,
  boss,
  coolingBoss,
  defeatedEncounter,
  lockedBoss,
  victoryEncounter,
} from './bossFixtures'
import type { Boss, BossEncounter } from '@/types'

/**
 * The boss board.
 *
 * <p>Button state comes from the server's `canStart`, so these tests assert the
 * UI defers to that verdict and that the start request carries only a boss id.
 */

function signIn() {
  saveSession({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

function mockBoard(
  bosses: Boss[] = [boss(), lockedBoss(), coolingBoss()],
  history: BossEncounter[] = [],
) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(jsonResponse({ success: true, data: bosses }))
    .mockResolvedValueOnce(jsonResponse({ success: true, data: history }))
}

beforeEach(() => {
  vi.restoreAllMocks()
  signIn()
})

describe('BossBoardPage', () => {
  it('renders each boss with its server-defined figures', async () => {
    mockBoard()

    renderWithProviders(<BossBoardPage />)

    expect(await screen.findByText(/boss network/i)).toBeInTheDocument()
    const card = screen.getByRole('article', { name: 'The Firewall' })
    expect(card).toHaveTextContent('MEDIUM')
    expect(card).toHaveTextContent('6') // required level
    expect(card).toHaveTextContent('3') // stages
    expect(card).toHaveTextContent('350 XP')
    expect(card).toHaveTextContent('220 coins')
    expect(card).toHaveTextContent('Not a program')
  })

  it('disables the button for a locked boss and shows the server reason', async () => {
    mockBoard()

    renderWithProviders(<BossBoardPage />)

    const card = await screen.findByRole('article', { name: 'The Architect' })
    expect(within(card).getByRole('button', { name: 'Locked' })).toBeDisabled()
    expect(card).toHaveTextContent('Requires level 26')
  })

  it('shows the cooldown countdown for a boss on cooldown', async () => {
    mockBoard()

    renderWithProviders(<BossBoardPage />)

    const card = await screen.findByRole('article', { name: 'Zero Day' })
    expect(card).toHaveTextContent('On cooldown')
    // Minutes:seconds, so the shape is asserted rather than the exact value.
    expect(card).toHaveTextContent(/\d{2}:\d{2}/)
    expect(within(card).getByRole('button', { name: 'Locked' })).toBeDisabled()
  })

  it('shows a loading state before the board arrives', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockReturnValueOnce(new Promise(() => {}))

    renderWithProviders(<BossBoardPage />)

    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('surfaces a backend error when the board cannot be loaded', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Boss network unavailable' }, 500),
      )

    renderWithProviders(<BossBoardPage />)

    expect(await screen.findByText('Boss network unavailable')).toBeInTheDocument()
  })

  it('renders recent history with its outcome', async () => {
    mockBoard([boss()], [victoryEncounter(), defeatedEncounter({ bossName: 'Black Ice' })])

    renderWithProviders(<BossBoardPage />)

    const history = await screen.findByLabelText('Boss history')
    expect(within(history).getByText('The Firewall')).toBeInTheDocument()
    expect(within(history).getByText('Black Ice')).toBeInTheDocument()
    expect(history).toHaveTextContent('+350 XP')
    expect(history).toHaveTextContent('VICTORY')
    expect(history).toHaveTextContent('DEFEATED')
  })

  it('says so when there is no history yet', async () => {
    mockBoard([boss()], [])

    renderWithProviders(<BossBoardPage />)

    expect(await screen.findByText(/no boss encounters yet/i)).toBeInTheDocument()
  })

  it('offers a Resume link instead of Enter when an encounter is live', async () => {
    mockBoard([boss({ availability: 'ACTIVE', canStart: false, activeEncounterId: 'enc-1' })])

    renderWithProviders(<BossBoardPage />)

    const card = await screen.findByRole('article', { name: 'The Firewall' })
    expect(within(card).getByRole('link', { name: 'Resume' })).toHaveAttribute(
      'href',
      '/bosses/encounter',
    )
    expect(within(card).queryByRole('button', { name: 'Enter battle' })).not.toBeInTheDocument()
  })

  it('starts a boss with no request body', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [boss()] }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [] }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeEncounter() }))

    renderWithProviders(<BossBoardPage />)

    const card = await screen.findByRole('article', { name: 'The Firewall' })
    await user.click(within(card).getByRole('button', { name: 'Enter battle' }))

    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('/start'))).toBe(true),
    )

    // The start request carried the boss id and nothing else: no energy cost, no
    // stage, no reward for the client to have decided.
    const startCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/start'))
    const [, options] = startCall as [string, RequestInit]
    expect(options.body).toBeUndefined()
    expect(String(startCall?.[0])).toContain(
      '/api/v1/player/bosses/41111111-0000-4000-8000-000000000001/start',
    )
  })

  it('shows the servers refusal when a boss cannot be entered', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [boss()] }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [] }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Not enough energy: this boss costs 30' }, 400),
      )

    renderWithProviders(<BossBoardPage />)

    const card = await screen.findByRole('article', { name: 'The Firewall' })
    await user.click(within(card).getByRole('button', { name: 'Enter battle' }))

    expect(await screen.findByText('Not enough energy: this boss costs 30')).toBeInTheDocument()
  })
})