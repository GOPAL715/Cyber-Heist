import type { PuzzleOutcome, PuzzleType } from '@/types'

/**
 * Puzzle screen display constants.
 *
 * <p>Kept out of `puzzle.tsx` so that file exports only components, which lets
 * React Fast Refresh work there.
 */

/** Short labels for the puzzle families, shown on mission cards. */
export const PUZZLE_TYPE_LABELS: Record<PuzzleType, string> = {
  CIPHER: 'Cipher',
  SEQUENCE: 'Sequence',
  PATTERN: 'Pattern',
  LOGIC: 'Logic',
  TIMED: 'Timed',
}

/**
 * Headlines for each outcome.
 *
 * <p>Matches the server's `PuzzleOutcome.headline()` so the two can never
 * disagree about what happened; the server's copy still wins if a response
 * arrives without one.
 */
export const OUTCOME_HEADLINES: Record<PuzzleOutcome, string> = {
  SOLVED: 'MISSION COMPLETE!',
  INCORRECT: 'ACCESS DENIED',
  EXPIRED: 'CONNECTION TIMEOUT',
}

/** How long the timed code stays on screen before the client hides it. */
export const CODE_REVEAL_MILLISECONDS = 4000

/** Formats seconds as `MM:SS`, or `H:MM:SS` past an hour. */
export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const seconds = safe % 60

  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

/** An interval as words: `45 sec`, `5 min`, `1 h 30 min`. */
export function describeInterval(seconds: number): string {
  if (seconds < 60) return `${seconds} sec`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return remainder === 0 ? `${hours} h` : `${hours} h ${remainder} min`
}