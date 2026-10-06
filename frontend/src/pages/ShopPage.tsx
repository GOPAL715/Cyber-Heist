import { useCallback, useEffect, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { ShopItemCard } from '@/components/equipment'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { shopService } from '@/services'
import type { ShopCatalogue, ShopItem } from '@/types'

/**
 * The shop.
 *
 * <p>Every price, rarity and effect on this page is rendered from the server's
 * catalogue response. The page holds no price table and no affordability rules
 * of its own - the only thing it decides is whether to grey out a button using
 * the two figures the server sent, and the server still performs the real check
 * when the purchase arrives.
 */
export function ShopPage() {
  const { authorizedRequest, isInitialising } = useAuth()

  const [catalogue, setCatalogue] = useState<ShopCatalogue | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [buyingId, setBuyingId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setError(null)
      const data = await authorizedRequest((token) => shopService.catalogue(token))
      setCatalogue(data)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load the shop right now.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function fetchCatalogue() {
      await load()
      if (!cancelled) setIsLoading(false)
    }

    void fetchCatalogue()
    return () => {
      cancelled = true
    }
  }, [isInitialising, load])

  const handleBuy = useCallback(
    async (item: ShopItem) => {
      setBuyingId(item.id)
      setError(null)
      setNotice(null)
      try {
        // One field goes out: the item id. The server decides the price.
        const result = await authorizedRequest((token) => shopService.purchase(token, item.id))
        setNotice(`${result.name} purchased for ${result.pricePaid} coins.`)
        // Reload rather than patching locally: the authoritative balance and
        // owned flags come from the catalogue endpoint.
        await load()
      } catch (buyError) {
        setError(
          buyError instanceof ApiError ? buyError.message : 'The purchase could not be completed.',
        )
      } finally {
        setBuyingId(null)
      }
    },
    [authorizedRequest, load],
  )

  if (isLoading) return <FullPageLoader />

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold text-slate-100">Cyber Heist Shop</h2>
          <p className="mt-1 text-sm text-slate-400">
            Spend mission rewards on gear. Every purchase takes effect immediately.
          </p>
        </div>

        <div className="panel px-4 py-2" aria-label="Player balance">
          <p className="text-[0.65rem] uppercase tracking-widest text-slate-500">Balance</p>
          <p className="text-xl font-bold tabular-nums text-amber-300">
            {catalogue?.coins ?? 0} coins
          </p>
        </div>
      </header>

      {error && <Alert>{error}</Alert>}
      {notice && <Alert variant="success">{notice}</Alert>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {catalogue?.items.map((item) => (
          <ShopItemCard
            key={item.id}
            item={item}
            coins={catalogue.coins}
            isBuying={buyingId === item.id}
            onBuy={(selected) => void handleBuy(selected)}
          />
        ))}
      </div>

      {catalogue && catalogue.items.length === 0 && (
        <p className="text-sm text-slate-500">The shop is empty right now.</p>
      )}
    </div>
  )
}