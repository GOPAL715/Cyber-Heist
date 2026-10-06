/** Matches the backend `Role` enum. */
export type Role = 'PLAYER' | 'ADMIN'

/** Safe account representation; the backend never returns a password hash. */
export interface AuthUser {
  id: string
  username: string
  email: string
  role: Role
}

/** Token pair returned by login and refresh. */
export interface AuthTokens {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  refreshExpiresIn?: number
  user: AuthUser
}

/** The authenticated account as kept in app state. */
export interface AuthState {
  accessToken: string
  refreshToken: string
  user: AuthUser
}

/** Envelope used by every successful backend response. */
export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
  timestamp: string
}

/** Envelope used by every backend error response. */
export interface ApiErrorResponse {
  success: boolean
  message: string
  errors?: Record<string, string>
  path?: string
  timestamp: string
}

/**
 * The calling player's own game state.
 *
 * <p>`xpIntoLevel` and `xpForNextLevel` come from the server so the UI never
 * reimplements the level curve. The energy fields do the same for
 * regeneration, so the header can show "82 / 100" and "+1 every 5 min" without
 * hardcoding either number.
 */
export interface PlayerProfile {
  id: string
  username: string
  displayName: string
  level: number
  /** Total cumulative XP; never reset on level up. */
  experience: number
  xpIntoLevel: number
  xpForNextLevel: number
  coins: number
  /** Refreshed by the server on every read; never computed locally. */
  energy: number
  energyMaximum: number
  energyRegenerationEnabled: boolean
  energyRegenerationAmount: number
  energyRegenerationIntervalSeconds: number
  /** Advisory only. The server re-checks affordability when a mission starts. */
  nextEnergyAt: string | null
  /**
   * Unspent skill points, granted one per level gained.
   *
   * <p>Server-authoritative: there is no endpoint that sets it, so the balance
   * on screen is the balance the unlock path will test.
   */
  skillPoints: number
}

export interface RegisterPayload {
  username: string
  email: string
  password: string
}

export interface LoginPayload {
  email: string
  password: string
}

// ---------------------------------------------------------------------------
// Missions
// ---------------------------------------------------------------------------

export type MissionCategory =
  | 'RECON'
  | 'EXPLOIT'
  | 'CRYPTOGRAPHY'
  | 'NETWORK'
  | 'INTELLIGENCE'

export type MissionDifficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'ELITE'

export type MissionStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED'

/** The puzzle families the backend engine can generate. */
export type PuzzleType = 'CIPHER' | 'SEQUENCE' | 'PATTERN' | 'LOGIC' | 'TIMED'

/**
 * A mission as the server presents it, already decorated with the caller's
 * level-gating. The client never computes rewards or eligibility.
 */
export interface Mission {
  id: string
  code: string
  title: string
  description: string
  category: MissionCategory
  difficulty: MissionDifficulty
  requiredLevel: number
  xpReward: number
  coinReward: number
  energyCost: number
  estimatedDurationSeconds: number
  status: MissionStatus
  locked: boolean
  lockReason: string | null
  startable: boolean
  blockedReason: string | null
  /** Which puzzle family this mission serves, so the board can label it. */
  puzzleType: PuzzleType
}

/**
 * A puzzle as the player is shown it.
 *
 * There is deliberately no `answer` or `correctAnswer` field, and the backend
 * does not send one: the answer is re-derived on the server from a seed that is
 * never transmitted, so it cannot be read out of devtools, the network tab or a
 * database dump.
 *
 * `sequence` carries the display tokens for every family - ciphertext, sequence
 * terms, grid rows, the node/edge list, the code - so each one renders without
 * special-casing the payload shape.
 */
export interface PuzzleChallenge {
  puzzleId: string
  type: PuzzleType
  difficulty: MissionDifficulty
  title: string
  question: string
  sequence: string[]
  /** Empty when the player types the answer instead of choosing. */
  options: string[]
  startedAt: string
  expiresAt: string
  /** For drawing the countdown. The server decides whether time is up. */
  timeLimitSeconds: number
}

/**
 * The response to starting a mission.
 *
 * Deliberately flat: it repeats every `Mission` field so a Phase 2 client
 * keeps working, and adds the puzzle. Nesting the mission under a key would
 * silently move every existing field.
 */
export type MissionStart = Mission & {
  puzzle: PuzzleChallenge
  attemptCount: number
  player: EnergySnapshot
}

