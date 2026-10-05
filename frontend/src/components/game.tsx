interface ProgressBarProps {
  value: number
  max: number
  label: string
}

/** Level progress indicator. */
export function ProgressBar({ value, max, label }: ProgressBarProps) {
  const percentage = max <= 0 ? 0 : Math.min(Math.round((value / max) * 100), 100)

  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between text-xs">
        <span className="uppercase tracking-widest text-slate-400">{label}</span>
        <span className="font-semibold text-neon">
          {value} / {max}
        </span>
      </div>

      <div
        role="progressbar"
        aria-valuenow={percentage}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label}
        className="h-2 w-full overflow-hidden rounded-full bg-slate-800"
      >
        <div
          className="h-full rounded-full bg-gradient-to-r from-neon to-magenta transition-[width] duration-500"
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  )
}

interface StatCardProps {
  icon: string
  label: string
  value: number | string
  accent: 'neon' | 'magenta' | 'lime' | 'amber'
}

export function StatCard({ icon, label, value, accent }: StatCardProps) {
  const accentClass = {
    neon: 'border-neon/30 text-neon',
    magenta: 'border-magenta/30 text-magenta',
    lime: 'border-lime/30 text-lime',
    amber: 'border-amber/30 text-amber',
  }[accent]

  return (
    <div className={`panel flex items-center gap-4 border p-4 ${accentClass}`}>
      <span aria-hidden="true" className="text-2xl">
        {icon}
      </span>
      <div>
        <p className="text-xs uppercase tracking-widest text-slate-400">{label}</p>
        <p className="text-2xl font-bold tabular-nums">{value}</p>
      </div>
    </div>
  )
}

interface EmptyStateProps {
  title: string
  description: string
}

/** Placeholder for the Phase 2 systems that are not built yet. */
export function ComingSoon({ title, description }: EmptyStateProps) {
  return (
    <div className="panel flex flex-col items-center gap-2 border-dashed p-8 text-center">
      <p className="text-xs uppercase tracking-[0.3em] text-magenta">{title}</p>
      <p className="text-sm text-slate-400">{description}</p>
    </div>
  )
}