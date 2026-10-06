import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, TextField } from '@/components/ui'
import { CODE_REVEAL_MILLISECONDS, describeInterval, formatCountdown } from '@/components/puzzleConstants'
import type { PuzzleChallenge } from '@/types'

interface PuzzlePanelProps {
  puzzle: PuzzleChallenge
  /** Mission title and category, shown in the header. */
  missionTitle: string
  missionCategory: string
  isSubmitting: boolean
  onSubmit: (answer: string) => void
  onAbandon: () => void
}

/**
 * The puzzle screen.
 *
 * <p>Holds no game rules. It renders whatever the server sent, collects an
 * answer and reports it; it never decides whether the answer was right, never
 * computes a score and never awards anything. That is the whole point - the
 * server is the only judge, so a tampered client gains nothing.
 *
 * <p>The countdown is presentation only. It is derived from the server's
 * `expiresAt`, and when it reaches zero the panel disables submission and
 * offers a return - but whether a late answer is accepted is still the server's
 * call, made against its own clock.
 */
export function PuzzlePanel({
  puzzle,
  missionTitle,
  missionCategory,
  isSubmitting,
  onSubmit,
  onAbandon,
}: PuzzlePanelProps) {
  const [answer, setAnswer] = useState('')
  const [secondsLeft, setSecondsLeft] = useState(() => secondsRemaining(puzzle.expiresAt))
  const [codeHidden, setCodeHidden] = useState(false)

  // Keyed on the puzzle so a retry starts a fresh countdown and a fresh answer
  // box rather than inheriting the previous attempt's state.
  useEffect(() => {
    setAnswer('')
    setCodeHidden(false)
    setSecondsLeft(secondsRemaining(puzzle.expiresAt))
  }, [puzzle.puzzleId, puzzle.expiresAt])

  useEffect(() => {
    const tick = window.setInterval(() => {
      setSecondsLeft(secondsRemaining(puzzle.expiresAt))
    }, 1000)
    return () => window.clearInterval(tick)
  }, [puzzle.expiresAt])

  // The timed family shows its code briefly and then goes dark. The server's
  // window keeps running either way, so hiding it changes nothing about what is
  // accepted - it is the challenge.
  useEffect(() => {
    if (puzzle.type !== 'TIMED') return
    const hide = window.setTimeout(() => setCodeHidden(true), CODE_REVEAL_MILLISECONDS)
    return () => window.clearTimeout(hide)
  }, [puzzle.type, puzzle.puzzleId])

  const isTimedOut = secondsLeft <= 0
  const isFreeText = puzzle.options.length === 0
  const canSubmit = !isSubmitting && !isTimedOut && answer.trim().length > 0

  const displayedSequence = useMemo(() => {
    if (puzzle.type === 'TIMED' && codeHidden) {
      return ['• • • •']
    }
    return puzzle.sequence
  }, [puzzle.type, puzzle.sequence, codeHidden])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    onSubmit(answer.trim())
  }

  return (
    <section className="panel p-6" aria-label="Puzzle">
      <header className="mb-4 flex flex-wrap items-center justify-between gap-2 border-b border-cyan-500/20 pb-3">
        <div>
          <h2 className="text-xs uppercase tracking-[0.3em] text-slate-500">{missionTitle}</h2>
          <p className="text-[0.65rem] uppercase tracking-widest text-slate-500">
            {missionCategory} • {puzzle.difficulty}
          </p>
        </div>

        <span
          className="font-mono text-xl tabular-nums text-neon"
          role="timer"
          aria-live="off"
          aria-label="Time remaining"
        >
          {formatCountdown(secondsLeft)}
        </span>
      </header>

      <h3 className="text-sm font-semibold uppercase tracking-widest text-slate-300">
        {puzzle.title}
      </h3>
      <p className="mt-1 text-sm text-slate-400">{puzzle.question}</p>

      <ul className="my-5 space-y-2" aria-label="Challenge">
        {displayedSequence.map((token, index) => (
          <li
            key={`${puzzle.puzzleId}-${index}`}
            className="rounded-lg border border-slate-700/70 bg-night/60 px-4 py-3 text-center font-mono text-xl tracking-[0.3em] text-slate-100"
          >
            {token}
          </li>
        ))}
      </ul>

      {isTimedOut && (
        <Alert>The security system detected inactivity and closed the connection.</Alert>
      )}

      <form onSubmit={handleSubmit} className="mt-4 space-y-4">
        {isFreeText ? (
          <TextField
            label="Your answer"
            value={answer}
            onChange={(event) => setAnswer(event.target.value)}
            placeholder="Type your answer"
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            maxLength={200}
            disabled={isSubmitting || isTimedOut}
          />
        ) : (
          <fieldset className="space-y-2" disabled={isSubmitting || isTimedOut}>
            <legend className="field-label">Choose one</legend>
            {puzzle.options.map((option) => (
              <label
                key={option}
                className="flex cursor-pointer items-center gap-3 rounded-lg border border-slate-700 bg-night px-4 py-2.5 text-sm text-slate-200 transition-colors hover:border-neon/50 has-[:checked]:border-neon has-[:checked]:bg-neon/10"
              >
                <input
                  type="radio"
                  name="puzzle-answer"
                  value={option}
                  checked={answer === option}
                  onChange={() => setAnswer(option)}
                  className="accent-neon"
                />
                {option}
              </label>
            ))}
          </fieldset>
        )}

        <div className="flex flex-col gap-2 sm:flex-row">
          <Button type="submit" fullWidth isLoading={isSubmitting} disabled={!canSubmit}>
            {isSubmitting ? 'Submitting' : 'Submit'}
          </Button>
          <Button type="button" variant="secondary" fullWidth onClick={onAbandon}>
            Return to missions
          </Button>
        </div>
      </form>
    </section>
  )
}