/** Server-computed energy figures, returned with the profile and after actions. */
export interface EnergySnapshot {
  energy: number
  maximum: number
  regenerationEnabled: boolean
  regenerationAmount: number
  regenerationIntervalSeconds: number
  nextRegenerationAt: string | null
}

/** The result of completing a mission, with every value computed server-side. */
export interface MissionCompletion {
  mission: {
    id: string
    code: string
    title: string
  }
  rewards: {
    experience: number
    coins: number
  }
  progression: {
    levelBefore: number
    levelAfter: number
    experience: number
    xpIntoLevel: number
    xpForNextLevel: number
    leveledUp: boolean
    levelsGained: number
  }
  player: {
    level: number
    experience: number
    coins: number
    energy: number
  }
  alreadyCompleted: boolean
}

/**
 * What the server decided about a submitted answer.
 *
 * Only `SOLVED` carries a reward. A wrong answer does not reveal what the right
 * one was, so the challenge keeps its value on a retry.
 */
export type PuzzleOutcome = 'SOLVED' | 'INCORRECT' | 'EXPIRED'

export interface PuzzleSubmission {
  mission: {
    id: string
    code: string
    title: string
  }
  puzzleType: PuzzleType
  outcome: PuzzleOutcome
  message: string
  rewards: {
    experience: number
    coins: number
  }
  progression: {
    levelBefore: number
    levelAfter: number
    experience: number
    xpIntoLevel: number
    xpForNextLevel: number
    leveledUp: boolean
    levelsGained: number
  }
  player: EnergySnapshot
  missionCompleted: boolean
  /** True for a repeat submission: reported honestly, paid nothing. */
  alreadySolved: boolean
  /** True when starting the mission again produces a new puzzle. */
  canRetry: boolean
}
// ---------------------------------------------------------------------------
// Items, inventory and equipment (Phase 4)
//
// Every value below is decided by the server. The client renders what it is
// told and never derives a price, a rarity or a bonus: those live in the
// `items` and `item_effects` tables and reach the UI only through these DTOs.
// ---------------------------------------------------------------------------

/** Item categories, mirroring the backend `ItemCategory` enum. */
export type ItemCategory = 'DEVICE' | 'PROCESSOR' | 'SECURITY' | 'SOFTWARE' | 'NETWORK'

/** Item rarities, mirroring the backend `ItemRarity` enum. */
export type ItemRarity = 'COMMON' | 'UNCOMMON' | 'RARE' | 'EPIC' | 'LEGENDARY'

/**
 * Equipment slots. Each slot holds at most one item, so a rarity or an effect
 * can be presented against a fixed row of five without the UI inventing one.
 */
export type EquipmentSlot =
  | 'MAIN_DEVICE'
  | 'PROCESSOR'
  | 'SECURITY'
  | 'SOFTWARE'
  | 'NETWORK'

/** The bonus kinds an item can grant, mirroring the backend `ItemEffectType`. */
export type ItemEffectType =
  | 'MISSION_SPEED'
  | 'EXPERIENCE_BONUS'
  | 'COIN_BONUS'
  | 'ENERGY_EFFICIENCY'
  | 'PUZZLE_BONUS'

/**
 * One bonus on an item, as a percentage.
 *
 * <p>The value is the item's own contribution. The aggregate a player actually
 * receives after caps is a separate figure the server computes in
 * `EquipmentLoadout.bonuses`, and that is the one the game applies - the UI adds
 * nothing up.
 */
export interface ItemEffect {
  type: ItemEffectType
  value: number
}

/** A shop item, priced and described entirely by the server. */
export interface ShopItem {
  id: string
  code: string
  name: string
  description: string
  category: ItemCategory
  rarity: ItemRarity
  /** The slot this item goes in, if it is equipment. */
  slot: EquipmentSlot
  /** Authoritative cost in coins. Never computed on the client. */
  price: number
  /** True when the caller already owns it, so it cannot be bought again. */
  owned: boolean
  effects: ItemEffect[]
}

/** The catalogue plus the caller's balance, returned by GET /player/shop. */
export interface ShopCatalogue {
  items: ShopItem[]
  coins: number
}

/**
 * The result of a purchase.
 *
 * <p>`pricePaid` and `coins` are echoed from the server's own arithmetic so the
 * UI can confirm the charge rather than guessing at it.
 */
export interface PurchaseResult {
  inventoryId: string
  itemId: string
  code: string
  name: string
  pricePaid: number
  coins: number
}

