import { Outlet } from 'react-router-dom'

/** Shared frame for the sign-in and sign-up screens. */
export function AuthLayout() {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4 py-12">
      {/* Decorative grid, purely presentational. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 opacity-[0.06]"
        style={{
          backgroundImage:
            'linear-gradient(rgba(0,240,255,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(0,240,255,0.5) 1px, transparent 1px)',
          backgroundSize: '48px 48px',
        }}
      />

      <main className="relative w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="neon-text animate-flicker text-3xl font-bold tracking-[0.35em]">
            CYBER HEIST
          </h1>
          <p className="mt-2 text-xs uppercase tracking-[0.3em] text-slate-500">
            Jack in · Start your run
          </p>
        </div>

        <div className="panel p-6 shadow-neon sm:p-8">
          <Outlet />
        </div>
      </main>
    </div>
  )
}