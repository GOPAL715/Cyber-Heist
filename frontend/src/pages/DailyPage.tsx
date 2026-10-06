import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { ProgressBar } from '@/components/game'
import { dailyService } from '@/services'
import type { DailyOverview } from '@/types/phase7'

export function DailyPage() {
  const [overview, setOverview] = useState<DailyOverview | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadDaily = useCallback(async () => {
    try {
      setError(null)
      const token = sessionStorage.getItem('accessToken')
      const data = await dailyService.today(token ?? '')
      setOverview(data)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Unable to load today's challenges.")
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadDaily()
  }, [loadDaily])

  if (isLoading) return <FullPageLoader />
  if (error) {
    return <div className='py-8'><Alert>{error}</Alert></div>
  }

  if (!overview) {
    return <div className='py-8'><Alert>No daily data available.</Alert></div>
  }

  const totalCompleted = overview.challenges.filter((c) => c.completed).length
  const total = overview.challenges.length

  return (
    <div className='space-y-8'>
      <section>
        <div className='flex flex-wrap items-center justify-between gap-4'>
          <div>
            <h2 className='text-2xl font-bold text-slate-100'>Daily</h2>
            <p className='mt-1 text-sm text-slate-400'>
              Business date: {overview.date} · {totalCompleted} of {total} completed
            </p>
          </div>
          <div className='flex items-center gap-2'>
            <span className='inline-flex items-center gap-1 px-3 py-1.5 rounded bg-neon/10 text-xs font-semibold text-neon'>
              🔥 {overview.streak.current} day{overview.streak.current === 1 ? ' streak' : ' streaks'}
            </span>
            <span className='text-sm text-slate-400'>Longest: {overview.streak.longest}</span>
          </div>
        </div>
      </section>

      <section className='panel'>
        <div className='flex items-center justify-between mb-4'>
          <h3 className='text-sm font-bold uppercase tracking-wider text-slate-500'>
            Today's objectives
          </h3>
          <Link to='/achievements' className='text-sm text-neon hover:underline'>
            View achievements
          </Link>
        </div>

        <div className='space-y-4'>
          {overview.challenges.map((challenge) => (
            <div key={challenge.code} className='flex items-start gap-4'>
              <div className={'inline-flex items-center justify-center w-8 h-8 rounded-full text-sm font-bold ' + (challenge.completed ? 'bg-emerald/20 text-emerald-400' : 'bg-slate-700/50 text-slate-400')}>
                {challenge.completed ? '✓' : challenge.progress === challenge.requirement ? '✓' : challenge.progress}
              </div>
              <div className='flex-1 min-w-0'>
                <div className='flex flex-wrap items-center justify-between gap-2 mb-1'>
                  <h4 className={'font-semibold text-slate-100 ' + (challenge.completed ? 'text-emerald-400' : '')}>
                    {challenge.title}
                  </h4>
                  {challenge.completed && (
                    <span className='text-xs font-semibold text-emerald-400'>
                      COMPLETED
                    </span>
                  )}
                </div>
                <p className='text-sm text-slate-400'>{challenge.description}</p>
                <div className='mt-2'>
                  <ProgressBar
                    value={challenge.progress}
                    max={challenge.requirement}
                    label={`${challenge.progress} / ${challenge.requirement}`}
                  />
                </div>
                {challenge.reward.experience > 0 && (
                  <p className='mt-2 text-xs text-slate-500'>
                    <span className='font-semibold'>+{challenge.reward.experience} XP</span>{' '}
                    <span className='mx-1'>·</span>
                    <span className='font-semibold'>+{challenge.reward.coins} coins</span>
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className='panel'>
        <h3 className='text-sm font-bold uppercase tracking-wider text-slate-500 mb-4'>
          Streak
        </h3>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='text-center p-4 rounded border border-slate-700/40 bg-slate-800/30'>
            <p className='text-3xl font-bold text-neon'>{overview.streak.current}</p>
            <p className='text-xs text-slate-500 mt-1'>Current streak</p>
          </div>
          <div className='text-center p-4 rounded border border-slate-700/40 bg-slate-800/30'>
            <p className='text-3xl font-bold text-slate-100'>{overview.streak.longest}</p>
            <p className='text-xs text-slate-500 mt-1'>Longest streak</p>
          </div>
        </div>
      </section>

      <div className='flex justify-center'>
        <button className='inline-flex items-center gap-2 px-5 py-3 rounded border border-neon/30 bg-neon/5 text-sm font-semibold text-neon transition-colors hover:bg-neon/10'>
          Refresh
        </button>
      </div>
    </div>
  )
}
