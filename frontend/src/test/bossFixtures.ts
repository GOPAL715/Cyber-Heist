import type { Boss, BossDetail, BossEncounter } from '@/types'

/**
 * Phase 6 fixtures.
 *
 * <p>Every cost, damage figure, integrity value and reward is the sort of value
 * the backend sends. Tests assert the UI renders these faithfully and never
 * derives its own.
 *
 * <p>Puzzle tokens are plain ASCII on purpose: a glyph like a filled circle is
 * easy to corrupt through a shell or an editor's encoding, and a test that fails
 * on a mangled character teaches nothing about the boss system.
 */

export function boss(overrides: Partial<Boss> = {}): Boss {
  return {
    id: '41111111-0000-4000-8000-000000000001',
    code: 'THE_FIREWALL',
    name: 'The Firewall',
    description: 'Not a program. A mind that has learned to say no.',
    difficulty: 'MEDIUM',
    requiredLevel: 6,
    energyCost: 30,
    stageCount: 3,
    xpReward: 350,
    coinReward: 220,
    availability: 'AVAILABLE',
    canStart: true,
    stages: [
      {
        stageNumber: 1,
        name: 'Scan the Perimeter',
        description: 'Map what is watching.',
        puzzleType: 'PATTERN',
        damageValue: 20,
      },
      {
        stageNumber: 2,
        name: 'Break the Cipher',
        description: 'Prove you know the protocol.',
        puzzleType: 'CIPHER',
        damageValue: 30,
      },
      {
        stageNumber: 3,
        name: 'Override Core Security',
        description: 'One decision left.',
        puzzleType: 'LOGIC',
        damageValue: 50,
      },
    ],
    ...overrides,
  }
}

/** A boss the player is too low to enter. */
export function lockedBoss(overrides: Partial<Boss> = {}): Boss {
  return boss({
    id: '41111111-0000-4000-8000-000000000005',
    code: 'THE_ARCHITECT',
    name: 'The Architect',
    difficulty: 'ELITE',
    requiredLevel: 26,
    energyCost: 50,
    availability: 'LOCKED',
    canStart: false,
    blockedReason: 'Requires level 26',
    ...overrides,
  })
}

/**
 * A boss cooling down after a recent attempt.
 *
 * <p>Given its own identity so a board holding both it and an open boss does not
 * render two cards with the same accessible name.
 */
export function coolingBoss(overrides: Partial<Boss> = {}): Boss {
  return boss({
    id: '41111111-0000-4000-8000-000000000004',
    code: 'ZERO_DAY',
    name: 'Zero Day',
    description: 'The window before the patch.',
    availability: 'COOLDOWN',
    canStart: false,
    cooldownUntil: new Date(Date.now() + 29 * 60_000).toISOString(),
    blockedReason: 'Available again shortly',
    ...overrides,
  })
}

/** A pattern puzzle, as the encounter endpoint returns it. */
export const stageOnePuzzle = {
  puzzleId: 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa',
  type: 'PATTERN' as const,
  difficulty: 'MEDIUM' as const,
  title: 'Find the gap',
  question: 'Which symbol is missing?',
  sequence: ['square', 'circle', 'square', 'square', '?'],
  options: ['circle', 'square', 'triangle'],
  startedAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 240_000).toISOString(),
  timeLimitSeconds: 240,
  multipleChoice: true,
}

export function activeEncounter(overrides: Partial<BossEncounter> = {}): BossEncounter {
  return {
    encounterId: 'bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb',
    bossId: '41111111-0000-4000-8000-000000000001',
    bossCode: 'THE_FIREWALL',
    bossName: 'The Firewall',
    bossDifficulty: 'MEDIUM',
    status: 'ACTIVE',
    currentStage: 1,
    stageCount: 3,
    bossIntegrity: 100,
    bossIntegrityPercent: 100,
    reachedStage: 1,
    puzzle: stageOnePuzzle,
    stageName: 'Scan the Perimeter',
    stageDescription: 'Map what is watching.',
    xpAwarded: 0,
    coinAwarded: 0,
    startedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    ...overrides,
  }
}

/** A finished encounter that paid. */
export function victoryEncounter(overrides: Partial<BossEncounter> = {}): BossEncounter {
  return activeEncounter({
    status: 'VICTORY',
    currentStage: 3,
    reachedStage: 3,
    bossIntegrity: 0,
    bossIntegrityPercent: 0,
    puzzle: undefined,
    xpAwarded: 350,
    coinAwarded: 220,
    cooldownUntil: new Date(Date.now() + 12 * 3_600_000).toISOString(),
    rewards: { experience: 350, coins: 220 },
    progression: {
      levelBefore: 6,
      levelAfter: 7,
      levelsGained: 1,
      skillPointsGained: 1,
    },
    outcomeMessage: 'BOSS DEFEATED. The Firewall is down.',
    ...overrides,
  })
}

/** A finished encounter that paid nothing. */
export function defeatedEncounter(overrides: Partial<BossEncounter> = {}): BossEncounter {
  return activeEncounter({
    status: 'DEFEATED',
    reachedStage: 1,
    puzzle: undefined,
    xpAwarded: 0,
    coinAwarded: 0,
    cooldownUntil: new Date(Date.now() + 30 * 60_000).toISOString(),
    outcomeMessage: 'ACCESS DENIED. The boss read your intent and closed the door.',
    ...overrides,
  })
}

export function bossDetail(overrides: Partial<BossDetail> = {}): BossDetail {
  return {
    ...boss(),
    // Server-defined cooldowns, not client guesses.
    cooldownVictoryMinutes: 720,
    cooldownDefeatMinutes: 30,
    ...overrides,
  }
}