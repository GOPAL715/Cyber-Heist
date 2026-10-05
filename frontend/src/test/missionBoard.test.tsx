import { describe, expect, it, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MissionBoard } from '@/components/MissionBoard'
import { AuthProvider } from '@/context/AuthContext'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, tokens } from './helpers'
import {
  completedMission,
  inProgressMission,
  levelUpCompletion,
  lockedMission,
  mission,
  completion,
} from './missionFixtures'
import type { Mission } from '@/types'

function renderBoard() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <MissionBoard onPlayerUpdated={() => {}} />
      </AuthProvider>
    </MemoryRouter>,
  )
}

function signIn() {
  saveSession({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

/**
 * Mocks the session restore and the initial mission list. The board issues
 * exactly these two requests on mount.
 */
function mockInitial(missions: Mission[]) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(jsonResponse({ success: true, data: missions }))
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('MissionBoard', () => {
  it('lists missions returned by the server', async () => {
    signIn()
    mockInitial([mission()])

    renderBoard()

    expect(await screen.findByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText('Start mission')).toBeInTheDocument()
  })

  it('shows a locked mission with the level requirement instead of a button', async () => {
    signIn()
    mockInitial([lockedMission])

    renderBoard()

    expect(await screen.findByText('Recover the Data')).toBeInTheDocument()
    expect(screen.getByText('Requires level 7')).toBeInTheDocument()
    expect(screen.getByText('Locked')).toBeInTheDocument()
    // A locked mission offers no action at all.
    expect(screen.queryByRole('button', { name: /start mission/i })).not.toBeInTheDocument()
  })

  it('offers the complete action for an in-progress mission', async () => {
    signIn()
    mockInitial([inProgressMission])

    renderBoard()

    expect(await screen.findByText('IN PROGRESS')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /complete mission/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^start mission$/i })).not.toBeInTheDocument()
  })

  it('shows a completed mission as done, with no action', async () => {
    signIn()
    mockInitial([completedMission])

    renderBoard()

    expect(await screen.findByText('COMPLETED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /start mission/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /complete mission/i })).not.toBeInTheDocument()
  })
  it('filters the board by category', async () => {
    signIn()
    mockInitial([mission(), lockedMission])

    renderBoard()

    expect(await screen.findByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText('Recover the Data')).toBeInTheDocument()
    expect(screen.getByText('2 missions')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'RECON' }))

    // The intelligence mission is filtered out without another request.
    expect(screen.queryByText('Recover the Data')).not.toBeInTheDocument()
    expect(screen.getByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText('1 mission')).toBeInTheDocument()
  })

  it('filters the board by difficulty', async () => {
    signIn()
    mockInitial([mission(), lockedMission])

    renderBoard()

    await screen.findByText('Scan the Perimeter')
    await userEvent.click(screen.getByRole('button', { name: 'ELITE' }))

    expect(screen.queryByText('Scan the Perimeter')).not.toBeInTheDocument()
    expect(screen.getByText('Recover the Data')).toBeInTheDocument()
  })

  it('shows an empty state when no mission matches the filters', async () => {
    signIn()
    mockInitial([mission()])

    renderBoard()

    await screen.findByText('Scan the Perimeter')
    await userEvent.click(screen.getByRole('button', { name: 'NETWORK' }))

    expect(screen.getByText(/no missions match/i)).toBeInTheDocument()
  })

  it('starts a mission and refreshes the list', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: { ...mission(), status: 'IN_PROGRESS' } }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: [{ ...mission(), status: 'IN_PROGRESS' }] }),
      )

    await user.click(screen.getByRole('button', { name: /start mission/i }))

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(4))
    const startCall = fetchSpy.mock.calls.find(([url]) => String(url).endsWith('/start'))
    expect(startCall).toBeDefined()
    // Start is a POST with no body: the client never sends rewards.
    expect(startCall?.[1]?.method).toBe('POST')
    expect(startCall?.[1]?.body).toBeUndefined()

    expect(await screen.findByText('IN PROGRESS')).toBeInTheDocument()
  })
  it('completes a mission and shows the reward summary', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([inProgressMission])
    renderBoard()

    await screen.findByText('IN PROGRESS')
    fetchSpy
      .mockResolvedValueOnce(jsonResponse({ success: true, data: completion }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /complete mission/i }))

    expect(await screen.findByText('MISSION COMPLETE')).toBeInTheDocument()
    // Scope to the dialog: the card behind it also shows the same figures.
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('+50 XP')).toBeInTheDocument()
    expect(within(dialog).getByText('+25')).toBeInTheDocument()

    const completeCall = fetchSpy.mock.calls.find(([url]) =>
      String(url).endsWith('/complete'),
    )
    expect(completeCall?.[1]?.method).toBe('POST')
    expect(completeCall?.[1]?.body).toBeUndefined()
  })

  it('announces a level up when the reward crosses a threshold', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([inProgressMission])
    renderBoard()

    await screen.findByText('IN PROGRESS')
    fetchSpy
      .mockResolvedValueOnce(jsonResponse({ success: true, data: levelUpCompletion }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /complete mission/i }))

    expect(await screen.findByText('Level up!')).toBeInTheDocument()
    expect(screen.getByText('Level 1 → Level 2')).toBeInTheDocument()
  })

  it('dismisses the reward summary when continuing', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([inProgressMission])
    renderBoard()

    await screen.findByText('IN PROGRESS')
    fetchSpy
      .mockResolvedValueOnce(jsonResponse({ success: true, data: completion }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /complete mission/i }))
    await user.click(await screen.findByRole('button', { name: /continue/i }))

    await waitFor(() =>
      expect(screen.queryByText('MISSION COMPLETE')).not.toBeInTheDocument(),
    )
  })

  it('reports an already claimed mission without extra rewards', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([inProgressMission])
    renderBoard()

    await screen.findByText('IN PROGRESS')
    fetchSpy
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: {
            ...completion,
            rewards: { experience: 0, coins: 0 },
            alreadyCompleted: true,
          },
        }),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /complete mission/i }))

    expect(await screen.findByText('ALREADY CLAIMED')).toBeInTheDocument()
    expect(screen.getByText(/no additional rewards/i)).toBeInTheDocument()
  })

  it('surfaces a server error when a mission cannot be started', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(
      jsonResponse(
        { success: false, message: 'Not enough energy: this mission costs 10' },
        400,
      ),
    )

    await user.click(screen.getByRole('button', { name: /start mission/i }))

    expect(await screen.findByText(/not enough energy/i)).toBeInTheDocument()
  })

  it('shows an error when the mission list cannot be loaded', async () => {
    signIn()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Unable to load missions' }, 500),
      )

    renderBoard()

    expect(await screen.findByText('Unable to load missions')).toBeInTheDocument()
  })
})