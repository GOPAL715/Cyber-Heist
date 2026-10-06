import { describe, expect, it, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MissionBoard } from '@/components/MissionBoard'
import { AuthProvider } from '@/context/AuthContext'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, tokens } from './helpers'
import {
  cipherPuzzle,
  completedMission,
  energy,
  expiredSubmission,
  inProgressMission,
  incorrectSubmission,
  levelUpSubmission,
  lockedMission,
  mission,
  missionStart,
  replaySubmission,
  sequencePuzzle,
  solvedSubmission,
} from './missionFixtures'
import type { Mission, PuzzleChallenge, PuzzleSubmission } from '@/types'

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

/** A successful start response: the mission plus its generated puzzle. */
function startResponse(source: Mission = mission(), puzzle: PuzzleChallenge = sequencePuzzle()) {
  return jsonResponse({ success: true, data: missionStart(source, puzzle) })
}

function submitResponse(submission: PuzzleSubmission) {
  return jsonResponse({ success: true, data: submission })
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
    expect(screen.getByRole('button', { name: /start mission/i })).toBeInTheDocument()
  })

  it('shows which puzzle family each mission serves', async () => {
    signIn()
    mockInitial([
      mission(),
      mission({
        id: '44444444-4444-4444-8444-444444444444',
        title: 'Recover the Data',
        category: 'INTELLIGENCE',
        puzzleType: 'LOGIC',
      }),
    ])

    renderBoard()

    await screen.findByText('Scan the Perimeter')
    expect(screen.getByText(/Puzzle · Sequence/)).toBeInTheDocument()
    expect(screen.getByText(/Puzzle · Logic/)).toBeInTheDocument()
  })

  it('shows a locked mission with the level requirement instead of a button', async () => {
    signIn()
    mockInitial([lockedMission])

    renderBoard()

    expect(await screen.findByText('Recover the Data')).toBeInTheDocument()
    expect(screen.getByText('Requires level 7')).toBeInTheDocument()
    expect(screen.getByText('Locked')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /start mission/i })).not.toBeInTheDocument()
  })

  it('shows a completed mission as done, with no action', async () => {
    signIn()
    mockInitial([completedMission])

    renderBoard()

    expect(await screen.findByText('COMPLETED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /start mission/i })).not.toBeInTheDocument()
  })

  it('offers a fresh puzzle for an in-progress mission, and says it costs energy', async () => {
    signIn()
    mockInitial([inProgressMission])

    renderBoard()

    expect(await screen.findByText('IN PROGRESS')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /restart for a new puzzle/i }),
    ).toBeInTheDocument()
    expect(screen.getByText(/new puzzle costs energy/i)).toBeInTheDocument()
    // The Phase 2 shortcut that skipped the challenge is gone.
    expect(screen.queryByRole('button', { name: /complete mission/i })).not.toBeInTheDocument()
  })

  it('filters the board by category', async () => {
    signIn()
    mockInitial([mission(), lockedMission])

    renderBoard()

    expect(await screen.findByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText('2 missions')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'RECON' }))

    expect(screen.queryByText('Recover the Data')).not.toBeInTheDocument()
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

  it('shows a loading state before the board arrives', () => {
    signIn()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ success: true, data: [] }))

    renderBoard()

    expect(screen.getByRole('status')).toHaveTextContent(/establishing uplink/i)
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
})