/** Whole seconds left before {@code expiresAt}, measured against the browser clock. */
function secondsRemaining(expiresAt: string): number {
  const expiry = Date.parse(expiresAt)
  if (Number.isNaN(expiry)) return 0
  return Math.ceil((expiry - Date.now()) / 1000)
}

interface PuzzleResultOverlayProps {
  outcome: 'SOLVED' | 'INCORRECT' | 'EXPIRED'
  headline: string
  missionTitle: string
  message: string
  rewards: { experience: number; coins: number }
  leveledUp: boolean
  levelBefore: number
  levelAfter: number
  canRetry: boolean
  onPrimaryAction: () => void
}

/**
 * The result screen after a submission.
 *
 * <p>Every figure shown comes from the server response; the client adds
 * nothing. A wrong or expired attempt deliberately does not reveal the answer -
 * showing it would let a player learn the solution by failing, which is the
 * opposite of a challenge.
 */
export function PuzzleResultOverlay({
  outcome,
  headline,
  missionTitle,
  message,
  rewards,
  leveledUp,
  levelBefore,
  levelAfter,
  canRetry,
  onPrimaryAction,
}: PuzzleResultOverlayProps) {
  const isSolved = outcome === 'SOLVED'

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="puzzle-result-title"
    >
      <div className="panel w-full max-w-sm p-6 text-center shadow-neon">
        <h2
          id="puzzle-result-title"
          className={`text-lg font-bold tracking-[0.2em] ${
            isSolved ? 'neon-text' : 'text-magenta'
          }`}
        >
          {headline}
        </h2>
        <p className="mt-1 text-sm text-slate-400">{missionTitle}</p>

        {isSolved ? (
          <div className="mt-6 space-y-3">
            <div className="flex items-center justify-between text-sm">
              <span className="uppercase tracking-widest text-slate-400">Experience</span>
              <span className="font-bold text-neon">+{rewards.experience} XP</span>
            </div>
            <div className="flex items-center justify-between text-sm">
              <span className="uppercase tracking-widest text-slate-400">Coins</span>
              <span className="font-bold text-amber">+{rewards.coins}</span>
            </div>
          </div>
        ) : (
          <div className="mt-6 space-y-2">
            <p className="text-sm text-slate-300">{message}</p>
            <p className="text-sm font-semibold text-amber">No rewards earned.</p>
          </div>
        )}

        {isSolved && leveledUp && (
          <div className="mt-6 rounded-lg border border-magenta/40 bg-magenta/10 p-4">
            <p className="text-xs font-bold uppercase tracking-[0.3em] text-magenta">Level up!</p>
            <p className="mt-1 text-lg font-bold text-slate-100">
              Level {levelBefore} → Level {levelAfter}
            </p>
          </div>
        )}

        {isSolved && (
          <p className="mt-4 text-xs text-slate-500">
            Energy was spent when the mission started; it regenerates over time.
          </p>
        )}

        <div className="mt-6">
          <Button fullWidth onClick={onPrimaryAction}>
            {isSolved ? 'Continue' : canRetry ? 'Try again' : 'Return to missions'}
          </Button>
        </div>
      </div>
    </div>
  )
}

