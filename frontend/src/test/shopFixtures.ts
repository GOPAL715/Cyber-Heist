import type { EquipmentLoadout, InventoryItem, ShopCatalogue, ShopItem } from '@/types'

/**
 * Phase 4 fixtures.
 *
 * <p>Every price, rarity and bonus here is the sort of value the backend sends.
 * Tests assert that the UI renders these figures faithfully and never derives
 * its own, which is why no fixture includes a locally computed total.
 */

export function shopItem(overrides: Partial<ShopItem> = {}): ShopItem {
  return {
    id: '21111111-0000-4000-8000-000000000008',
    code: 'NEURAL_PROCESSOR',
    name: 'Neural Processor',
    description: 'Predicts a lock before it resolves.',
    category: 'PROCESSOR',
    rarity: 'RARE',
    slot: 'PROCESSOR',
    price: 750,
    owned: false,
    effects: [{ type: 'EXPERIENCE_BONUS', value: 10 }],
    ...overrides,
  }
}

/** The catalogue as GET /player/shop returns it. */
export function shopCatalogue(overrides: Partial<ShopCatalogue> = {}): ShopCatalogue {
  return {
    coins: 1250,
    items: [
      shopItem(),
      shopItem({
        id: '21111111-0000-4000-8000-000000000002',
        code: 'BASIC_PROCESSOR',
        name: 'Basic Processor',
        description: 'Stock silicon with the serials filed off.',
        rarity: 'COMMON',
        price: 40,
        effects: [{ type: 'EXPERIENCE_BONUS', value: 5 }],
      }),
      shopItem({
        id: '21111111-0000-4000-8000-000000000001',
        code: 'BASIC_LAPTOP',
        name: 'Basic Laptop',
        description: 'A refurbished deck with a cracked bezel.',
        category: 'DEVICE',
        rarity: 'COMMON',
        slot: 'MAIN_DEVICE',
        price: 0,
        owned: true,
        effects: [{ type: 'MISSION_SPEED', value: 5 }],
      }),
      shopItem({
        id: '21111111-0000-4000-8000-000000000009',
        code: 'INTRUSION_SUITE',
        name: 'Advanced Intrusion Suite',
        description: 'Probe library and a replay harness.',
        category: 'SOFTWARE',
        rarity: 'RARE',
        slot: 'SOFTWARE',
        price: 5000,
        effects: [{ type: 'PUZZLE_BONUS', value: 10 }],
      }),
    ],
    ...overrides,
  }
}

export function inventoryItem(overrides: Partial<InventoryItem> = {}): InventoryItem {
  return {
    inventoryId: 'aaaaaaaa-0000-4000-8000-000000000001',
    itemId: '21111111-0000-4000-8000-000000000002',
    code: 'BASIC_PROCESSOR',
    name: 'Basic Processor',
    description: 'Stock silicon with the serials filed off.',
    category: 'PROCESSOR',
    rarity: 'COMMON',
    slot: 'PROCESSOR',
    quantity: 1,
    equipped: false,
    equippedIn: null,
    effects: [{ type: 'EXPERIENCE_BONUS', value: 5 }],
    ...overrides,
  }
}

export function equipmentLoadout(overrides: Partial<EquipmentLoadout> = {}): EquipmentLoadout {
  return {
    equipment: [
      {
        slot: 'MAIN_DEVICE',
        item: inventoryItem({
          inventoryId: 'aaaaaaaa-0000-4000-8000-000000000009',
          code: 'BASIC_LAPTOP',
          name: 'Basic Laptop',
          category: 'DEVICE',
          rarity: 'COMMON',
          slot: 'MAIN_DEVICE',
          equipped: true,
          equippedIn: 'MAIN_DEVICE',
          effects: [{ type: 'MISSION_SPEED', value: 5 }],
        }),
      },
      { slot: 'PROCESSOR', item: null },
      { slot: 'SECURITY', item: null },
      { slot: 'SOFTWARE', item: null },
      { slot: 'NETWORK', item: null },
    ],
    bonuses: [{ type: 'MISSION_SPEED', percent: 5 }],
    ...overrides,
  }
}

/** A loadout with a processor equipped, so XP bonuses are visible. */
export function richLoadout(): EquipmentLoadout {
  return {
    equipment: [
      {
        slot: 'MAIN_DEVICE',
        item: inventoryItem({
          inventoryId: 'aaaaaaaa-0000-4000-8000-000000000009',
          code: 'BASIC_LAPTOP',
          name: 'Basic Laptop',
          category: 'DEVICE',
          rarity: 'COMMON',
          slot: 'MAIN_DEVICE',
          equipped: true,
          equippedIn: 'MAIN_DEVICE',
          effects: [{ type: 'MISSION_SPEED', value: 5 }],
        }),
      },
      {
        slot: 'PROCESSOR',
        item: inventoryItem({
          inventoryId: 'aaaaaaaa-0000-4000-8000-000000000001',
          code: 'NEURAL_PROCESSOR',
          name: 'Neural Processor',
          rarity: 'RARE',
          slot: 'PROCESSOR',
          equipped: true,
          equippedIn: 'PROCESSOR',
          effects: [{ type: 'EXPERIENCE_BONUS', value: 10 }],
        }),
      },
      { slot: 'SECURITY', item: null },
      { slot: 'SOFTWARE', item: null },
      { slot: 'NETWORK', item: null },
    ],
    bonuses: [
      { type: 'MISSION_SPEED', percent: 5 },
      { type: 'EXPERIENCE_BONUS', percent: 10 },
    ],
  }
}