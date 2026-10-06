import { useCallback, useEffect, useState } from 'react'
import { Alert, FullPageLoader } from '@/components/ui'
import { SkillBranchColumn } from '@/components/skills'
import { BonusChip } from '@/components/equipment'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { skillService } from '@/services'
import type { Skill, SkillTree } from '@/types'

/**
 * The skill tree.
 *
 * <p>Reads the whole tree in one request and re-reads it after every upgrade,
 * rather than patching a skill locally. That is deliberate: a client that
 * incremented `currentLevel` itself would keep showing progress the server had
 * refused, and the point of the tree is that the server decides what is
 * available.
 *
 * <p>The unlock request carries a skill id and nothing else.
 */
export function SkillsPage() {
  const { authorizedRequest, isInitialising } = useAuth()

  const [tree, setTree] = useState<SkillTree | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [unlockingId, setUnlockingId] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setError(null)
      setTree(await authorizedRequest((token) => skillService.tree(token)))
    } catch (loadError) {
      setError(
        loadError instanceof ApiError ? loadError.message : 'Unable to load your skills right now.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [authorizedRequest])

  useEffect(() => {
    if (isInitialising) return
    let cancelled = false

    async function fetchTree() {
      await load()
      if (!cancelled) setIsLoading(false)
    }

    void fetchTree()
    return () => {
      cancelled = true
    }
  }, [isInitialising, load])

  const handleUnlock = useCallback(
    async (skill: Skill) => {
      setUnlockingId(skill.id)
      setError(null)
      setNotice(null)
      try {
        // One field goes out. The server decides cost, level and effect.
        const result = await authorizedRequest((token) => skillService.unlock(token, skill.id))
        setNotice(
          `${result.name} is now level ${result.currentLevel} of ${result.maxLevel}, for ${result.cost} point${result.cost === 1 ? '' : 's'}.`,
        )
        await load()
      } catch (unlockError) {
        setError(
          unlockError instanceof ApiError ? unlockError.message : 'That skill could not be upgraded.',
        )
      } finally {
        setUnlockingId(null)
      }
    },
    [authorizedRequest, load],
  )

  if (isLoading) return <FullPageLoader />

  if (error && !tree) {
    return (
      <div className="py-8">
        <Alert>{error}</Alert>
      </div>
    )
  }

  const unlockedCount =
    tree?.branches
      .flatMap((branch) => branch.skills)
      .filter((skill) => skill.currentLevel > 0).length ?? 0
  const skillCount = tree?.branches.flatMap((branch) => branch.skills).length ?? 0

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold text-slate-100">Skill Tree</h2>
          <p className="mt-1 text-sm text-slate-400">
            You earn one point per level. Spend them to make your next run stronger.
          </p>
        </div>

        <div className="panel px-4 py-2" aria-label="Skill points">
          <p className="text-[0.65rem] uppercase tracking-widest text-slate-500">
            Unspent points
          </p>
          <p className="text-xl font-bold tabular-nums text-neon">
            {tree?.skillPoints ?? 0}
          </p>
          <p className="text-[0.65rem] tabular-nums text-slate-500">
            {unlockedCount}/{skillCount} skills started
          </p>
        </div>
      </header>

      {error && <Alert>{error}</Alert>}
      {notice && <Alert variant="success">{notice}</Alert>}

      {/*
        The server's capped, combined totals - the same figures the reward and
        energy paths apply. Showing the uncapped per-source split here would tell
        a player they have a bonus the engine is not actually using.
      */}
      {tree && tree.effectiveBonuses.length > 0 && (
        <section
          className="panel flex flex-wrap items-center gap-2 px-4 py-3"
          aria-label="Active bonuses"
        >
          <span className="text-[0.65rem] uppercase tracking-[0.2em] text-slate-500">
            Active bonuses
          </span>
          {tree.effectiveBonuses.map((bonus) => (
            <BonusChip key={bonus.type} bonus={bonus} />
          ))}
        </section>
      )}

      {tree && Object.keys(tree.bonuses.skills).length === 0 && (
        <p className="text-xs text-slate-500">
          Your equipped gear is already active. Unlock a skill to change your payouts.
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        {tree?.branches.map((branch) => (
          <SkillBranchColumn
            key={branch.branch}
            branch={branch}
            unlockingId={unlockingId}
            onUnlock={(skill) => void handleUnlock(skill)}
          />
        ))}
      </div>
    </div>
  )
}