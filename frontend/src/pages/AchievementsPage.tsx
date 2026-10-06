import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { ProgressBar } from '@/components/game'
import { achievementService } from '@/services'
import type { Achievement as AchievementType } from '@/types/phase7'

type Category = AchievementType['category']

const CATEGORY_ORDER: Category[] = [
  'MISSIONS',
  'PUZZLES',
  'PROGRESSION',
  'ECONOMY',
  'EQUIPMENT',
  'SKILLS',
  'BOSSES',
  'DAILY',
]

const CATEGORY_LABELS: Record<Category, string> = {
  MISSIONS: 'Missions',
  PUZZLES: 'Puzzles',
  PROGRESSION: 'Progression',
  ECONOMY: 'Economy',
  EQUIPMENT: 'Equipment',
  SKILLS: 'Skills',
  BOSSES: 'Bosses',
  DAILY: 'Daily',
}

export function AchievementsPage() {
  const [achievements, setAchievements] = useState<AchievementType[]>([])
  const [categoryFilter, setCategoryFilter] = useState<Category | 'ALL'>('ALL')
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadAchievements = useCallback(async () => {
    try {
      setError(null)
      const token = sessionStorage.getItem('accessToken')
      const list = await achievementService.list(token ?? '')
      setAchievements(list)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Unable to load achievements.')
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadAchievements()
  }, [loadAchievements])

  const filtered = categoryFilter === 'ALL' ? achievements : achievements.filter((a) => a.category === categoryFilter)
  const unlockedCount = achievements.filter((a) => a.unlocked).length

  if (isLoading) return <FullPageLoader />
  if (error) {
    return <div className='py-8'><Alert>{error}</Alert></div>
  }

  return (
    <div className='space-y-8'>
      <section>
        <div className='flex flex-wrap items-center justify-between gap-4'>
          <div>
            <h2 className='text-2xl font-bold text-slate-100'>Achievements</h2>
            <p className='mt-1 text-sm text-slate-400'>{unlockedCount} of {achievements.length} milestones earned</p>
          </div>
          <div className='flex flex-wrap gap-2'>
            <button onClick={() => setCategoryFilter('ALL')} className={'px-3 py-1.5 text-sm font-semibold rounded border transition ' + (categoryFilter === 'ALL' ? 'bg-neon/10 text-neon border-neon/30' : 'bg-slate-800/60 text-slate-400 hover:bg-slate-700/40')}>All</button>
            {CATEGORY_ORDER.map((category) => (
              <button key={category} onClick={() => setCategoryFilter(category)} className={'px-3 py-1.5 text-sm font-semibold rounded border transition ' + (categoryFilter === category ? 'bg-neon/10 text-neon border-neon/30' : 'bg-slate-800/60 text-slate-400 hover:bg-slate-700/40')}>{CATEGORY_LABELS[category]}</button>
            ))}
          </div>
        </div>
      </section>
      <section className='grid gap-4 md:grid-cols-2 lg:grid-cols-3'>
        {filtered.map((achievement) => (
          <div key={achievement.code} className={'panel transition-shadow hover:shadow-md ' + (achievement.unlocked ? 'border-emerald/30 bg-emerald/5' : 'border-slate-700/40 bg-slate-800/30')}>
            <div className='flex items-start justify-between'>
              <div className='flex items-center gap-3'>
                <span className='text-2xl' aria-hidden='true'>{achievement.icon}</span>
                <div>
                  <h3 className={'font-bold text-lg ' + (achievement.unlocked ? 'text-emerald-400' : 'text-slate-100')}>{achievement.name}</h3>
                  <p className='text-xs text-slate-500'>{CATEGORY_LABELS[achievement.category]}</p>
                </div>
              </div>
              {achievement.unlocked ? (
                <span className='inline-flex items-center gap-1 text-xs font-semibold text-emerald-400'><span>✓</span> UNLOCKED</span>
              ) : (
                <span className='inline-flex items-center gap-1 text-xs font-semibold text-slate-500'><span>🔒</span> LOCKED</span>
              )}
            </div>
            <p className='mt-3 text-sm text-slate-400'>{achievement.description}</p>
            <div className='mt-4'>
              <ProgressBar
                value={achievement.progress}
                max={achievement.requirement}
                label={`${achievement.progress} / ${achievement.requirement}`}
              />
            </div>
            {achievement.reward.experience > 0 && (
              <div className='mt-3 flex flex-wrap gap-3 text-xs text-slate-500'>
                {achievement.reward.experience > 0 && <span>+{achievement.reward.experience} XP</span>}
                {achievement.reward.coins > 0 && <span>+{achievement.reward.coins} coins</span>}
              </div>
            )}
            {achievement.unlockedAt && (
              <p className='mt-2 text-xs text-slate-600'>Unlocked {new Date(achievement.unlockedAt).toLocaleDateString()}</p>
            )}
          </div>
        ))}
      </section>
      {filtered.length === 0 && <div className='panel'><p className='text-sm text-slate-500'>No achievements match the selected category.</p></div>}
      <section className='panel'>
        <h3 className='text-sm font-bold uppercase tracking-wider text-slate-500 mb-4'>Recently unlocked</h3>
        {achievements.some((a) => a.unlocked) ? (
          <div className='space-y-2'>
            {achievements.filter((a) => a.unlocked).slice(0, 10).map((a) => (
              <div key={a.code} className='flex items-center justify-between gap-4 border-b border-slate-700/30 pb-2'>
                <div className='flex items-center gap-3'>
                  <span className='text-lg' aria-hidden='true'>{a.icon}</span>
                  <div>
                    <p className='font-semibold text-slate-100'>{a.name}</p>
                    <p className='text-xs text-slate-500'>{a.progress} / {a.requirement}</p>
                  </div>
                </div>
                <span className='text-xs font-semibold text-emerald-400'>{new Date(a.unlockedAt ?? '').toLocaleDateString()}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className='text-sm text-slate-500'>No achievements unlocked yet.</p>
        )}
      </section>
      <Link to='/daily' className='block text-center'>
        <span className='inline-flex items-center gap-2 px-5 py-3 rounded border border-neon/30 bg-neon/5 text-sm font-semibold text-neon transition-colors hover:bg-neon/10'>Continue Your Streak</span>
      </Link>
    </div>
  )
}
