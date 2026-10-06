import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { ProgressBar, StatCard } from '@/components/game'
import { EnergyMeter } from '@/components/puzzle'
import { MissionBoard } from '@/components/MissionBoard'
import { LoadoutPanel } from '@/components/equipment'
import { DifficultyBadge } from '@/components/bosses'
import { availabilityLabel } from '@/components/bossConstants'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { bossService, equipmentService, playerService, achievementService } from '@/services'
import type { Boss, EquipmentLoadout, PlayerProfile } from '@/types'
import type { Achievement } from '@/types/phase7'

export function DashboardPage() {
  const { user, authorizedRequest, isInitialising } = useAuth()

  const [profile, setProfile] = useState<PlayerProfile | null>(null)
  const [loadout, setLoadout] = useState<EquipmentLoadout | null>(null)
  const [nextBoss, setNextBoss] = useState<Boss | null>(null)
  const [achievements, setAchievements] = useState<Achievement[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadPlayer = useCallback(async () => {
    try {
      const [profileData, loadoutData, bossList, achievementsData] = await Promise.all([
        authorizedRequest((token) => playerService.profile(token)),
        authorizedRequest((token) => equipmentService.loadout(token)),
        authorizedRequest((token) => bossService.list(token)),
        authorizedRequest((token) => achievementService.list(token)),
      ])
      setProfile(profileData)
      setLoadout(loadoutData)
      setNextBoss(bossList.find((boss) => boss.canStart) ?? null)
      setAchievements(achievementsData)
    } catch (loadError) {
      setError(loadError instanceof ApiError ? loadError.message : 'Unable to load your profile.')
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
    return <div className='py-8'><Alert>{error ?? 'Profile unavailable.'}</Alert></div>
  }

  const unlockedAchievements = achievements.filter((a) => a.unlocked)

  return (
    <div className='space-y-8'>
      <section>
        <h2 className='text-2xl font-bold text-slate-100'>
          Welcome back, <span className='neon-text'>{profile.displayName}</span>
        </h2>
        <p className='mt-1 text-sm text-slate-400'>
          Signed in as {user?.email} as {user?.role}
        </p>
      </section>

      <section className='panel p-6' aria-label='Player progress'>
        <div className='mb-6 flex flex-wrap items-center gap-6'>
          <div>
            <p className='text-xs uppercase tracking-widest text-slate-400'>Level</p>
            <p className='neon-text text-4xl font-bold'>{profile.level}</p>
          </div>

          <div className='min-w-[220px] flex-1'>
            <ProgressBar
              value={profile.xpIntoLevel}
              max={profile.xpForNextLevel}
              label='XP'
            />
          </div>

          <StatCard
            label='Coins'
            value={profile.coins}
            accent='neon'
            icon='ðŸ’°'
          />

          <StatCard
            label='Skill Points'
            value={profile.skillPoints}
            accent='neon'
            icon='âœ¨'
          />

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

      <MissionBoard onPlayerUpdated={loadPlayer} />

      {loadout && <LoadoutPanel loadout={loadout} compact />}

      <section className='space-y-4'>
        <h3 className='text-xs uppercase tracking-[0.3em] text-slate-500'>Progression</h3>
        <div className='grid gap-4 sm:grid-cols-3'>
          <Link to='/shop' className='panel p-4 transition-colors hover:border-neon/40'>
            <p className='text-sm font-bold uppercase tracking-wider text-neon'>Visit the shop</p>
            <p className='mt-1 text-xs text-slate-400'>
              Spend coins on devices, processors and security suites.
            </p>
          </Link>

          <Link to='/inventory' className='panel p-4 transition-colors hover:border-neon/40'>
            <p className='text-sm font-bold uppercase tracking-wider text-neon'>
              Open your inventory
            </p>
            <p className='mt-1 text-xs text-slate-400'>
              Equip what you own and manage your loadout.
            </p>
          </Link>

          <Link to='/skills' className='panel p-4 transition-colors hover:border-neon/40'>
            <p className='text-sm font-bold uppercase tracking-wider text-neon'>
              Spend your skill points
            </p>
            <p className='mt-1 text-xs text-slate-400'>
              You have {profile.skillPoints} point{profile.skillPoints === 1 ? '' : 's'} to
              invest in the tree.
            </p>
          </Link>
        </div>
      </section>

      <section className='space-y-4'>
        <h3 className='text-xs uppercase tracking-[0.3em] text-slate-500'>Next boss</h3>
        {nextBoss ? (
          <Link to='/bosses' className='panel block p-4 transition-colors hover:border-magenta/40'>
            <div className='flex flex-wrap items-center justify-between gap-3'>
              <div className='flex items-center gap-3'>
                <DifficultyBadge difficulty={nextBoss.difficulty} />
                <span className='text-sm font-bold uppercase tracking-wider text-slate-100'>
                  {nextBoss.name}
                </span>
              </div>
              <span className='text-xs uppercase tracking-widest text-slate-500'>
                {availabilityLabel(nextBoss.availability)}
              </span>
            </div>
            <p className='mt-1 text-xs text-slate-400'>
              {nextBoss.stageCount} stages Â· entry {nextBoss.energyCost} energy Â· requires level{' '}
              {nextBoss.requiredLevel}
            </p>
          </Link>
        ) : (
          <Link to='/bosses' className='panel block p-4 transition-colors hover:border-magenta/40'>
            <p className='text-sm font-bold uppercase tracking-wider text-magenta'>View bosses</p>
            <p className='mt-1 text-xs text-slate-400'>
              Five targets are waiting on the network.
            </p>
          </Link>
        )}
      </section>

      <section className='panel p-6' aria-label='Daily challenges'>
        <div className='flex flex-wrap items-center justify-between gap-4 mb-4'>
          <h3 className='text-sm font-bold uppercase tracking-wider text-slate-500'>
            Daily challenges
          </h3>
          <Link to='/daily' className='text-sm font-semibold text-neon hover:underline'>
            View all
          </Link>
        </div>
        <p className='text-sm text-slate-400'>
          Complete your challenges to earn daily rewards.
        </p>
        <div className='mt-4'>
          <p className='text-sm text-slate-500'>
            Visit the daily page to see today's challenges and track your streak.
          </p>
        </div>
      </section>

      <section className='panel p-6' aria-label='Achievements'>
        <div className='flex flex-wrap items-center justify-between gap-4 mb-4'>
          <h3 className='text-sm font-bold uppercase tracking-wider text-slate-500'>
            Achievements
          </h3>
          <Link to='/achievements' className='text-sm font-semibold text-neon hover:underline'>
            View all
          </Link>
        </div>
        <div className='flex items-center justify-between mb-3'>
          <p className='text-sm text-slate-400'>
            {unlockedAchievements.length} of {achievements.length} milestones earned
          </p>
        </div>
        <div className='flex gap-2'>
          {achievements.slice(0, 4).map((achievement) => (
            <div key={achievement.code} className='flex items-center gap-2 p-2 rounded border border-slate-700/40 bg-slate-800/30'>
              <span className='text-lg' aria-hidden='true'>
                {achievement.icon}
              </span>
              <div className='flex-1 min-w-0'>
                <p className='text-xs font-semibold text-slate-100 truncate'>
                  {achievement.name}
                </p>
                <p className={'text-xs ' + (achievement.unlocked ? 'text-emerald-400' : 'text-slate-500')}>
                  {achievement.unlocked ? 'âœ“ Unlocked' : 'ðŸ”’ Locked'}
                </p>
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  )
}
