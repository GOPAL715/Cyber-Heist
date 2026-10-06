import type { BossAvailability, BossDifficulty, EncounterStatus } from '@/types'

/**
 * Boss display constants.
 *
 * <p>Kept out of `bosses.tsx` so that file exports only components, which lets
 * React Fast Refresh work there.
 *
 * <p>Deliberately no numbers. Headlines, colours and label wording are the only
 * things this module decides; every cost, damage figure, integrity value and
 * reward comes from the server. Hardcoding a price here would be the exact
 * mistake the Phase 6 security model exists to prevent, because the board would
 * then disagree with what the backend actually charges.
 */

/** Tailwind classes for a difficulty badge. */
export function difficultyClasses(difficulty: BossDifficulty): string {
  const classes: Record<BossDifficulty, string> = {
    EASY: 'border-lime-400/40 bg-lime-400/10 text-lime-300',
    MEDIUM: 'border-neon/40 bg-neon/10 text-neon',
    HARD: 'border-magenta/40 bg-magenta/10 text-magenta',
    ELITE: 'border-amber-400/40 bg-amber-400/10 text-amber-300',
  }
  return classes[difficulty]
}

/** A short label for what the player may currently do with a boss. */
export function availabilityLabel(availability: BossAvailability): string {
  const labels: Record<BossAvailability, string> = {
    AVAILABLE: 'Open',
    LOCKED: 'Locked',
    COOLDOWN: 'On cooldown',
    ACTIVE: 'In progress',
  }
  return labels[availability]
}

/** Headline for a finished encounter. */
export function outcomeHeadline(status: EncounterStatus): string | null {
  switch (status) {
    case 'VICTORY':
      return 'BOSS DEFEATED'
    case 'DEFEATED':
      return 'ACCESS DENIED'
    case 'EXPIRED':
      return 'CONNECTION LOST'
    case 'ACTIVE':
      return null
  }
}

/**
 * Cooldown as a countdown, e.g. "29:42" or "11h 12m".
 *
 * <p>Presentation only: the server decides when a boss becomes available again,
 * so a client clock can never shorten the wait.
 */
export function formatCooldown(until: string, now: number = Date.now()): string {
  const remaining = Math.max(0, Math.floor((new Date(until).getTime() - now) / 1000))
  if (remaining <= 0) return 'now'

  const hours = Math.floor(remaining / 3600)
  const minutes = Math.floor((remaining % 3600) / 60)
  const seconds = remaining % 60

  if (hours > 0) return `${hours}h ${minutes}m`
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

/** A coarse "when was this" label for the history list. */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

/**
 * The integrity bar's colour, shifting as the boss is worn down.
 *
 * <p>The width itself comes from the server's percentage; this only decides
 * what colour to paint it.
 */
export function integrityClasses(percent: number): string {
  if (percent > 60) return 'from-rose-500 to-rose-400'
  if (percent > 30) return 'from-amber-500 to-amber-400'
  return 'from-lime-500 to-lime-400'
}