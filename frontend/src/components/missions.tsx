import type { Mission, MissionCategory, MissionDifficulty } from '@/types'
import {
  MISSION_CATEGORIES,
  MISSION_DIFFICULTIES,
  STATUS_LABELS,
} from './missionConstants'

interface MissionFiltersProps {
  selectedCategory: MissionCategory | 'ALL'
  selectedDifficulty: MissionDifficulty | 'ALL'
  onCategoryChange: (value: MissionCategory | 'ALL') => void
  onDifficultyChange: (value: MissionDifficulty | 'ALL') => void
  /** Number of missions matching the current filters, announced to the user. */
  resultCount: number
}

const FILTER_BUTTON =
  'rounded-full border px-3 py-1 text-xs uppercase tracking-wider transition-colors'

/** Category and difficulty filter pills for the mission board. */
export function MissionFilters({
  selectedCategory,
  selectedDifficulty,
  onCategoryChange,
  onDifficultyChange,
  resultCount,
}: MissionFiltersProps) {
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[0.65rem] uppercase tracking-widest text-slate-500">Type</span>
        {(['ALL', ...MISSION_CATEGORIES] as const).map((category) => (
          <button
            key={category}
            type="button"
            aria-pressed={selectedCategory === category}
            onClick={() => onCategoryChange(category)}
            className={`${FILTER_BUTTON} ${
              selectedCategory === category
                ? 'border-neon bg-neon/15 text-neon'
                : 'border-slate-700 text-slate-400 hover:border-slate-500 hover:text-slate-200'
            }`}
          >
            {category}
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[0.65rem] uppercase tracking-widest text-slate-500">Tier</span>
        {(['ALL', ...MISSION_DIFFICULTIES] as const).map((difficulty) => (
          <button
            key={difficulty}
            type="button"
            aria-pressed={selectedDifficulty === difficulty}
            onClick={() => onDifficultyChange(difficulty)}
            className={`${FILTER_BUTTON} ${
              selectedDifficulty === difficulty
                ? 'border-magenta bg-magenta/15 text-magenta'
                : 'border-slate-700 text-slate-400 hover:border-slate-500 hover:text-slate-200'
            }`}
          >
            {difficulty}
          </button>
        ))}
      </div>

      <p className="text-xs text-slate-500" role="status">
        {resultCount} {resultCount === 1 ? 'mission' : 'missions'}
      </p>
    </div>
  )
}
interface MissionCardProps {
  mission: Mission
  isBusy: boolean
  onStart: (mission: Mission) => void
  onComplete: (mission: Mission) => void
}

/**
 * A single mission with its state and the action available for that state.
 *
 * <p>Which action is offered, and whether anything is offered at all, comes from
 * the {@code status}, {@code locked} and {@code startable} flags the server sent.
 */
export function MissionCard({ mission, isBusy, onStart, onComplete }: MissionCardProps) {
  const isLocked = mission.locked
  const isCompleted = mission.status === 'COMPLETED'
  const isInProgress = mission.status === 'IN_PROGRESS'

  const cardClass = [
    'panel flex flex-col gap-3 p-4 transition-colors',
    isLocked ? 'opacity-60' : '',
    isCompleted ? 'border-lime/30' : '',
    isInProgress ? 'border-neon/50 shadow-neon' : '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <article className={cardClass} aria-label={mission.title}>
      <header className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate font-semibold text-slate-100">{mission.title}</h3>
          <p className="mt-0.5 text-[0.65rem] uppercase tracking-widest text-slate-500">
            {mission.category} • {mission.difficulty}
          </p>
        </div>

        <span
          className={`shrink-0 rounded-full border px-2.5 py-0.5 text-[0.6rem] font-bold uppercase tracking-wider ${
            isLocked
              ? 'border-slate-600 text-slate-500'
              : isCompleted
                ? 'border-lime/40 text-lime'
                : isInProgress
                  ? 'border-neon/50 text-neon'
                  : 'border-slate-600 text-slate-300'
          }`}
        >
          {isLocked ? 'Locked' : STATUS_LABELS[mission.status]}
        </span>
      </header>

      <p className="text-sm text-slate-400">{mission.description}</p>

      <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        <div className="flex gap-1.5">
          <dt className="text-slate-500">XP</dt>
          <dd className="font-semibold text-neon">+{mission.xpReward}</dd>
        </div>
        <div className="flex gap-1.5">
          <dt className="text-slate-500">Coins</dt>
          <dd className="font-semibold text-amber">+{mission.coinReward}</dd>
        </div>
        <div className="flex gap-1.5">
          <dt className="text-slate-500">Energy</dt>
          <dd className="font-semibold text-magenta">-{mission.energyCost}</dd>
        </div>
      </dl>

      {isLocked ? (
        <p className="text-xs font-medium text-amber">{mission.lockReason}</p>
      ) : isCompleted ? (
        <p className="text-xs font-semibold text-lime">✓ {STATUS_LABELS.COMPLETED}</p>
      ) : isInProgress ? (
        <button
          type="button"
          disabled={isBusy}
          onClick={() => onComplete(mission)}
          className="btn-primary w-full"
        >
          {isBusy ? 'Working…' : 'Complete mission'}
        </button>
      ) : (
        <button
          type="button"
          disabled={isBusy || !mission.startable}
          onClick={() => onStart(mission)}
          className="btn-primary w-full"
        >
          {isBusy ? 'Starting…' : 'Start mission'}
        </button>
      )}
    </article>
  )
}