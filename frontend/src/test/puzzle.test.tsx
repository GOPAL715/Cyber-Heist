import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EnergyMeter, PuzzlePanel } from '@/components/puzzle'
import { describeInterval, formatCountdown } from '@/components/puzzleConstants'
import { energy, sequencePuzzle, cipherPuzzle } from './missionFixtures'
import type { PuzzleChallenge } from '@/types'

describe('formatCountdown', () => {
  it('renders minutes and seconds', () => {
    expect(formatCountdown(0)).toBe('00:00')
    expect(formatCountdown(9)).toBe('00:09')
    expect(formatCountdown(125)).toBe('02:05')
    expect(formatCountdown(599)).toBe('09:59')
  })

  it('adds an hours field past sixty minutes', () => {
    expect(formatCountdown(3600)).toBe('1:00:00')
    expect(formatCountdown(3725)).toBe('1:02:05')
  })

  it('never renders a negative time', () => {
    expect(formatCountdown(-30)).toBe('00:00')
  })
})

describe('describeInterval', () => {
  it('speaks in the largest sensible unit', () => {
    expect(describeInterval(45)).toBe('45 sec')
    expect(describeInterval(300)).toBe('5 min')
    expect(describeInterval(3600)).toBe('1 h')
    expect(describeInterval(5400)).toBe('1 h 30 min')
  })
})

describe('EnergyMeter', () => {
  it('shows the balance against the cap and the regeneration rate', () => {
    render(<EnergyMeter {...energy} energy={82} />)

    expect(screen.getByText('⚡ 82 / 100')).toBeInTheDocument()
    expect(screen.getByText(/\+1 every 5 min/)).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Energy remaining' })).toHaveAttribute(
      'aria-valuenow',
      '82',
    )
  })

  it('reports a full bar at the cap', () => {
    render(<EnergyMeter {...energy} energy={100} nextRegenerationAt={null} />)

    const bar = screen.getByRole('progressbar', { name: 'Energy remaining' })
    expect(bar).toHaveAttribute('aria-valuenow', '100')
    expect(screen.queryByText(/not enough energy/i)).not.toBeInTheDocument()
  })

  it('warns when the player is empty', () => {
    render(<EnergyMeter {...energy} energy={0} />)

    expect(screen.getByText('⚡ 0 / 100')).toBeInTheDocument()
    expect(screen.getByText(/not enough energy to start a mission/i)).toBeInTheDocument()
  })

  it('says when regeneration is off rather than showing a rate', () => {
    render(<EnergyMeter {...energy} regenerationEnabled={false} nextRegenerationAt={null} />)

    expect(screen.getByText(/regeneration is switched off/i)).toBeInTheDocument()
    expect(screen.queryByText(/next in/i)).not.toBeInTheDocument()
  })

  it('counts down to the server-scheduled next unit', () => {
    render(
      <EnergyMeter
        {...energy}
        nextRegenerationAt={new Date(Date.now() + 125_000).toISOString()}
      />,
    )

    expect(screen.getByText(/next in 0[12]:[0-5]\d/)).toBeInTheDocument()
  })

  it('says "regenerating" once the next unit is due', () => {
    render(<EnergyMeter {...energy} nextRegenerationAt={new Date(Date.now() - 1000).toISOString()} />)

    expect(screen.getByText(/regenerating/i)).toBeInTheDocument()
  })

  it('renders the amount the server configured, not a hardcoded one', () => {
    render(
      <EnergyMeter {...energy} regenerationAmount={3} regenerationIntervalSeconds={60} />,
    )

    expect(screen.getByText(/\+3 every 1 min/)).toBeInTheDocument()
  })
})

describe('PuzzlePanel', () => {
  function renderPanel(puzzle: PuzzleChallenge, overrides: Partial<Parameters<typeof PuzzlePanel>[0]> = {}) {
    const onSubmit = vi.fn()
    render(
      <PuzzlePanel
        puzzle={puzzle}
        missionTitle="Scan the Perimeter"
        missionCategory="RECON"
        isSubmitting={false}
        onSubmit={onSubmit}
        onAbandon={() => {}}
        {...overrides}
      />,
    )
    return onSubmit
  }

  it('renders the mission, the question and every display token', () => {
    renderPanel(sequencePuzzle())

    expect(screen.getByText('Scan the Perimeter')).toBeInTheDocument()
    expect(screen.getByText(/RECON • EASY/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Find the next number' })).toBeInTheDocument()
    for (const term of ['2', '4', '8', '16', '?']) {
      expect(screen.getByText(term)).toBeInTheDocument()
    }
  })

  it('renders multiple choice as radios', async () => {
    const user = userEvent.setup()
    const onSubmit = renderPanel(sequencePuzzle())

    expect(screen.getAllByRole('radio')).toHaveLength(4)
    await user.click(screen.getByRole('radio', { name: '32' }))
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(onSubmit).toHaveBeenCalledWith('32')
  })

  it('renders free text as a labelled input', async () => {
    const user = userEvent.setup()
    const onSubmit = renderPanel(cipherPuzzle())

    const input = screen.getByLabelText(/your answer/i)
    await user.type(input, 'HELLO')
    await user.click(screen.getByRole('button', { name: /^submit$/i }))

    expect(onSubmit).toHaveBeenCalledWith('HELLO')
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('keeps submit disabled until something is entered', async () => {
    const user = userEvent.setup()
    const onSubmit = renderPanel(cipherPuzzle())

    expect(screen.getByRole('button', { name: /^submit$/i })).toBeDisabled()
    await user.type(screen.getByLabelText(/your answer/i), '  ')
    expect(screen.getByRole('button', { name: /^submit$/i })).toBeDisabled()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('disables submission while the request is in flight', () => {
    renderPanel(sequencePuzzle(), { isSubmitting: true })

    expect(screen.getByRole('button', { name: /submitting/i })).toBeDisabled()
    expect(screen.getAllByRole('radio')[0]).toBeDisabled()
  })

  it('shows a countdown and stops offering submission once it runs out', () => {
    renderPanel(sequencePuzzle({ expiresAt: new Date(Date.now() - 1000).toISOString() }))

    expect(screen.getByRole('timer')).toHaveTextContent('00:00')
    expect(
      screen.getByText(/security system detected inactivity/i),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^submit$/i })).toBeDisabled()
  })

  it('offers a way back to the board', async () => {
    const user = userEvent.setup()
    const onAbandon = vi.fn()
    renderPanel(sequencePuzzle(), { onAbandon })

    await user.click(screen.getByRole('button', { name: /return to missions/i }))
    expect(onAbandon).toHaveBeenCalled()
  })

  it('hides a timed code after the reveal window', async () => {
    vi.useFakeTimers()
    try {
      renderPanel(cipherPuzzle({ type: 'TIMED' }))

      expect(screen.getByText('KHOOR')).toBeInTheDocument()
      await vi.advanceTimersByTimeAsync(4100)
      expect(screen.queryByText('KHOOR')).not.toBeInTheDocument()
      expect(screen.getByText('• • • •')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})