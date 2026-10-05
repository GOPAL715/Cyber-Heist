import type { Mission, MissionCompletion } from '@/types'

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
})

export const inProgressMission = baseMission({
  id: '55555555-5555-4555-8555-555555555555',
  status: 'IN_PROGRESS',
  startable: false,
})

export const completedMission = baseMission({
  id: '66666666-6666-4666-8666-666666666666',
  status: 'COMPLETED',
  startable: false,
  blockedReason: 'Already completed',
})

export const completion: MissionCompletion = {
  mission: { id: baseMission().id, code: 'RECON_PERIMETER', title: 'Scan the Perimeter' },
  rewards: { experience: 50, coins: 25 },
  progression: {
    levelBefore: 1,
    levelAfter: 1,
    experience: 50,
    xpIntoLevel: 50,
    xpForNextLevel: 100,
    leveledUp: false,
    levelsGained: 0,
  },
  player: { level: 1, experience: 50, coins: 125, energy: 90 },
  alreadyCompleted: false,
}

export const levelUpCompletion: MissionCompletion = {
  ...completion,
  progression: {
    levelBefore: 1,
    levelAfter: 2,
    experience: 110,
    xpIntoLevel: 10,
    xpForNextLevel: 150,
    leveledUp: true,
    levelsGained: 1,
  },
}