import { Alert, FullPageLoader } from '@/components/ui'
import { useAuth } from '@/context/AuthContext'

/** Shows the signed-in account exactly as the backend returns it. */
export function ProfilePage() {
  const { user, isInitialising } = useAuth()

  if (isInitialising) return <FullPageLoader />
  if (!user) return <Alert>You are not signed in.</Alert>

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-slate-100">Profile</h2>

      <dl className="panel divide-y divide-slate-700/60">
        {[
          { label: 'Alias', value: user.username },
          { label: 'Email', value: user.email },
          { label: 'Role', value: user.role },
          { label: 'User ID', value: user.id },
        ].map((row) => (
          <div key={row.label} className="flex items-center justify-between px-5 py-3.5">
            <dt className="text-xs uppercase tracking-widest text-slate-500">{row.label}</dt>
            <dd className="break-all text-sm text-slate-200">{row.value}</dd>
          </div>
        ))}
      </dl>

      <p className="text-xs text-slate-600">
        Session details such as password hashes and tokens are never sent to the browser.
      </p>
    </div>
  )
}