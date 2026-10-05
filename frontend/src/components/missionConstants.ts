import type { MissionCategory, MissionDifficulty } from '@/types'

/**
 * Mission board display constants.
 *
 * <p>Kept out of `missions.tsx` so that file exports only components, which
 * lets React Fast Refresh work there.
 */

/** The five mission categories, in board display order. */
export const MISSION_CATEGORIES: readonly MissionCategory[] = [
  'RECON',
  'EXPLOIT',
  'CRYPTOGRAPHY',
  'NETWORK',
  'INTELLIGENCE',
]

/** The four difficulty tiers, easiest first. */
export const MISSION_DIFFICULTIES: readonly MissionDifficulty[] = [
  'EASY',
  'MEDIUM',
  'HARD',
  'ELITE',
]

/**
 * The status labels shown on a mission card.
 *
 * `NOT_STARTED` reads as "AVAILABLE" in the UI, which is the player-facing
 * state rather than the persisted one.
 */
export const STATUS_LABELS = {
  NOT_STARTED: 'AVAILABLE',
  IN_PROGRESS: 'IN PROGRESS',
  COMPLETED: 'COMPLETED',
} as const