import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BossEncounterPage } from '@/pages/BossEncounterPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import { activeEncounter, defeatedEncounter, victoryEncounter } from './bossFixtures'
import type { BossEncounter } from '@/types'

/**
 * A boss encounter in progress.
 *
 * <p>The integrity bar, the stage number and the outcome all come from the
 * server's response after each submission; none of them is advanced locally,
 * which is what these tests hold the page to.
 */

function signIn() {
  saveSession({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

/** Session restore, then the live encounter. */
function mockEncounter(encounter: BossEncounter | null = activeEncounter()) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(
      encounter
        ? jsonResponse({ success: true, data: encounter })
        : jsonResponse({ success: false, message: 'No active boss encounter' }, 404),
    )
}

/**
 * Answers the current phase through the reused puzzle panel.
 *
 * <p>Options are radios inside a form, so an answer has to be chosen before the
 * submit button enables. Which option is picked does not matter: these tests
 * assert the server's response, not the player's knowledge.
 */
async function answerCurrentPhase(user: ReturnType<typeof userEvent.setup>) {
  const options = await screen.findAllByRole('radio')
  await user.click(options[0])
  await user.click(screen.getByRole('button', { name: 'Submit' }))
}

beforeEach(() => {
  vi.restoreAllMocks()
  signIn()
})

describe('BossEncounterPage', () => {
  it('renders the boss, its integrity and the current stage', async () => {
    mockEncounter()

    renderWithProviders(<BossEncounterPage />)

    expect(await screen.findByText('The Firewall')).toBeInTheDocument()
    expect(screen.getByText('Stage 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Boss integrity' })).toHaveAttribute(
      'aria-valuenow',
      '100',
    )
    expect(screen.getByText('Scan the Perimeter')).toBeInTheDocument()
  })

  it('shows a loading state before the encounter arrives', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockReturnValueOnce(new Promise(() => {}))

    renderWithProviders(<BossEncounterPage />)

    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('offers the board when there is no encounter', async () => {
    mockEncounter(null)

    renderWithProviders(<BossEncounterPage />)

    expect(await screen.findByText(/not in a boss encounter/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /return to boss board/i })).toBeInTheDocument()
  })

  it('shows the stage transition and reduced integrity after a correct answer', async () => {
    const user = userEvent.setup()
    const stageTwo = activeEncounter({
      currentStage: 2,
      bossIntegrity: 80,
      bossIntegrityPercent: 80,
      stageName: 'Break the Cipher',
      outcomeMessage: 'STAGE 1 CLEARED. Integrity 80. NEXT PHASE.',
    })
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeEncounter() }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: stageTwo }))

    renderWithProviders(<BossEncounterPage />)

    await answerCurrentPhase(user)

    await waitFor(() =>
      expect(
        screen.getByText('STAGE 1 CLEARED. Integrity 80. NEXT PHASE.'),
      ).toBeInTheDocument(),
    )
    expect(screen.getByRole('progressbar', { name: 'Boss integrity' })).toHaveAttribute(
      'aria-valuenow',
      '80',
    )
    expect(screen.getByText('Stage 2 of 3')).toBeInTheDocument()

    // The submission carried a puzzle id and an answer, and nothing else: no
    // damage, integrity, stage or reward for the client to have decided.
    const submitCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/stage/submit'))
    expect(submitCall).toBeDefined();
    const [, options] = submitCall as [string, RequestInit]
    const body = JSON.parse(String(options.body))
    expect(Object.keys(body).sort()).toEqual(['answer', 'puzzleId'])
    expect(body.puzzleId).toBe('aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa')
  })

  it('shows the victory panel with the server-reported rewards', async () => {
    mockEncounter(victoryEncounter())

    renderWithProviders(<BossEncounterPage />)

    const outcome = await screen.findByLabelText('Boss outcome')
    expect(outcome).toHaveTextContent('BOSS DEFEATED')
    expect(outcome).toHaveTextContent('The Firewall')
    expect(outcome).toHaveTextContent('+350 XP')
    expect(outcome).toHaveTextContent('+220 coins')
    expect(outcome).toHaveTextContent('LEVEL UP! +1 skill point')
    expect(outcome).toHaveTextContent(/next attempt in/i)
  })

  it('shows the failure panel with no rewards and a cooldown', async () => {
    mockEncounter(defeatedEncounter())

    renderWithProviders(<BossEncounterPage />)

    const outcome = await screen.findByLabelText('Boss outcome')
    expect(outcome).toHaveTextContent('ACCESS DENIED')
    expect(outcome).toHaveTextContent('No rewards earned')
    expect(outcome).toHaveTextContent(/next attempt in/i)
    // No reward figures are shown at all on a loss.
    expect(outcome).not.toHaveTextContent(/\+\d+ XP/)
  })

  it('re-reads the encounter after a rejected submission', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: activeEncounter() }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Puzzle not found for this encounter' }, 404),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: defeatedEncounter() }))

    renderWithProviders(<BossEncounterPage />)

    await answerCurrentPhase(user)

    expect(await screen.findByText('Puzzle not found for this encounter')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Boss outcome')).toBeInTheDocument())
  })

  it('shows a terminal encounter with no puzzle to answer', async () => {
    mockEncounter(victoryEncounter())

    renderWithProviders(<BossEncounterPage />)

    await screen.findByLabelText('Boss outcome')
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
  })
})