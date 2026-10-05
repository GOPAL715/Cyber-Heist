import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui'

/** Application shell shown around the dashboard. */
export function DashboardLayout() {
  const { user, logout } = useAuth()

  return (
    <div className="min-h-screen">
      <header className="border-b border-cyan-500/20 bg-night/70 backdrop-blur">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-4">
          <h1 className="neon-text text-lg font-bold tracking-[0.3em]">CYBER HEIST</h1>

          <div className="flex items-center gap-4">
            {user && (
              <div className="hidden text-right sm:block">
                <p className="text-sm font-semibold text-slate-200">{user.username}</p>
                <p className="text-[0.65rem] uppercase tracking-widest text-slate-500">
                  {user.role}
                </p>
              </div>
            )}
            <NavLink to="/profile" className="btn-secondary text-xs">
              Profile
            </NavLink>
            <Button variant="secondary" onClick={() => void logout()}>
              Logout
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}