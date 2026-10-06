import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { BossCard, BossHistoryList } from '@/components/bosses'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { bossService } from '@/services'
import type { Boss, BossEncounter } from '@/types'

/**
 * The boss board.
 *
 * <p>Reads the catalogue and the history together, and re-reads both after an
 * encounter starts rather than patching the list locally: the energy balance,
 * the availability and the owned flags are all server facts, and a board that
 * guessed at them would disagree with what the backend does next.
 */
export function BossBoardPage() {
  const { authorizedRequest, isInitialising } = useAuth()
  const navigate = useNavigate()

  const [bosses, setBosses] = useState<Boss[]>([])
  const [history, setHistory] = useState<BossEncounter[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [startingId, setStartingId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setError(null)
      const [catalogue, past] = await Promise.all([
        authorizedRequest((token) => bossService.list(token)),
        authorizedRequest((token) => bossService.history(token)),
      ])
      setBosses(catalogue)
      setHistory(past)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load the boss network right now.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function fetchBosses() {
      await load()
      if (!cancelled) setIsLoading(false)
    }

    void fetchBosses()
    return () => {
      cancelled = true
    }
  }, [isInitialising, load])

  const handleStart = useCallback(
    async (boss: Boss) => {
      setStartingId(boss.id)
      setError(null)
      try {
        // One field goes out. The server decides the cost and the first phase.
        await authorizedRequest((token) => bossService.start(token, boss.id))
        navigate('/bosses/encounter')
      } catch (startError) {
        // Reload first, then report. Reloading clears any previous error, so
        // setting it before the reload would wipe the reason the player was
        // just shown and leave them with no idea why the fight did not start.
        await load()
        setError(
          startError instanceof ApiError
            ? startError.message
            : 'That boss could not be entered.',
        )
      } finally {
        setStartingId(null)
      }
    },
    [authorizedRequest, load, navigate],
  )

  if (isLoading) return <FullPageLoader />

  return (
    <div className="space-y-8">
      <header>
        <h2 className="text-2xl font-bold text-slate-100">Boss Network</h2>
        <p className="mt-1 text-sm text-slate-400">
          Five targets are listening. Each one takes three phases, and one wrong
          answer ends the run.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {bosses.map((boss) => (
          <BossCard
            key={boss.id}
            boss={boss}
            isStarting={startingId === boss.id}
            onStart={(selected) => void handleStart(selected)}
          />
        ))}
      </div>

      <section className="space-y-3">
        <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">Boss history</h3>
        <BossHistoryList encounters={history} />
      </section>
    </div>
  )
}