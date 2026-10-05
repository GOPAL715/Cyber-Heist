import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { MissionCard, MissionFilters } from '@/components/missions'
import { MissionCompleteOverlay } from '@/components/MissionCompleteOverlay'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { missionService } from '@/services'
import type { Mission, MissionCategory, MissionCompletion, MissionDifficulty } from '@/types'

/**
 * The mission board.
 *
 * <p>Holds no game rules of its own: it asks the server to start or complete a
 * mission and renders whatever comes back. Filters are applied to the already
 * fetched list so switching tabs is instant and costs no request.
 */
export function MissionBoard({ onPlayerUpdated }: { onPlayerUpdated: () => void }) {
  const { authorizedRequest, isInitialising } = useAuth()

  const [missions, setMissions] = useState<Mission[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [busyMissionId, setBusyMissionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<MissionCompletion | null>(null)

  const [category, setCategory] = useState<MissionCategory | 'ALL'>('ALL')
  const [difficulty, setDifficulty] = useState<MissionDifficulty | 'ALL'>('ALL')

  const loadMissions = useCallback(async () => {
    setError(null)
    try {
      const data = await authorizedRequest((token) => missionService.list(token))
      setMissions(data)
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load missions.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    void loadMissions()
  }, [isInitialising, loadMissions])

  async function handleStart(mission: Mission) {
    setBusyMissionId(mission.id)
    setError(null)
    try {
      await authorizedRequest((token) => missionService.start(token, mission.id))
      await loadMissions()
      // Energy was spent, so the header must be refreshed too.
      onPlayerUpdated()
    } catch (startError) {
      setError(
        startError instanceof ApiError ? startError.message : 'Unable to start this mission.',
      )
    } finally {
      setBusyMissionId(null)
    }
  }

  async function handleComplete(mission: Mission) {
    setBusyMissionId(mission.id)
    setError(null)
    try {
      const completion = await authorizedRequest((token) =>
        missionService.complete(token, mission.id),
      )
      setResult(completion)
      await loadMissions()
      onPlayerUpdated()
    } catch (completeError) {
      setError(
        completeError instanceof ApiError
          ? completeError.message
          : 'Unable to complete this mission.',
      )
    } finally {
      setBusyMissionId(null)
    }
  }

  const visibleMissions = useMemo(
    () =>
      missions.filter(
        (mission) =>
          (category === 'ALL' || mission.category === category) &&
          (difficulty === 'ALL' || mission.difficulty === difficulty),
      ),
    [missions, category, difficulty],
  )

  if (isLoading) return <FullPageLoader />

  return (
    <section className="space-y-4" aria-label="Mission board">
      <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">Mission board</h3>

      <MissionFilters
        selectedCategory={category}
        selectedDifficulty={difficulty}
        onCategoryChange={setCategory}
        onDifficultyChange={setDifficulty}
        resultCount={visibleMissions.length}
      />

      {error && <Alert>{error}</Alert>}

      {visibleMissions.length === 0 ? (
        <p className="panel border-dashed p-8 text-center text-sm text-slate-500">
          No missions match these filters.
        </p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {visibleMissions.map((mission) => (
            <MissionCard
              key={mission.id}
              mission={mission}
              isBusy={busyMissionId === mission.id}
              onStart={handleStart}
              onComplete={handleComplete}
            />
          ))}
        </div>
      )}

      {result && (
        <MissionCompleteOverlay result={result} onDismiss={() => setResult(null)} />
      )}
    </section>
  )
}