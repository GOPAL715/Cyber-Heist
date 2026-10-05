import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center px-4 text-center">
      <p className="neon-text text-6xl font-bold">404</p>
      <h1 className="mt-4 text-xl font-bold text-slate-100">Dead end</h1>
      <p className="mt-2 max-w-sm text-sm text-slate-400">
        This route is not part of the network.
      </p>
      <Link to="/dashboard" className="btn-primary mt-8">
        Back to dashboard
      </Link>
    </div>
  )
}