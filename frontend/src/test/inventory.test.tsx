import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { InventoryPage } from '@/pages/InventoryPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import { equipmentLoadout, inventoryItem, richLoadout } from './shopFixtures'
import type { EquipmentLoadout, InventoryItem } from '@/types'

/**
 * The inventory and loadout screen.
 *
 * <p>Equipped state is asserted against what the server reports after each
 * action, because the page re-reads rather than toggling locally: a screen that
 * flipped its own state could show an equip that the backend rejected.
 */

function signIn() {
  saveSession({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

const laptop = inventoryItem({
  inventoryId: 'aaaaaaaa-0000-4000-8000-000000000009',
  code: 'BASIC_LAPTOP',
  name: 'Basic Laptop',
  category: 'DEVICE',
  rarity: 'COMMON',
  slot: 'MAIN_DEVICE',
  equipped: true,
  equippedIn: 'MAIN_DEVICE',
  effects: [{ type: 'MISSION_SPEED', value: 5 }],
})

const processor = inventoryItem({
  inventoryId: 'aaaaaaaa-0000-4000-8000-000000000001',
  code: 'BASIC_PROCESSOR',
  name: 'Basic Processor',
  rarity: 'COMMON',
  slot: 'PROCESSOR',
})

const rare = inventoryItem({
  inventoryId: 'aaaaaaaa-0000-4000-8000-000000000002',
  code: 'NEURAL_PROCESSOR',
  name: 'Neural Processor',
  rarity: 'RARE',
  slot: 'PROCESSOR',
  effects: [{ type: 'EXPERIENCE_BONUS', value: 10 }],
})

/**
 * Session restore, then the inventory and loadout the page fetches together.
 */
function mockPage(options: {
  items?: InventoryItem[]
  loadout?: EquipmentLoadout
} = {}) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(
      jsonResponse({ success: true, data: { items: options.items ?? [laptop, processor] } }),
    )
    .mockResolvedValueOnce(
      jsonResponse({ success: true, data: options.loadout ?? equipmentLoadout() }),
    )
}

beforeEach(() => {
  vi.restoreAllMocks()
  signIn()
})

describe('InventoryPage', () => {
  it('renders every owned item with its rarity and effects', async () => {
    mockPage({ items: [laptop, rare] })

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Neural Processor' })
    expect(card).toHaveTextContent('RARE')
    expect(card).toHaveTextContent('+10% XP')
    expect(card).toHaveTextContent('Stock silicon with the serials filed off.')
    expect(screen.getByRole('article', { name: 'Basic Laptop' })).toBeInTheDocument()
  })

  it('shows an Equipped badge and an Unequip action for equipped items', async () => {
    mockPage()

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Basic Laptop' })
    expect(card).toHaveTextContent('Equipped')
    expect(within(card).getByRole('button', { name: 'Unequip' })).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Equip' })).not.toBeInTheDocument()
  })

  it('offers Equip for items that are not equipped', async () => {
    mockPage()

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Basic Processor' })
    expect(within(card).getByRole('button', { name: 'Equip' })).toBeInTheDocument()
  })

  it('renders all five slots, showing empty ones explicitly', async () => {
    mockPage()

    renderWithProviders(<InventoryPage />)

    await screen.findByRole('article', { name: 'Basic Laptop' })

    // The server returns every slot; empty ones are labelled rather than
    // omitted, so the player can see what is still to fill. Scoped to the
    // loadout panel, because slot names also appear on the owned-item cards.
    const loadoutPanel = screen.getByLabelText('Player loadout')
    for (const slot of ['Main Device', 'Processor', 'Security', 'Software', 'Network']) {
      expect(within(loadoutPanel).getByText(slot)).toBeInTheDocument()
    }
    expect(within(loadoutPanel).getAllByText('Empty')).toHaveLength(4)
    expect(loadoutPanel).toHaveTextContent('1/5 slots')
  })

  it('shows the aggregated bonuses the server calculated', async () => {
    mockPage({ items: [laptop, rare], loadout: richLoadout() })

    renderWithProviders(<InventoryPage />)

    const bonuses = await screen.findByLabelText('Active bonuses')
    expect(bonuses).toHaveTextContent('+5% Mission Speed')
    expect(bonuses).toHaveTextContent('+10% XP')
  })

  it('equips an owned item and re-reads the server state', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: { items: [laptop, processor] } }),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: equipmentLoadout() }))
      // Equip result.
      .mockResolvedValueOnce(jsonResponse({ success: true, data: processor }))
      // Refreshed inventory and loadout, now with the processor equipped.
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: { items: [laptop, { ...processor, equipped: true, equippedIn: 'PROCESSOR' }] },
        }),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: richLoadout() }))

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Basic Processor' })
    await user.click(within(card).getByRole('button', { name: 'Equip' }))

    await waitFor(() =>
      expect(screen.getByRole('article', { name: 'Basic Processor' })).toHaveTextContent(
        'Equipped',
      ),
    )

    // The equip request sent the slot and the inventory row id, and nothing
    // that could set a rarity, an effect or an ownership claim.
    const equipCall = fetchSpy.mock.calls.find(([url]) =>
      String(url).includes('/api/v1/player/equipment/PROCESSOR'),
    )
    expect(equipCall).toBeDefined()
    const [, options] = equipCall as [string, RequestInit]
    expect(JSON.parse(String(options.body))).toEqual({
      inventoryItemId: processor.inventoryId,
    })
  })

  it('unequips without losing the item', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: { items: [laptop] } }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: equipmentLoadout() }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: null }))
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: { items: [{ ...laptop, equipped: false, equippedIn: null }] },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: {
            equipment: equipmentLoadout().equipment.map((view) =>
              view.slot === 'MAIN_DEVICE' ? { slot: view.slot, item: null } : view,
            ),
            bonuses: [],
          },
        }),
      )

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Basic Laptop' })
    await user.click(within(card).getByRole('button', { name: 'Unequip' }))

    expect(await screen.findByText(/still in your inventory/i)).toBeInTheDocument()
    // The item is still listed; it is simply no longer equipped.
    expect(screen.getByRole('article', { name: 'Basic Laptop' })).toHaveTextContent('Equip')
  })

  it('surfaces the server message when an equip is rejected', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: { items: [laptop, processor] } }),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: equipmentLoadout() }))
      .mockResolvedValueOnce(
        jsonResponse(
          { success: false, message: 'Item is not in your inventory' },
          404,
        ),
      )

    renderWithProviders(<InventoryPage />)

    const card = await screen.findByRole('article', { name: 'Basic Processor' })
    await user.click(within(card).getByRole('button', { name: 'Equip' }))

    expect(await screen.findByText('Item is not in your inventory')).toBeInTheDocument()
  })

  it('surfaces a backend error when the inventory cannot be loaded', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Inventory unavailable' }, 500),
      )
      .mockResolvedValueOnce(jsonResponse({ success: true, data: equipmentLoadout() }))

    renderWithProviders(<InventoryPage />)

    expect(await screen.findByText('Inventory unavailable')).toBeInTheDocument()
  })

  it('tells the player when they own nothing', async () => {
    mockPage({ items: [] })

    renderWithProviders(<InventoryPage />)

    expect(await screen.findByText(/do not own anything yet/i)).toBeInTheDocument()
  })
})