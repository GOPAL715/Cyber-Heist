import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, FullPageLoader } from '@/components/ui'
import { PuzzlePanel } from '@/components/puzzle'
import { BossOutcomePanel, IntegrityBar, StageTransition } from '@/components/bosses'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { bossService } from '@/services'
import type { BossEncounter } from '@/types'

/**
 * A boss encounter in progress.
 *
 * <p>Composes the existing {@code PuzzlePanel} rather than duplicating the
 * puzzle UI: a boss phase is the same challenge component, given a different
 * header. The component holds no boss rules — it sends a puzzle id and an answer
 * and renders whatever the server says came back.
 *
 * <p>After each submission the whole encounter is re-read from the server. That
 * is what makes the integrity bar, the stage number and the outcome trustworthy:
 * none of them is ever advanced locally.
 */
export function BossEncounterPage() {
  const { authorizedRequest, isInitialising } = useAuth()
  const navigate = useNavigate()

  const [encounter, setEncounter] = useState<BossEncounter | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [transition, setTransition] = useState<string | undefined>(undefined)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const load = useCallback(async () => {
    try {
      setError(null)
      setEncounter(await authorizedRequest((token) => bossService.current(token)))
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 404) {
        // No live encounter: the board is the right place to be. A 404 is the
        // server saying there is nothing here, not a failure.
        setEncounter(null)
        return
      }
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load the encounter right now.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function fetchEncounter() {
      await load()
      if (!cancelled) setIsLoading(false)
    }

    void fetchEncounter()
    return () => {
      cancelled = true
    }
  }, [isInitialising, load])

  const handleSubmit = useCallback(
    async (answer: string) => {
      if (!encounter?.puzzle) return
      setIsSubmitting(true)
      setError(null)
      try {
        // Two fields, and nothing that could influence damage, integrity,
        // stage or reward.
        const next = await authorizedRequest((token) =>
          bossService.submitStage(token, {
            puzzleId: encounter.puzzle?.puzzleId ?? '',
            answer,
          }),
        )
        setEncounter(next)
        setTransition(next.status === 'ACTIVE' ? next.outcomeMessage : undefined)
      } catch (submitError) {
        // Reload first, then report: the reload clears any previous error, so
        // reporting before it would wipe the reason the submission failed.
        await load()
        setError(
          submitError instanceof ApiError ? submitError.message : 'That answer could not be submitted.',
        )
      } finally {
        setIsSubmitting(false)
      }
    },
    [authorizedRequest, encounter, load],
  )

  if (isLoading) return <FullPageLoader />

  if (error && !encounter) {
    return (
      <div className="py-8">
        <Alert>{error}</Alert>
      </div>
    )
  }

  if (!encounter) {
    return (
      <div className="space-y-4 py-8">
        <Alert>You are not in a boss encounter.</Alert>
        <button type="button" className="btn-secondary px-4 py-1.5 text-xs" onClick={() => navigate('/bosses')}>
          Return to boss board
        </button>
      </div>
    )
  }

  const finished = encounter.status !== 'ACTIVE'

  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <p className="text-xs uppercase tracking-[0.3em] text-slate-500">Boss encounter</p>
        <h2 className="text-2xl font-bold text-slate-100">{encounter.bossName}</h2>
        <p className="text-sm text-slate-400">
          Stage {encounter.currentStage} of {encounter.stageCount}
        </p>
      </header>

      <IntegrityBar percent={encounter.bossIntegrityPercent} />

      {error && <Alert>{error}</Alert>}
      {!error && <StageTransition message={transition} />}

      {finished ? (
        <BossOutcomePanel encounter={encounter} />
      ) : (
        encounter.puzzle && (
          <PuzzlePanel
            puzzle={encounter.puzzle}
            // Reused as-is: the panel only renders and collects, and the stage
            // name makes a correct header for a boss phase.
            missionTitle={encounter.stageName ?? `Stage ${encounter.currentStage}`}
            missionCategory={encounter.bossName}
            isSubmitting={isSubmitting}
            onSubmit={(answer) => void handleSubmit(answer)}
            onAbandon={() => navigate('/bosses')}
          />
        )
      )}
    </div>
  )
}