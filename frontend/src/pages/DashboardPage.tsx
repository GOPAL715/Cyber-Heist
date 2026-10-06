import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { ProgressBar, StatCard } from '@/components/game'
import { EnergyMeter } from '@/components/puzzle'
import { MissionBoard } from '@/components/MissionBoard'
import { LoadoutPanel } from '@/components/equipment'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { equipmentService, playerService } from '@/services'
import type { EquipmentLoadout, PlayerProfile } from '@/types'

export function DashboardPage() {
  const { user, authorizedRequest, isInitialising } = useAuth()

  const [profile, setProfile] = useState<PlayerProfile | null>(null)
  const [loadout, setLoadout] = useState<EquipmentLoadout | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  /**
   * Profile and loadout are fetched together.
   *
   * <p>Both are read on every visit, and the balance in the header has to agree
   * with the bonuses shown beside it. The mission board also calls back into
   * this after a payout, because equipment changes what a mission pays and the
   * level bar moves with it.
   */
  const loadPlayer = useCallback(async () => {
    try {
      const [profileData, loadoutData] = await Promise.all([
        authorizedRequest((token) => playerService.profile(token)),
        authorizedRequest((token) => equipmentService.loadout(token)),
      ])
      setProfile(profileData)
      setLoadout(loadoutData)
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
      await loadPlayer()
      if (!cancelled) setIsLoading(false)
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [isInitialising, loadPlayer])

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
          Signed in as {user?.email} as {user?.role}
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
          <StatCard icon="🪙" label="Coins" value={profile.coins} accent="amber" />
          <EnergyMeter
            energy={profile.energy}
            maximum={profile.energyMaximum}
            regenerationEnabled={profile.energyRegenerationEnabled}
            regenerationAmount={profile.energyRegenerationAmount}
            regenerationIntervalSeconds={profile.energyRegenerationIntervalSeconds}
            nextRegenerationAt={profile.nextEnergyAt}
          />
        </div>
      </section>

      {/* Refetched after each mission so level, coins and energy stay accurate. */}
      <MissionBoard onPlayerUpdated={loadPlayer} />

      {/*
        The loadout is shown compactly: missions stay the primary action, and
        this is a summary of what is currently modifying payouts rather than a
        second place to manage gear.
      */}
      {loadout && <LoadoutPanel loadout={loadout} compact />}

      <section className="space-y-4">
        <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">Gear</h3>
        <div className="grid gap-4 sm:grid-cols-2">
          <Link to="/shop" className="panel p-4 transition-colors hover:border-neon/40">
            <p className="text-sm font-bold uppercase tracking-wider text-neon">Visit the shop</p>
            <p className="mt-1 text-xs text-slate-400">
              Spend coins on devices, processors and security suites.
            </p>
          </Link>

          <Link to="/inventory" className="panel p-4 transition-colors hover:border-neon/40">
            <p className="text-sm font-bold uppercase tracking-wider text-neon">
              Open your inventory
            </p>
            <p className="mt-1 text-xs text-slate-400">
              Equip what you own and manage your loadout.
            </p>
          </Link>
        </div>
      </section>
    </div>
  )
}