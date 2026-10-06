import { Link } from 'react-router-dom'
import type { Boss, BossEncounter } from '@/types'
import {
  availabilityLabel,
  difficultyClasses,
  formatCooldown,
  integrityClasses,
  outcomeHeadline,
  relativeTime,
} from './bossConstants'

/**
 * Boss board pieces.
 *
 * <p>Every figure rendered here arrives from the server. The board shows the
 * server's own `canStart` verdict and its `blockedReason` rather than deciding
 * affordability locally, so it can never offer a fight the backend would refuse.
 */

/** Difficulty badge. */
export function DifficultyBadge({ difficulty }: { difficulty: Boss['difficulty'] }) {
  return (
    <span
      className={`rounded border px-1.5 py-0.5 text-[0.6rem] font-semibold uppercase tracking-widest ${difficultyClasses(difficulty)}`}
    >
      {difficulty}
    </span>
  )
}

/** One boss card on the board. */
interface BossCardProps {
  boss: Boss
  isStarting: boolean
  onStart: (boss: Boss) => void
}

export function BossCard({ boss, isStarting, onStart }: BossCardProps) {
  return (
    <article className="panel flex h-full flex-col gap-3 p-4" aria-label={boss.name}>
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-bold uppercase tracking-wider text-slate-100">{boss.name}</h3>
        <DifficultyBadge difficulty={boss.difficulty} />
      </div>

      <p className="flex-1 text-sm text-slate-400">{boss.description}</p>

      <dl className="grid grid-cols-3 gap-2 text-center">
        <div className="rounded border border-slate-700/60 px-2 py-1.5">
          <dt className="text-[0.55rem] uppercase tracking-widest text-slate-500">Level</dt>
          <dd className="text-sm font-bold tabular-nums text-slate-200">{boss.requiredLevel}</dd>
        </div>
        <div className="rounded border border-slate-700/60 px-2 py-1.5">
          <dt className="text-[0.55rem] uppercase tracking-widest text-slate-500">Stages</dt>
          <dd className="text-sm font-bold tabular-nums text-slate-200">{boss.stageCount}</dd>
        </div>
        <div className="rounded border border-slate-700/60 px-2 py-1.5">
          <dt className="text-[0.55rem] uppercase tracking-widest text-slate-500">Entry</dt>
          <dd className="text-sm font-bold tabular-nums text-lime-300">{boss.energyCost}</dd>
        </div>
      </dl>

      <p className="text-xs text-slate-500">
        {boss.xpReward} XP · {boss.coinReward} coins
      </p>

      <div className="flex items-center justify-between gap-3 border-t border-slate-700/60 pt-3">
        <span className="text-[0.65rem] uppercase tracking-widest text-slate-500">
          {availabilityLabel(boss.availability)}
          {boss.availability === 'COOLDOWN' && boss.cooldownUntil && (
            <> · {formatCooldown(boss.cooldownUntil)}</>
          )}
        </span>

        {boss.availability === 'ACTIVE' && boss.activeEncounterId ? (
          <Link to="/bosses/encounter" className="btn-primary px-4 py-1.5 text-xs">
            Resume
          </Link>
        ) : (
          <button
            type="button"
            className="btn-primary px-4 py-1.5 text-xs"
            disabled={!boss.canStart || isStarting}
            onClick={() => onStart(boss)}
          >
            {isStarting ? 'Entering...' : boss.canStart ? 'Enter battle' : 'Locked'}
          </button>
        )}
      </div>

      {boss.blockedReason && (
        <p className="text-[0.65rem] text-slate-500" role="status">
          {boss.blockedReason}
        </p>
      )}
    </article>
  )
}

/**
 * The boss integrity bar.
 *
 * <p>The percentage comes from the server so the bar cannot disagree with the
 * condition that ends the fight. This only decides the colour.
 */
