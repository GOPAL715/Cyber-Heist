import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '@/context/AuthContext'
import { FullPageLoader } from '@/components/ui'

/**
 * Gate for authenticated routes.
 *
 * <p>An unauthenticated visitor is sent to /login, remembering where they were
 * headed so login can return them there. This is a UX convenience only - the
 * backend independently rejects unauthenticated requests.
 */
export function ProtectedRoute() {
  const { isAuthenticated, isInitialising } = useAuth()
  const location = useLocation()

  if (isInitialising) return <FullPageLoader />

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }

  return <Outlet />
}

/**
 * Gate for guest-only routes such as /login and /register.
 *
 * <p>Keeps a signed-in player from landing on the login form, redirecting them
 * to their dashboard instead.
 */
export function PublicOnlyRoute() {
  const { isAuthenticated, isInitialising } = useAuth()

  if (isInitialising) return <FullPageLoader />

  if (isAuthenticated) return <Navigate to="/dashboard" replace />

  return <Outlet />
}