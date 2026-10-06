import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { MissionCard, MissionFilters } from '@/components/missions'
import { PuzzlePanel, PuzzleResultOverlay } from '@/components/puzzle'
import { OUTCOME_HEADLINES } from '@/components/puzzleConstants'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { missionService } from '@/services'
import type {
  Mission,
  MissionCategory,
  MissionDifficulty,
  MissionStart,
  PuzzleSubmission,
} from '@/types'

/**
 * The mission board and the puzzle loop that hangs off it.
 *
 * <p>The flow is board → start → puzzle → submit → result. This component owns
 * that sequencing and nothing else: it asks the server to start a mission,
 * shows the challenge it returns, forwards the player's answer, and renders
 * whatever verdict comes back.
 *
 * <p>It deliberately holds no rules. There is no client-side answer checking,
 * no score, no reward arithmetic and no "did I win" flag - the only input it
 * ever sends with a submission is the puzzle id and the answer itself. That is
 * what makes a tampered client worthless: there is nothing to tamper with.
 */
export function MissionBoard({ onPlayerUpdated }: { onPlayerUpdated: () => void }) {
  const { authorizedRequest, isInitialising } = useAuth()

  const [missions, setMissions] = useState<Mission[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [busyMissionId, setBusyMissionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  /** The mission whose puzzle is open, with the start response that produced it. */
  const [active, setActive] = useState<MissionStart | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [result, setResult] = useState<PuzzleSubmission | null>(null)

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

  async function startMission(missionId: string) {
    setBusyMissionId(missionId)
    setError(null)
    try {
      // One call does the charging and the generating, so the player can never
      // be charged without receiving a challenge.
      const start = await authorizedRequest((token) => missionService.start(token, missionId))
      setActive(start)
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

  const handleStart = useCallback(
    (mission: Mission) => startMission(mission.id),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [authorizedRequest],
  )

  async function handleSubmit(answer: string) {
    if (!active) return
    setIsSubmitting(true)
    setError(null)
    try {
      const submission = await authorizedRequest((token) =>
        missionService.submitPuzzle(token, active.id, active.puzzle.puzzleId, answer),
      )
      setResult(submission)
      setActive(null)
      await loadMissions()
      onPlayerUpdated()
    } catch (submitError) {
      setError(
        submitError instanceof ApiError
          ? submitError.message
          : 'Unable to submit your answer.',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  /**
 * Restarts the failed mission, which costs energy and issues a new puzzle.
 *
 * <p>Goes by the mission id from the result rather than looking the mission up
 * in the board: the list is reloaded after every submission, and a mission that
 * has just been started may have moved under a different filter, so the card is
 * not reliably still on screen.
 */
  async function handleRetry() {
    if (!result) return
    setResult(null)
    await startMission(result.mission.id)
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

  // The puzzle screen replaces the board rather than sitting beside it: it is a
  // focused task, and the cards behind it would invite a second click while a
  // challenge is already open.
  if (active) {
    return (
      <section className="space-y-4" aria-label="Mission board">
        <h3 className="text-xs uppercase tracking-[0.3em] text-slate-500">Mission board</h3>
        {error && <Alert>{error}</Alert>}
        <PuzzlePanel
          puzzle={active.puzzle}
          missionTitle={active.title}
          missionCategory={active.category}
          isSubmitting={isSubmitting}
          onSubmit={handleSubmit}
          onAbandon={() => setActive(null)}
        />
      </section>
    )
  }

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
            />
          ))}
        </div>
      )}

      {result && (
        <PuzzleResultOverlay
          outcome={result.outcome}
          headline={
            result.alreadySolved && result.outcome === 'SOLVED'
              ? 'ALREADY CLAIMED'
              : OUTCOME_HEADLINES[result.outcome]
          }
          missionTitle={result.mission.title}
          message={result.message}
          rewards={result.rewards}
          leveledUp={result.progression.leveledUp && !result.alreadySolved}
          levelBefore={result.progression.levelBefore}
          levelAfter={result.progression.levelAfter}
          canRetry={result.canRetry}
          onPrimaryAction={() => {
            const wantsRetry = result.canRetry && !result.alreadySolved
            setResult(null)
            if (wantsRetry) void handleRetry()
          }}
        />
      )}
    </section>
  )
}