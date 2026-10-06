import type { ItemEffect, ItemRarity } from '@/types'

/**
 * Item display constants.
 *
 * <p>Kept out of `equipment.tsx` so that file exports only components, which
 * lets React Fast Refresh work there.
 *
 * <p>Deliberately no numbers here. Slot names, rarity colours and effect labels
 * are the only things this module decides; every price and percentage arrives
 * from the server. Hardcoding a price or a bonus alongside these would be the
 * exact mistake the Phase 4 security model exists to prevent, because the
 * display would then disagree with what the backend actually charges.
 */

/** Human label for a slot, e.g. MAIN_DEVICE -> "Main Device". */
export function slotLabel(slot: string): string {
  return slot
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ')
}

/** Human label for an effect type, e.g. EXPERIENCE_BONUS -> "XP". */
export function effectLabel(type: ItemEffect['type']): string {
  const labels: Record<ItemEffect['type'], string> = {
    MISSION_SPEED: 'Mission Speed',
    EXPERIENCE_BONUS: 'XP',
    COIN_BONUS: 'Coins',
    ENERGY_EFFICIENCY: 'Energy Efficiency',
    PUZZLE_BONUS: 'Puzzle',
  }
  return labels[type]
}

/** Short sentence describing a bonus, e.g. "+10% XP". */
export function effectSummary(effect: ItemEffect): string {
  return `+${effect.value}% ${effectLabel(effect.type)}`
}

/** Tailwind classes for a rarity badge. */
export function rarityClasses(rarity: ItemRarity): string {
  const classes: Record<ItemRarity, string> = {
    COMMON: 'border-slate-500/40 bg-slate-500/10 text-slate-300',
    UNCOMMON: 'border-lime-400/40 bg-lime-400/10 text-lime-300',
    RARE: 'border-neon/40 bg-neon/10 text-neon',
    EPIC: 'border-magenta/40 bg-magenta/10 text-magenta',
    LEGENDARY: 'border-amber-400/40 bg-amber-400/10 text-amber-300',
  }
  return classes[rarity]
}