/** An owned item, with its equipped state resolved server-side. */
export interface InventoryItem {
  /** Identifies the ownership row. This is what an equip request sends. */
  inventoryId: string
  itemId: string
  code: string
  name: string
  description: string
  category: ItemCategory
  rarity: ItemRarity
  slot: EquipmentSlot
  quantity: number
  equipped: boolean
  /** The slot it occupies, or null when it is not equipped. */
  equippedIn: EquipmentSlot | null
  effects: ItemEffect[]
}

/** One slot of the loadout. `item` is null for an empty slot. */
export interface EquipmentSlotView {
  slot: EquipmentSlot
  item: InventoryItem | null
}

/**
 * An aggregate bonus after caps, as a percentage.
 *
 * <p>This is the figure the server applies to rewards and energy costs. Showing
 * anything else would misreport what the player is actually earning.
 */
export interface ActiveBonus {
  type: ItemEffectType
  percent: number
}

/** The whole loadout, including empty slots and the active bonuses. */
export interface EquipmentLoadout {
  equipment: EquipmentSlotView[]
  bonuses: ActiveBonus[]
}

// ---------------------------------------------------------------------------
// Skills (Phase 5)
//
// Every figure below is server-defined. The client renders the tree and sends
// nothing but a skill id when the player wants the next level, so there is no
// request shape in which a cost, level, effect or prerequisite could be forged.
// ---------------------------------------------------------------------------

/** Skill branches, mirroring the backend `SkillBranch` enum. */
export type SkillBranch = 'SPEED' | 'INTELLIGENCE' | 'DEFENSE' | 'NETWORK'

/**
 * One level of a skill: what it costs and what it grants.
 *
 * <p>`effectValue` is the total that level grants, not an increment on the
 * level below it, so the tree can show each level in isolation.
 */
export interface SkillLevelCost {
  level: number
  cost: number
  effectType: ItemEffectType
  effectValue: number
}

/** A prerequisite and the caller's progress against it. */
export interface SkillPrerequisite {
  skillId: string
  code: string
  name: string
  requiredLevel: number
  currentLevel: number
}

/**
 * One skill, and the caller's relationship with it.
 *
 * <p>`canUnlock` is the server's verdict and is used directly for the button, so
 * the screen cannot offer something the backend would refuse.
 */
export interface Skill {
  id: string
  code: string
  name: string
  description: string
  branch: SkillBranch
  /** 0 until the first level is taken; there is no row for it server-side. */
  currentLevel: number
  maxLevel: number
  levels: SkillLevelCost[]
  /** Points the next level costs. Absent when the skill is maxed. */
  nextCost?: number
  nextEffectType?: ItemEffectType
  nextEffectValue?: number
  canUnlock: boolean
  /** True when a prerequisite is unmet. */
  locked: boolean
  /** Player-safe reason the skill cannot be taken. Absent when it can. */
  blockedReason?: string
  prerequisites: SkillPrerequisite[]
}

export interface SkillBranchView {
  branch: SkillBranch
  skills: Skill[]
}

/**
 * What equipment and skills each contribute, before the shared cap is applied.
 *
 * <p>Attribution only. The numbers the game actually applies come from the
 * capped totals, which is why this is presented as "what this source adds"
 * rather than as the player's bonus.
 */
export interface BonusBreakdown {
  equipment: Partial<Record<ItemEffectType, number>>
  skills: Partial<Record<ItemEffectType, number>>
}

export interface SkillTree {
  /** Unspent points. Server-authoritative; there is no endpoint to set it. */
  skillPoints: number
  branches: SkillBranchView[]
  /** Per-source attribution, before the shared cap. Presentation only. */
  bonuses: BonusBreakdown
  /**
   * The capped, combined totals the game actually applies.
   *
   * <p>This - not the uncapped split - is what the screen shows as the player's
   * active bonuses, so the number on screen is the number in the payout.
   */
  effectiveBonuses: ActiveBonus[]
}

/** The result of taking one level, with the server's own arithmetic. */
export interface SkillUnlockResult {
  skillId: string
  code: string
  name: string
  currentLevel: number
  maxLevel: number
  /** Points the server actually charged. */
  cost: number
  effectType: ItemEffectType
  effectValue: number
  /** Points remaining after the charge. */
  balance: number
  /** The player's effective bonuses after the change, already capped. */
  bonuses: ActiveBonus[]
}