interface EnergyMeterProps {
  energy: number
  maximum: number
  regenerationEnabled: boolean
  regenerationAmount: number
  regenerationIntervalSeconds: number
  nextRegenerationAt: string | null
}

/**
 * The energy readout.
 *
 * <p>Displays what the server last reported and nothing it worked out itself.
 * The countdown to `nextRegenerationAt` is a convenience for the player, but
 * the server recomputes the balance whenever state is read or a mission is
 * started, so a stale client figure can never overdraw an account - the start
 * call would simply be refused with the real number.
 */
export function EnergyMeter({
  energy,
  maximum,
  regenerationEnabled,
  regenerationAmount,
  regenerationIntervalSeconds,
  nextRegenerationAt,
}: EnergyMeterProps) {
  const percentage = maximum <= 0 ? 0 : Math.min(100, Math.round((energy / maximum) * 100))
  const isLow = energy <= 0

  return (
    <div className="panel p-4" aria-label="Energy">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs uppercase tracking-widest text-slate-400">Energy</span>
        <span className="text-lg font-bold tabular-nums text-neon">
          ⚡ {energy} / {maximum}
        </span>
      </div>

      <div
        role="progressbar"
        aria-valuenow={energy}
        aria-valuemin={0}
        aria-valuemax={maximum}
        aria-label="Energy remaining"
        className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-800"
      >
        <div
          className={`h-full transition-[width] duration-500 ${
            isLow ? 'bg-rose-400' : 'bg-gradient-to-r from-neon to-magenta'
          }`}
          style={{ width: `${percentage}%` }}
        />
      </div>

      {regenerationEnabled ? (
        <p className="mt-2 text-[0.65rem] text-slate-500">
          +{regenerationAmount} every {describeInterval(regenerationIntervalSeconds)}
          {nextRegenerationAt ? (
            <>
              {' · '}
              <NextRegeneration at={nextRegenerationAt} />
            </>
          ) : null}
        </p>
      ) : (
        <p className="mt-2 text-[0.65rem] text-slate-500">Regeneration is switched off.</p>
      )}

      {isLow && <p className="mt-1 text-[0.65rem] text-amber">Not enough energy to start a mission.</p>}
    </div>
  )
}

/** The wait until the next unit, or nothing once it is due. */
function NextRegeneration({ at }: { at: string }) {
  const target = Date.parse(at)
  const [remaining, setRemaining] = useState(() => Math.ceil((target - Date.now()) / 1000))

  useEffect(() => {
    const tick = window.setInterval(() => {
      setRemaining(Math.ceil((target - Date.now()) / 1000))
    }, 1000)
    return () => window.clearInterval(tick)
  }, [target])

  if (remaining <= 0) return <span>regenerating</span>
  return <span>next in {formatCountdown(remaining)}</span>
}