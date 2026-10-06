import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { Button } from '@/components/ui'

/**
 * The in-game navigation, in the order a player uses them: missions to earn,
 * then the shop and inventory to spend what was earned.
 */
const NAV_LINKS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/missions', label: 'Missions' },
  { to: '/shop', label: 'Shop' },
  { to: '/inventory', label: 'Inventory' },
] as const

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

        <nav aria-label="Main" className="mx-auto max-w-5xl px-4">
          <ul className="flex gap-1 pb-2">
            {NAV_LINKS.map((link) => (
              <li key={link.to}>
                <NavLink
                  to={link.to}
                  className={({ isActive }) =>
                    `block rounded px-3 py-1.5 text-xs uppercase tracking-widest transition-colors ${
                      isActive
                        ? 'bg-neon/15 text-neon'
                        : 'text-slate-400 hover:bg-slate-800/60 hover:text-slate-200'
                    }`
                  }
                >
                  {link.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}