describe('MissionBoard puzzle loop', () => {
  it('starts a mission and renders the returned puzzle', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    const puzzle = sequencePuzzle()
    fetchSpy.mockResolvedValueOnce(startResponse(mission(), puzzle))

    await user.click(screen.getByRole('button', { name: /start mission/i }))

    expect(await screen.findByText(puzzle.question)).toBeInTheDocument()
    expect(screen.getByText('Find the next number')).toBeInTheDocument()

    for (const term of puzzle.sequence) {
      expect(screen.getByText(term)).toBeInTheDocument()
    }
    for (const option of puzzle.options) {
      expect(screen.getByRole('radio', { name: option })).toBeInTheDocument()
    }
  })

  it('renders a free-text challenge as an input rather than options', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission({ puzzleType: 'CIPHER' })])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(
      startResponse(mission({ puzzleType: 'CIPHER' }), cipherPuzzle()),
    )

    await user.click(screen.getByRole('button', { name: /start mission/i }))

    expect(await screen.findByLabelText(/your answer/i)).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('sends no body on start and fetches no answer', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(startResponse())
    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await screen.findByRole('timer')

    // Nothing fetches a solution; the client only ever asks the server to judge.
    const urls = fetchSpy.mock.calls.map(([url]) => String(url))
    expect(urls.filter((url) => url.includes('/puzzle'))).toHaveLength(0)

    const startCall = fetchSpy.mock.calls.find(([url]) => String(url).endsWith('/start'))
    expect(startCall?.[1]?.method).toBe('POST')
    expect(startCall?.[1]?.body).toBeUndefined()
  })

  it('shows a countdown derived from the server expiry', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(
      startResponse(
        mission(),
        sequencePuzzle({ expiresAt: new Date(Date.now() + 125_000).toISOString() }),
      ),
    )
    await user.click(screen.getByRole('button', { name: /start mission/i }))

    const timer = await screen.findByRole('timer')
    // 125 seconds renders as 02:05, allowing for a moment of drift.
    expect(timer.textContent).toMatch(/^0[12]:[0-5]\d$/)
  })

  it('submits the chosen answer and shows the reward result', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    const puzzle = sequencePuzzle()
    fetchSpy
      .mockResolvedValueOnce(startResponse(mission(), puzzle))
      .mockResolvedValueOnce(submitResponse(solvedSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await screen.findByText(puzzle.question)

    await user.click(screen.getByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('MISSION COMPLETE!')).toBeInTheDocument()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('+50 XP')).toBeInTheDocument()
    expect(within(dialog).getByText('+25')).toBeInTheDocument()

    // The submission carried the puzzle id and the answer, and nothing else.
    const submitCall = fetchSpy.mock.calls.find(([url]) =>
      String(url).endsWith('/puzzle/submit'),
    )
    expect(submitCall?.[1]?.method).toBe('POST')
    expect(JSON.parse(String(submitCall?.[1]?.body))).toEqual({
      puzzleId: puzzle.puzzleId,
      answer: '32',
    })
  })

  it('submits a typed answer for a free-text puzzle', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission({ puzzleType: 'CIPHER' })])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse(mission({ puzzleType: 'CIPHER' }), cipherPuzzle()))
      .mockResolvedValueOnce(submitResponse(solvedSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.type(await screen.findByLabelText(/your answer/i), 'HELLO')
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('MISSION COMPLETE!')).toBeInTheDocument()

    const submitCall = fetchSpy.mock.calls.find(([url]) =>
      String(url).endsWith('/puzzle/submit'),
    )
    expect(JSON.parse(String(submitCall?.[1]?.body))).toEqual({
      puzzleId: cipherPuzzle().puzzleId,
      answer: 'HELLO',
    })
  })

  it('announces a level up when the reward crosses a threshold', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse())
      .mockResolvedValueOnce(submitResponse(levelUpSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('Level up!')).toBeInTheDocument()
    expect(screen.getByText('Level 1 → Level 2')).toBeInTheDocument()
  })

  it('shows a failure result with no reward and a retry action', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse())
      .mockResolvedValueOnce(submitResponse(incorrectSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [inProgressMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '24' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('ACCESS DENIED')).toBeInTheDocument()
    expect(screen.getByText('Incorrect answer. No rewards earned.')).toBeInTheDocument()
    expect(screen.getByText('No rewards earned.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  it('shows an expired result that offers no retry', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse())
      .mockResolvedValueOnce(submitResponse(expiredSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [inProgressMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '24' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('CONNECTION TIMEOUT')).toBeInTheDocument()
    expect(screen.getByText(/security system detected inactivity/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /return to missions/i })).toBeInTheDocument()
  })

  it('reports a repeated submission as already claimed, with no reward', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse())
      .mockResolvedValueOnce(submitResponse(replaySubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('ALREADY CLAIMED')).toBeInTheDocument()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).queryByText('+50 XP')).not.toBeInTheDocument()
    expect(screen.queryByText('Level up!')).not.toBeInTheDocument()
  })

  it('dismisses the reward summary when continuing', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy
      .mockResolvedValueOnce(startResponse())
      .mockResolvedValueOnce(submitResponse(solvedSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [completedMission] }))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))
    await user.click(await screen.findByRole('button', { name: /continue/i }))

    await waitFor(() =>
      expect(screen.queryByText('MISSION COMPLETE!')).not.toBeInTheDocument(),
    )
  })

  it('retries a failed mission by starting it again', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    const first = sequencePuzzle()
    const second = sequencePuzzle({ puzzleId: '99999999-9999-4999-8999-999999999999' })

    fetchSpy
      .mockResolvedValueOnce(startResponse(mission(), first))
      .mockResolvedValueOnce(submitResponse(incorrectSubmission))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: [inProgressMission] }))
      .mockResolvedValueOnce(startResponse(mission(), second))

    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await user.click(await screen.findByRole('radio', { name: '24' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))
    await user.click(await screen.findByRole('button', { name: /try again/i }))

    // A brand new challenge opens.
    expect(await screen.findByRole('timer')).toBeInTheDocument()
    const startCalls = fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/start'))
    expect(startCalls).toHaveLength(2)
  })

  it('surfaces a server error when a submission is rejected', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(startResponse())
    await user.click(screen.getByRole('button', { name: /start mission/i }))

    fetchSpy.mockResolvedValueOnce(
      jsonResponse({ success: false, message: 'Puzzle not found for this mission' }, 404),
    )
    await user.click(await screen.findByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(await screen.findByText('Puzzle not found for this mission')).toBeInTheDocument()
  })

  it('will not submit an empty answer', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(startResponse())
    await user.click(screen.getByRole('button', { name: /start mission/i }))

    const submitButton = await screen.findByRole('button', { name: /^submit$/i })
    expect(submitButton).toBeDisabled()
    expect(fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/submit'))).toHaveLength(0)
  })

  it('returns to the board on request', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(startResponse())
    await user.click(screen.getByRole('button', { name: /start mission/i }))
    await screen.findByRole('timer')

    await user.click(screen.getByRole('button', { name: /return to missions/i }))

    expect(await screen.findByRole('button', { name: /start mission/i })).toBeInTheDocument()
  })

  it('carries the server energy figures through the start response', async () => {
    const user = userEvent.setup()
    signIn()

    const fetchSpy = mockInitial([mission()])
    renderBoard()

    await screen.findByText('Scan the Perimeter')
    fetchSpy.mockResolvedValueOnce(startResponse())
    await user.click(screen.getByRole('button', { name: /start mission/i }))

    await screen.findByRole('timer')
    expect(energy.energy).toBe(90)
    expect(energy.maximum).toBe(100)
    expect(energy.regenerationIntervalSeconds).toBe(300)
  })
})