import { useCallback, useEffect, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { ComingSoon, ProgressBar, StatCard } from '@/components/game'
import { MissionBoard } from '@/components/MissionBoard'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { playerService } from '@/services'
import type { PlayerProfile } from '@/types'

export function DashboardPage() {
  const { user, authorizedRequest, isInitialising } = useAuth()

  const [profile, setProfile] = useState<PlayerProfile | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadProfile = useCallback(async () => {
    try {
      // authorizedRequest refreshes the access token automatically if needed.
      const data = await authorizedRequest((token) => playerService.profile(token))
      setProfile(data)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError
          ? loadError.message
          : 'Unable to load your profile right now.',
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
    <div className="space-y-8">
      <section>
        <h2 className="text-2xl font-bold text-slate-100">
          Welcome back, <span className="neon-text">{profile.displayName}</span>
        </h2>
        <p className="mt-1 text-sm text-slate-400">
          Signed in as {user?.email} · {user?.role}
        </p>
      </section>

      <section className="panel p-6" aria-label="Player progress">
        <div className="mb-6 flex flex-wrap items-center gap-6">
          <div>
            <p className="text-xs uppercase tracking-widest text-slate-400">Level</p>
            <p className="neon-text text-4xl font-bold">{profile.level}</p>
          </div>

          <div className="min-w-[220px] flex-1">
            {/*
              The level band comes from the server, so the client never needs to
              know the curve. At the level cap the target is zero, in which case
              a full bar is the honest representation.
            */}
            <ProgressBar
              value={profile.xpIntoLevel}
              max={profile.xpForNextLevel}
              label="XP"
            />
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <StatCard icon="💰" label="Coins" value={profile.coins} accent="amber" />
          <StatCard icon="⚡" label="Energy" value={profile.energy} accent="neon" />
        </div>
      </section>

      {/* Refetched after each mission so level, coins and energy stay accurate. */}
      <MissionBoard onPlayerUpdated={loadProfile} />

      <section className="space-y-4">
        <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">Next up</h3>
        <ComingSoon
          title="Puzzle engine — coming soon"
          description="Interactive contracts powered by each mission category."
        />
        <ComingSoon
          title="Upgrades & skill tree — coming soon"
          description="Spend your coins on gear and unlock new capabilities."
        />
      </section>
    </div>
  )
}