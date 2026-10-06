import { useCallback, useEffect, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { MissionBoard } from '@/components/MissionBoard'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { playerService } from '@/services'
import type { PlayerProfile } from '@/types'

/**
 * The full mission board on its own route.
 *
 * <p>The dashboard keeps a compact board because running a job is the primary
 * action in the game. This route is the same component with room to breathe,
 * for a player who wants to browse the catalogue without the rest of the
 * dashboard in the way.
 */
export function MissionsPage() {
  const { authorizedRequest, isInitialising } = useAuth()

  const [profile, setProfile] = useState<PlayerProfile | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // The board reports reward payouts by calling back, and those change level,
  // coins and energy - so the profile is refetched alongside it.
  const loadProfile = useCallback(async () => {
    try {
      const data = await authorizedRequest((token) => playerService.profile(token))
      setProfile(data)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load your profile right now.',
      )
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function load() {
      await loadProfile()
      if (!cancelled) setIsLoading(false)
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [isInitialising, loadProfile])

  if (isLoading) return <FullPageLoader />

  if (error || !profile) {
    return (
      <div className="py-8">
        <Alert>{error ?? 'Profile unavailable.'}</Alert>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <header>
        <h2 className="text-2xl font-bold text-slate-100">Missions</h2>
        <p className="mt-1 text-sm text-slate-400">
          Pick a target, spend the energy, solve the challenge, collect the payout.
        </p>
      </header>

      <MissionBoard onPlayerUpdated={loadProfile} />
    </div>
  )
}