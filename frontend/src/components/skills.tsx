import type { Skill, SkillBranchView } from '@/types'
import {
  branchBlurb,
  branchClasses,
  branchLabel,
  effectClasses,
  levelReadout,
  levelSummary,
  pointLabel,
} from './skillConstants'
import { effectLabel } from './equipmentConstants'

/**
 * One skill card.
 *
 * <p>The button state comes from the server's `canUnlock` rather than from a
 * local comparison of cost and balance, so the screen cannot offer an upgrade
 * the backend would refuse. The server's `blockedReason` is what the player is
 * shown when it is unavailable.
 */
interface SkillCardProps {
  skill: Skill
  isUnlocking: boolean
  onUnlock: (skill: Skill) => void
}

export function SkillCard({ skill, isUnlocking, onUnlock }: SkillCardProps) {
  const maxed = skill.currentLevel >= skill.maxLevel

  return (
    <article className="panel flex flex-col gap-3 p-4" aria-label={skill.name}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-bold uppercase tracking-wider text-slate-100">
            {skill.name}
          </h3>
          <p className="mt-0.5 text-xs tabular-nums text-slate-500">
            Level {levelReadout(skill.currentLevel, skill.maxLevel)}
          </p>
        </div>

        <span className="shrink-0 text-xs font-semibold uppercase tracking-widest text-slate-400">
          {skill.nextCost !== undefined ? `${skill.nextCost} SP` : 'MAX'}
        </span>
      </div>

      <p className="text-sm text-slate-400">{skill.description}</p>

      {/*
        Every level is listed, reached ones highlighted. The costs and values are
        the server's `skill_levels` rows, so the ladder the player reads is the
        ladder the backend charges against.
      */}
      <ol className="space-y-1">
        {skill.levels.map((level) => {
          const reached = level.level <= skill.currentLevel
          const isNext = level.level === skill.currentLevel + 1
          return (
            <li
              key={level.level}
              className={`flex items-center justify-between gap-2 rounded border px-2 py-1 text-[0.65rem] ${
                reached
                  ? 'border-lime-400/30 bg-lime-400/10'
                  : isNext
                    ? 'border-slate-600 bg-slate-800/60'
                    : 'border-slate-700/60 opacity-60'
              }`}
            >
              <span className="tabular-nums text-slate-400">Lv {level.level}</span>
              <span className={effectClasses(level.effectType)}>{levelSummary(level)}</span>
              <span className="tabular-nums text-slate-500">
                {pointLabel(level.cost)}
              </span>
            </li>
          )
        })}
      </ol>

      {skill.prerequisites.length > 0 && (
        <ul className="space-y-1" aria-label={`${skill.name} prerequisites`}>
          {skill.prerequisites.map((prerequisite) => {
            const met = prerequisite.currentLevel >= prerequisite.requiredLevel
            return (
              <li
                key={prerequisite.skillId}
                className="text-[0.65rem] text-slate-500"
              >
                Requires{' '}
                <span className={met ? 'text-lime-400' : 'text-slate-400'}>
                  {prerequisite.name} {prerequisite.requiredLevel}
                </span>{' '}
                ({levelReadout(prerequisite.currentLevel, prerequisite.requiredLevel)})
              </li>
            )
          })}
        </ul>
      )}

      <div className="mt-1 flex items-center justify-between gap-3 border-t border-slate-700/60 pt-3">
        <span className="text-[0.65rem] text-slate-500">
          {maxed
            ? 'Fully upgraded'
            : skill.nextEffectType !== undefined
              ? `Next: +${skill.nextEffectValue ?? 0}% ${effectLabel(skill.nextEffectType)}`
              : ' '}
        </span>

        <button
          type="button"
          className="btn-primary px-4 py-1.5 text-xs"
          disabled={!skill.canUnlock || isUnlocking}
          onClick={() => onUnlock(skill)}
        >
          {isUnlocking ? 'Unlocking...' : skill.canUnlock ? 'Upgrade' : 'Locked'}
        </button>
      </div>

      {skill.blockedReason && (
        <p className="text-[0.65rem] text-slate-500" role="status">
          {skill.blockedReason}
        </p>
      )}
    </article>
  )
}

/** One branch and its skills. */
interface SkillBranchColumnProps {
  branch: SkillBranchView
  /** Id of the skill currently being upgraded, for its button's busy state. */
  unlockingId: string | null
  onUnlock: (skill: Skill) => void
}

export function SkillBranchColumn({ branch, unlockingId, onUnlock }: SkillBranchColumnProps) {
  return (
    <section className="space-y-3" aria-label={`${branchLabel(branch.branch)} branch`}>
      <header>
        <h3 className={`text-sm font-bold uppercase tracking-[0.2em] ${branchClasses(branch.branch)}`}>
          {branchLabel(branch.branch)}
        </h3>
        <p className="mt-0.5 text-xs text-slate-500">{branchBlurb(branch.branch)}</p>
      </header>

      <div className="space-y-3">
        {branch.skills.map((skill) => (
          <SkillCard
            key={skill.id}
            skill={skill}
            isUnlocking={unlockingId === skill.id}
            onUnlock={onUnlock}
          />
        ))}
      </div>
    </section>
  )
}