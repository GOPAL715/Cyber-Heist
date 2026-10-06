import type {
  ActiveBonus,
  EquipmentLoadout,
  EquipmentSlot,
  EquipmentSlotView,
  InventoryItem,
  ItemEffect,
  ItemRarity,
  ShopItem,
} from '@/types'
import { effectLabel, effectSummary, rarityClasses, slotLabel } from './equipmentConstants'



/** Rarity badge element. */
export function RarityBadge({ rarity }: { rarity: ItemRarity }) {
  return (
    <span
      className={`rounded border px-1.5 py-0.5 text-[0.6rem] font-semibold uppercase tracking-widest ${rarityClasses(rarity)}`}
    >
      {rarity}
    </span>
  )
}

/**
 * A row of bonuses, or nothing when the item grants none.
 *
 * <p>An item with no effects renders no element rather than a zero, so an empty
 * row never reads as "+0%".
 */
export function EffectList({ effects }: { effects: ItemEffect[] }) {
  if (effects.length === 0) return null

  return (
    <ul className="flex flex-wrap gap-1.5">
      {effects.map((effect) => (
        <li
          key={effect.type}
          className="rounded border border-cyan-500/30 bg-cyan-500/10 px-1.5 py-0.5 text-[0.65rem] font-medium text-cyan-200"
        >
          {effectSummary(effect)}
        </li>
      ))}
    </ul>
  )
}

/** A shop item card, with a buy action. */
interface ShopItemCardProps {
  item: ShopItem
  coins: number
  isBuying: boolean
  onBuy: (item: ShopItem) => void
}

/**
 * One purchasable item.
 *
 * <p>The affordability hint is derived from the two server-provided figures -
 * the balance and the catalogue price - purely to decide whether to disable the
 * button. It is not a second source of pricing: the server still charges
 * `item.price`, and the balance shown after a purchase comes back from it.
 */
export function ShopItemCard({ item, coins, isBuying, onBuy }: ShopItemCardProps) {
  const affordable = !item.owned && coins >= item.price

  return (
    <article className="panel flex h-full flex-col gap-3 p-4" aria-label={item.name}>
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-bold uppercase tracking-wider text-slate-100">{item.name}</h3>
        <RarityBadge rarity={item.rarity} />
      </div>

      <p className="text-xs uppercase tracking-widest text-slate-500">{item.category}</p>

      <p className="flex-1 text-sm text-slate-400">{item.description}</p>

      <EffectList effects={item.effects} />

      <div className="flex items-center justify-between gap-3 border-t border-slate-700/60 pt-3">
        <span className="text-sm font-bold tabular-nums text-amber-300">{item.price} coins</span>

        {item.owned ? (
          <span className="text-xs font-semibold uppercase tracking-widest text-lime-400">
            Owned
          </span>
        ) : (
          <button
            type="button"
            className="btn-primary px-4 py-1.5 text-xs"
            disabled={!affordable || isBuying}
            onClick={() => onBuy(item)}
          >
            {isBuying ? 'Buying...' : affordable ? 'Buy' : 'Not enough coins'}
          </button>
        )}
      </div>
    </article>
  )
}

/** An owned item row, with equip and unequip actions. */
interface InventoryItemCardProps {
  item: InventoryItem
  isWorking: boolean
  onEquip: (item: InventoryItem) => void
  onUnequip: (slot: EquipmentSlot) => void
}

export function InventoryItemCard({ item, isWorking, onEquip, onUnequip }: InventoryItemCardProps) {
  return (
    <article
      className="panel flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between"
      aria-label={item.name}
    >
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-sm font-bold uppercase tracking-wider text-slate-100">{item.name}</h3>
          <RarityBadge rarity={item.rarity} />
          {item.equipped && (
            <span className="rounded border border-lime-400/40 bg-lime-400/10 px-1.5 py-0.5 text-[0.6rem] font-semibold uppercase tracking-widest text-lime-300">
              Equipped
            </span>
          )}
        </div>

        <p className="text-xs uppercase tracking-widest text-slate-500">{slotLabel(item.slot)}</p>
        <p className="text-sm text-slate-400">{item.description}</p>
        <EffectList effects={item.effects} />
      </div>

      <div className="shrink-0">
        {item.equipped && item.equippedIn ? (
          <button
            type="button"
            className="btn-secondary px-4 py-1.5 text-xs"
            disabled={isWorking}
            onClick={() => onUnequip(item.equippedIn as EquipmentSlot)}
          >
            Unequip
          </button>
        ) : (
          <button
            type="button"
            className="btn-primary px-4 py-1.5 text-xs"
            disabled={isWorking}
            onClick={() => onEquip(item)}
          >
            Equip
          </button>
        )}
      </div>
    </article>
  )
}

/** One slot of the loadout, shown empty rather than omitted. */
function LoadoutSlot({ view }: { view: EquipmentSlotView }) {
  return (
    <li className="flex items-center justify-between gap-4 border-b border-slate-700/50 px-4 py-3 last:border-b-0">
      <div className="min-w-0">
        <p className="text-[0.65rem] uppercase tracking-[0.2em] text-slate-500">
          {slotLabel(view.slot)}
        </p>
        <p
          className={`truncate text-sm font-semibold ${
            view.item ? 'text-slate-100' : 'text-slate-600 italic'
          }`}
        >
          {view.item ? view.item.name : 'Empty'}
        </p>
      </div>

      {view.item ? (
        <div className="flex shrink-0 items-center gap-2">
          <EffectList effects={view.item.effects} />
          <RarityBadge rarity={view.item.rarity} />
        </div>
      ) : null}
    </li>
  )
}

interface LoadoutPanelProps {
  loadout: EquipmentLoadout
  /** Compact rendering for the dashboard, where space is limited. */
  compact?: boolean
}

/**
 * The player's loadout and their active bonuses.
 *
 * <p>The percentages come from the server's aggregation, which is the same
 * number the reward and energy paths apply. Showing a locally summed figure
 * instead would be a lie whenever an item is capped or unequipped.
 */
export function LoadoutPanel({ loadout, compact = false }: LoadoutPanelProps) {
  const filled = loadout.equipment.filter((view) => view.item !== null)

  return (
    <section className="panel" aria-label="Player loadout">
      <header className="flex items-center justify-between border-b border-slate-700/50 px-4 py-3">
        <h2 className="text-xs uppercase tracking-[0.3em] text-slate-400">Player Loadout</h2>
        <span className="text-xs tabular-nums text-slate-500">
          {filled.length}/{loadout.equipment.length} slots
        </span>
      </header>

      <ul>
        {loadout.equipment.map((view) =>
          compact && view.item === null ? null : <LoadoutSlot key={view.slot} view={view} />,
        )}
      </ul>

      {loadout.bonuses.length > 0 && (
        <div
          className="flex flex-wrap items-center gap-2 border-t border-slate-700/50 px-4 py-3"
          aria-label="Active bonuses"
        >
          <span className="text-[0.65rem] uppercase tracking-[0.2em] text-slate-500">Bonuses</span>
          {loadout.bonuses.map((bonus) => (
            <BonusChip key={bonus.type} bonus={bonus} />
          ))}
        </div>
      )}
    </section>
  )
}

/** A single "+10% XP" chip. */
export function BonusChip({ bonus }: { bonus: ActiveBonus }) {
  return (
    <span className="rounded border border-lime-400/30 bg-lime-400/10 px-2 py-0.5 text-[0.65rem] font-semibold text-lime-300">
      +{bonus.percent}% {effectLabel(bonus.type)}
    </span>
  )
}