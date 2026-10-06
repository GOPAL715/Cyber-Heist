import { effectLabel } from './equipmentConstants'
import type { ItemEffectType, SkillBranch, SkillLevelCost } from '@/types'

/**
 * Skill tree display constants.
 *
 * <p>Kept out of `skills.tsx` so that file exports only components, which lets
 * React Fast Refresh work there.
 *
 * <p>Deliberately no numbers. Branch headings, effect wording and presentation
 * are the only things this module decides; every cost, level and percentage
 * arrives from the server. Hardcoding a point cost here would be the exact
 * mistake the Phase 5 security model exists to prevent, because the display
 * would then disagree with what the backend actually charges.
 */

/** Headline for each branch. */
export function branchLabel(branch: SkillBranch): string {
  const labels: Record<SkillBranch, string> = {
    SPEED: 'Speed',
    INTELLIGENCE: 'Intelligence',
    DEFENSE: 'Defense',
    NETWORK: 'Network',
  }
  return labels[branch]
}

/**
 * One line describing what a branch is for.
 *
 * <p>Written as prose rather than derived from the skills, because the effects a
 * branch grants differ per skill and a generated sentence would be misleading.
 */
export function branchBlurb(branch: SkillBranch): string {
  const blurbs: Record<SkillBranch, string> = {
    SPEED: 'Move faster and last longer between systems.',
    INTELLIGENCE: 'Read the target and turn that into experience.',
    DEFENSE: 'Absorb the cost of a long, difficult run.',
    NETWORK: 'Reach further and take more with you.',
  }
  return blurbs[branch]
}

/** Tailwind classes for a branch heading. */
export function branchClasses(branch: SkillBranch): string {
  const classes: Record<SkillBranch, string> = {
    SPEED: 'text-magenta',
    INTELLIGENCE: 'text-neon',
    DEFENSE: 'text-lime-400',
    NETWORK: 'text-amber-300',
  }
  return classes[branch]
}

/**
 * Tailwind classes for one effect type.
 *
 * <p>Aligned with the equipment components so an XP bonus reads the same whether
 * it came from a skill or from an item.
 */
export function effectClasses(type: ItemEffectType): string {
  const classes: Record<ItemEffectType, string> = {
    MISSION_SPEED: 'border-magenta/30 bg-magenta/10 text-magenta',
    EXPERIENCE_BONUS: 'border-neon/30 bg-neon/10 text-neon',
    COIN_BONUS: 'border-amber-400/30 bg-amber-400/10 text-amber-300',
    ENERGY_EFFICIENCY: 'border-lime-400/30 bg-lime-400/10 text-lime-300',
    PUZZLE_BONUS: 'border-cyan-300/30 bg-cyan-300/10 text-cyan-200',
  }
  return classes[type]
}

/** "2 / 5" for a skill's level readout. */
export function levelReadout(currentLevel: number, maxLevel: number): string {
  return `${currentLevel} / ${maxLevel}`
}

/** "1 point" or "3 points". */
export function pointLabel(cost: number): string {
  return `${cost} point${cost === 1 ? '' : 's'}`
}

/** Describes what a level grants, e.g. "+4% XP". */
export function levelSummary(level: SkillLevelCost): string {
  return `+${level.effectValue}% ${effectLabel(level.effectType)}`
}