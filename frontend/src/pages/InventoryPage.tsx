import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { InventoryItemCard, LoadoutPanel } from '@/components/equipment'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { equipmentService } from '@/services'
import type { EquipmentLoadout, EquipmentSlot, InventoryItem } from '@/types'

/**
 * Inventory and loadout.
 *
 * <p>Both the owned-items list and the five loadout slots are read from the
 * server, and both are re-read after an equip or unequip. Nothing is toggled
 * locally: the button state always reflects what the backend actually stored,
 * so a request that was rejected cannot leave the screen claiming otherwise.
 */
export function InventoryPage() {
  const { authorizedRequest, isInitialising } = useAuth()

  const [items, setItems] = useState<InventoryItem[]>([])
  const [loadout, setLoadout] = useState<EquipmentLoadout | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [workingId, setWorkingId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setError(null)
      const [inventory, equipment] = await Promise.all([
        authorizedRequest((token) => equipmentService.inventory(token)),
        authorizedRequest((token) => equipmentService.loadout(token)),
      ])
      setItems(inventory.items)
      setLoadout(equipment)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load your inventory right now.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function fetchInventory() {
      await load()
      if (!cancelled) setIsLoading(false)
    }

    void fetchInventory()
    return () => {
      cancelled = true
    }
  }, [isInitialising, load])

  const handleEquip = useCallback(
    async (item: InventoryItem) => {
      setWorkingId(item.inventoryId)
      setError(null)
      setNotice(null)
      try {
        // The client says which owned row to equip and into which slot; the
        // server confirms the row is this player's and fits the slot.
        await authorizedRequest((token) =>
          equipmentService.equip(token, item.slot, item.inventoryId),
        )
        setNotice(`${item.name} equipped.`)
        await load()
      } catch (equipError) {
        setError(
          equipError instanceof ApiError ? equipError.message : 'That item could not be equipped.',
        )
      } finally {
        setWorkingId(null)
      }
    },
    [authorizedRequest, load],
  )

  const handleUnequip = useCallback(
    async (slot: EquipmentSlot) => {
      setWorkingId(slot)
      setError(null)
      setNotice(null)
      try {
        await authorizedRequest((token) => equipmentService.unequip(token, slot))
        setNotice('Item unequipped. It is still in your inventory.')
        await load()
      } catch (unequipError) {
        setError(
          unequipError instanceof ApiError ? unequipError.message : 'That item could not be unequipped.',
        )
      } finally {
        setWorkingId(null)
      }
    },
    [authorizedRequest, load],
  )

  if (isLoading) return <FullPageLoader />

  return (
    <div className="space-y-6">
      <header>
        <h2 className="text-2xl font-bold text-slate-100">Inventory</h2>
        <p className="mt-1 text-sm text-slate-400">
          Your owned gear and the loadout it feeds.{' '}
          <Link to="/shop" className="text-neon underline underline-offset-4">
            Visit the shop
          </Link>{' '}
          to earn more.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}
      {notice && <Alert variant="success">{notice}</Alert>}

      {loadout && <LoadoutPanel loadout={loadout} />}

      <section className="space-y-3" aria-label="Owned items">
        <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">
          Owned items ({items.length})
        </h3>

        {items.length === 0 ? (
          <p className="text-sm text-slate-500">You do not own anything yet.</p>
        ) : (
          <div className="space-y-3">
            {items.map((item) => (
              <InventoryItemCard
                key={item.inventoryId}
                item={item}
                isWorking={workingId === item.inventoryId}
                onEquip={(selected) => void handleEquip(selected)}
                onUnequip={(slot) => void handleUnequip(slot)}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}