import type { MissionCompletion } from '@/types'

interface MissionCompleteOverlayProps {
  result: MissionCompletion
  onDismiss: () => void
}

/**
 * Reward summary shown after a mission completes.
 *
 * <p>All figures come straight from the server response. The only animation is
 * a fade, and it is disabled under `prefers-reduced-motion`, so the panel is
 * fully usable without it.
 */
export function MissionCompleteOverlay({ result, onDismiss }: MissionCompleteOverlayProps) {
  const { progression } = result
  const leveledUp = progression.leveledUp

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 animate-flicker"
      role="dialog"
      aria-modal="true"
      aria-labelledby="mission-complete-title"
    >
      <div className="panel w-full max-w-sm p-6 text-center shadow-neon">
        <h2 id="mission-complete-title" className="neon-text text-lg font-bold tracking-[0.2em]">
          {result.alreadyCompleted ? 'ALREADY CLAIMED' : 'MISSION COMPLETE'}
        </h2>
        <p className="mt-1 text-sm text-slate-400">{result.mission.title}</p>

        {result.alreadyCompleted ? (
          <p className="mt-4 text-sm text-slate-400">
            This mission was already completed. No additional rewards were granted.
          </p>
        ) : (
          <div className="mt-6 space-y-3">
            <div className="flex items-center justify-between text-sm">
              <span className="uppercase tracking-widest text-slate-400">Experience</span>
              <span className="font-bold text-neon">+{result.rewards.experience} XP</span>
            </div>
            <div className="flex items-center justify-between text-sm">
              <span className="uppercase tracking-widest text-slate-400">Coins</span>
              <span className="font-bold text-amber">+{result.rewards.coins}</span>
            </div>
          </div>
        )}

        {leveledUp && (
          <div className="mt-6 rounded-lg border border-magenta/40 bg-magenta/10 p-4">
            <p className="text-xs font-bold uppercase tracking-[0.3em] text-magenta">Level up!</p>
            <p className="mt-1 text-lg font-bold text-slate-100">
              Level {progression.levelBefore} → Level {progression.levelAfter}
            </p>
          </div>
        )}

        <button type="button" onClick={onDismiss} className="btn-primary mt-6 w-full">
          Continue
        </button>
      </div>
    </div>
  )
}