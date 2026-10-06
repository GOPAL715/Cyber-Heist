import type { EnergySnapshot, Mission, MissionStart, PuzzleChallenge, PuzzleSubmission } from '@/types'

function baseMission(overrides: Partial<Mission> = {}): Mission {
  return {
    id: '33333333-3333-4333-8333-333333333333',
    code: 'RECON_PERIMETER',
    title: 'Scan the Perimeter',
    description: 'Sweep the outer ring of the target building.',
    category: 'RECON',
    difficulty: 'EASY',
    requiredLevel: 1,
    xpReward: 50,
    coinReward: 25,
    energyCost: 10,
    estimatedDurationSeconds: 180,
    status: 'NOT_STARTED',
    locked: false,
    lockReason: null,
    startable: true,
    blockedReason: null,
    puzzleType: 'SEQUENCE',
    ...overrides,
  }
}

export const mission = baseMission

export const lockedMission = baseMission({
  id: '44444444-4444-4444-8444-444444444444',
  code: 'INTEL_RECOVER_DATA',
  title: 'Recover the Data',
  description: 'The whole job hinges on one vault.',
  category: 'INTELLIGENCE',
  difficulty: 'ELITE',
  requiredLevel: 7,
  xpReward: 380,
  coinReward: 190,
  energyCost: 40,
  status: 'NOT_STARTED',
  locked: true,
  lockReason: 'Requires level 7',
  startable: false,
  puzzleType: 'LOGIC',
})

export const inProgressMission = baseMission({
  id: '55555555-5555-4555-8555-555555555555',
  status: 'IN_PROGRESS',
  startable: true,
})

export const completedMission = baseMission({
  id: '66666666-6666-4666-8666-666666666666',
  status: 'COMPLETED',
  startable: false,
  blockedReason: 'Already completed',
})

/** Energy figures as the server reports them. */
export const energy: EnergySnapshot = {
  energy: 90,
  maximum: 100,
  regenerationEnabled: true,
  regenerationAmount: 1,
  regenerationIntervalSeconds: 300,
  nextRegenerationAt: new Date(Date.now() + 120_000).toISOString(),
}

/** A multiple-choice sequence challenge, shaped exactly as the backend sends it. */
export function sequencePuzzle(overrides: Partial<PuzzleChallenge> = {}): PuzzleChallenge {
  return {
    puzzleId: '77777777-7777-4777-8777-777777777777',
    type: 'SEQUENCE',
    difficulty: 'EASY',
    title: 'Find the next number',
    question: 'Each term is multiplied by the same factor. What number comes next?',
    sequence: ['2', '4', '8', '16', '?'],
    options: ['24', '30', '32', '36'],
    startedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 150_000).toISOString(),
    timeLimitSeconds: 150,
    ...overrides,
  }
}

/** A free-text cipher challenge. */
export function cipherPuzzle(overrides: Partial<PuzzleChallenge> = {}): PuzzleChallenge {
  return {
    puzzleId: '88888888-8888-4888-8888-888888888888',
    type: 'CIPHER',
    difficulty: 'EASY',
    title: 'Decode the transmission',
    question: 'The intercept below was shifted by a fixed Caesar offset.',
    sequence: ['KHOOR'],
    options: [],
    startedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 240_000).toISOString(),
    timeLimitSeconds: 240,
    ...overrides,
  }
}

/** The start response: every mission field, plus the puzzle. */
export function missionStart(
  source: Mission = mission(),
  puzzle: PuzzleChallenge = sequencePuzzle(),
): MissionStart {
  return {
    ...source,
    status: 'IN_PROGRESS',
    startable: true,
    puzzle,
    attemptCount: 1,
    player: energy,
  }
}

const progression = {
  levelBefore: 1,
  levelAfter: 1,
  experience: 50,
  xpIntoLevel: 50,
  xpForNextLevel: 100,
  leveledUp: false,
  levelsGained: 0,
}

const rewards = { experience: 50, coins: 25 }
const summary = {
  id: '33333333-3333-4333-8333-333333333333',
  code: 'RECON_PERIMETER',
  title: 'Scan the Perimeter',
}

/** A correct submission. */
export const solvedSubmission: PuzzleSubmission = {
  mission: summary,
  puzzleType: 'SEQUENCE',
  outcome: 'SOLVED',
  message: 'Security bypassed.',
  rewards,
  progression,
  player: { ...energy, energy: 90 },
  missionCompleted: true,
  alreadySolved: false,
  canRetry: false,
}

/** A wrong submission: no reward, and the puzzle is spent. */
export const incorrectSubmission: PuzzleSubmission = {
  ...solvedSubmission,
  outcome: 'INCORRECT',
  message: 'Incorrect answer. No rewards earned.',
  rewards: { experience: 0, coins: 0 },
  progression: { ...progression, experience: 0, xpIntoLevel: 0 },
  missionCompleted: false,
  canRetry: true,
}

/** A submission that arrived after the window closed. */
export const expiredSubmission: PuzzleSubmission = {
  ...incorrectSubmission,
  outcome: 'EXPIRED',
  message: 'The security system detected inactivity and closed the connection.',
  canRetry: false,
}

/** A repeat submission against an already-solved puzzle. */
export const replaySubmission: PuzzleSubmission = {
  ...solvedSubmission,
  alreadySolved: true,
  message: 'This puzzle has already been solved.',
  rewards: { experience: 0, coins: 0 },
}

/** A submission whose reward crossed a level threshold. */
export const levelUpSubmission: PuzzleSubmission = {
  ...solvedSubmission,
  progression: {
    ...progression,
    levelAfter: 2,
    experience: 110,
    xpIntoLevel: 10,
    xpForNextLevel: 150,
    leveledUp: true,
    levelsGained: 1,
  },
}