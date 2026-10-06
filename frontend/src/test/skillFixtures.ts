import type { Skill, SkillBranchView, SkillTree } from '@/types'

/**
 * Phase 5 fixtures.
 *
 * <p>Every cost, level and percentage is the sort of value the backend sends.
 * Tests assert the UI renders these faithfully and never derives its own, which
 * is why no fixture includes a locally computed affordability rule.
 */

export function skill(overrides: Partial<Skill> = {}): Skill {
  return {
    id: '31111111-0000-4000-8000-000000000001',
    code: 'RAPID_EXECUTION',
    name: 'Rapid Execution',
    description: 'Trim every avoidable second from a run.',
    branch: 'SPEED',
    currentLevel: 0,
    maxLevel: 5,
    levels: [
      { level: 1, cost: 1, effectType: 'MISSION_SPEED', effectValue: 2 },
      { level: 2, cost: 1, effectType: 'MISSION_SPEED', effectValue: 4 },
      { level: 3, cost: 2, effectType: 'MISSION_SPEED', effectValue: 6 },
      { level: 4, cost: 2, effectType: 'MISSION_SPEED', effectValue: 8 },
      { level: 5, cost: 3, effectType: 'MISSION_SPEED', effectValue: 10 },
    ],
    nextCost: 1,
    nextEffectType: 'MISSION_SPEED',
    nextEffectValue: 2,
    canUnlock: true,
    locked: false,
    prerequisites: [],
    ...overrides,
  }
}

/** A skill gated behind another, with the prerequisite unmet. */
export function lockedSkill(overrides: Partial<Skill> = {}): Skill {
  return skill({
    id: '31111111-0000-4000-8000-000000000002',
    code: 'QUICK_RESPONSE',
    name: 'Quick Response',
    branch: 'SPEED',
    description: 'Anticipate the alarm instead of waiting for it.',
    currentLevel: 0,
    nextEffectType: 'ENERGY_EFFICIENCY',
    nextEffectValue: 3,
    canUnlock: false,
    locked: true,
    blockedReason: 'Rapid Execution level 2 required',
    prerequisites: [
      {
        skillId: '31111111-0000-4000-8000-000000000001',
        code: 'RAPID_EXECUTION',
        name: 'Rapid Execution',
        requiredLevel: 2,
        currentLevel: 0,
      },
    ],
    ...overrides,
  })
}

/** A fully upgraded skill, which has no next level and no cost. */
export function maxedSkill(overrides: Partial<Skill> = {}): Skill {
  return skill({
    currentLevel: 5,
    nextCost: undefined,
    nextEffectType: undefined,
    nextEffectValue: undefined,
    canUnlock: false,
    blockedReason: 'Fully upgraded',
    ...overrides,
  })
}

function branch(over: Partial<SkillBranchView> & Pick<SkillBranchView, 'branch'>): SkillBranchView {
  return { skills: [], ...over }
}

export function skillTree(overrides: Partial<SkillTree> = {}): SkillTree {
  return {
    skillPoints: 4,
    branches: [
      branch({ branch: 'SPEED', skills: [skill(), lockedSkill()] }),
      branch({
        branch: 'INTELLIGENCE',
        skills: [
          skill({
            id: '31111111-0000-4000-8000-000000000004',
            code: 'CIPHER_MASTERY',
            name: 'Cipher Mastery',
            branch: 'INTELLIGENCE',
            description: 'Read a cipher like prose.',
            nextEffectType: 'PUZZLE_BONUS',
            nextEffectValue: 2,
          }),
        ],
      }),
      branch({ branch: 'DEFENSE', skills: [maxedSkill({ name: 'Hardened Core', code: 'HARDENED_CORE' })] }),
      branch({ branch: 'NETWORK', skills: [] }),
    ],
    bonuses: {
      equipment: { MISSION_SPEED: 5 },
      skills: {},
    },
    effectiveBonuses: [{ type: 'MISSION_SPEED', percent: 5 }],
    ...overrides,
  }
}

/** The result of a successful unlock, as the server reports it. */
export const unlockResult = {
  skillId: '31111111-0000-4000-8000-000000000001',
  code: 'RAPID_EXECUTION',
  name: 'Rapid Execution',
  currentLevel: 1,
  maxLevel: 5,
  cost: 1,
  effectType: 'MISSION_SPEED' as const,
  effectValue: 2,
  balance: 3,
  bonuses: [{ type: 'MISSION_SPEED' as const, percent: 7 }],
}