export function IntegrityBar({
  percent,
  label = 'Boss integrity',
}: {
  percent: number
  label?: string
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between text-xs">
        <span className="uppercase tracking-widest text-slate-400">{label}</span>
        <span className="font-semibold tabular-nums text-rose-300">{percent}%</span>
      </div>
      <div
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label}
        className="h-3 w-full overflow-hidden rounded-full bg-slate-800"
      >
        <div
          className={`h-full bg-gradient-to-r transition-[width] duration-500 motion-reduce:transition-none ${integrityClasses(percent)}`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  )
}

/** A one-line narration of a stage transition. */
export function StageTransition({ message }: { message?: string }) {
  if (!message) return null
  return (
    <p
      className="rounded border border-neon/30 bg-neon/10 px-3 py-2 text-sm text-neon"
      role="status"
    >
      {message}
    </p>
  )
}

/**
 * The end-of-fight panel.
 *
 * <p>Shows the server's reward figures. It never recomputes them, and it never
 * shows anything about the puzzle that was answered.
 */
export function BossOutcomePanel({ encounter }: { encounter: BossEncounter }) {
  const headline = outcomeHeadline(encounter.status)
  const won = encounter.status === 'VICTORY'

  return (
    <section
      className={`panel p-6 text-center ${won ? 'border-lime-400/40' : 'border-rose-500/40'}`}
      aria-label="Boss outcome"
    >
      <h3
        className={`text-xl font-bold uppercase tracking-[0.3em] ${won ? 'text-lime-300' : 'text-rose-300'}`}
      >
        {headline}
      </h3>

      <p className="mt-2 text-sm text-slate-300">{encounter.bossName}</p>

      {won && encounter.rewards && (
        <>
          <p className="mt-4 text-sm font-semibold text-neon">
            +{encounter.rewards.experience} XP
          </p>
          <p className="text-sm font-semibold text-amber-300">
            +{encounter.rewards.coins} coins
          </p>
          {encounter.progression && encounter.progression.levelsGained > 0 && (
            <p className="mt-3 text-sm font-semibold text-lime-300">
              LEVEL UP! +{encounter.progression.skillPointsGained} skill point
              {encounter.progression.skillPointsGained === 1 ? '' : 's'}
            </p>
          )}
        </>
      )}

      {!won && <p className="mt-4 text-sm text-slate-400">No rewards earned.</p>}

      {encounter.cooldownUntil && (
        <p className="mt-4 text-xs text-slate-500">
          Next attempt in {formatCooldown(encounter.cooldownUntil)}
        </p>
      )}

      <Link to="/bosses" className="btn-secondary mt-6 inline-block px-4 py-1.5 text-xs">
        Return to boss board
      </Link>
    </section>
  )
}

/** The player's recent fights. */
export function BossHistoryList({ encounters }: { encounters: BossEncounter[] }) {
  if (encounters.length === 0) {
    return <p className="text-sm text-slate-500">No boss encounters yet.</p>
  }

  return (
    <ul className="space-y-2" aria-label="Boss history">
      {encounters.map((encounter) => {
        const won = encounter.status === 'VICTORY'
        return (
          <li
            key={encounter.encounterId}
            className="panel flex flex-wrap items-center justify-between gap-2 px-3 py-2"
          >
            <span className="flex items-center gap-2">
              <span aria-hidden="true" className={won ? 'text-lime-400' : 'text-rose-400'}>
                {won ? '✓' : '✕'}
              </span>
              <span className="text-sm font-semibold text-slate-100">{encounter.bossName}</span>
              <span className="text-[0.65rem] uppercase tracking-widest text-slate-500">
                {encounter.status}
              </span>
            </span>

            <span className="flex items-center gap-3 text-xs">
              {won && (
                <span className="tabular-nums text-neon">
                  +{encounter.xpAwarded} XP · +{encounter.coinAwarded} coins
                </span>
              )}
              <span className="text-slate-500">
                stage {encounter.reachedStage}/{encounter.stageCount}
              </span>
              <span className="text-slate-500">{relativeTime(encounter.startedAt)}</span>
            </span>
          </li>
        )
      })}
    </ul>
  )
}