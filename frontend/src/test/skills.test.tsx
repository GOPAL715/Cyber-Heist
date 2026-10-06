import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SkillsPage } from '@/pages/SkillsPage'
import { saveSession } from '@/services/sessionStorage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'
import { skillTree, unlockResult } from './skillFixtures'

/**
 * The skill tree screen.
 *
 * <p>Button state comes from the server's `canUnlock`, so these tests assert the
 * UI defers to that verdict rather than re-deriving affordability, and that the
 * unlock request carries a skill id and nothing more.
 */

function signIn() {
  saveSession({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user,
  })
}

/** Session restore, then the tree. */
function mockTree(tree = skillTree()) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
    .mockResolvedValueOnce(jsonResponse({ success: true, data: tree }))
}

beforeEach(() => {
  vi.restoreAllMocks()
  signIn()
})

describe('SkillsPage', () => {
  it('renders the unspent point balance from the API', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    expect(await screen.findByText('Skill Tree')).toBeInTheDocument()
    expect(within(screen.getByLabelText('Skill points')).getByText('4')).toBeInTheDocument()
    // JSX splits the count across text nodes, so assert on the panel's content.
    // One skill starts already maxed in the fixture, so the count is 1 not 0.
    expect(screen.getByLabelText('Skill points')).toHaveTextContent('1/4 skills started')
  })

  it('renders all four branches', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    await screen.findByText('Skill Tree')
    for (const label of ['Speed', 'Intelligence', 'Defense', 'Network']) {
      expect(screen.getByRole('region', { name: `${label} branch` })).toBeInTheDocument()
    }
  })

  it('shows each level with the cost and effect the server sent', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    const card = await screen.findByRole('article', { name: 'Rapid Execution' })
    expect(card).toHaveTextContent('Level 0 / 5')
    expect(card).toHaveTextContent('+2% Mission Speed')
    expect(card).toHaveTextContent('+10% Mission Speed')
    expect(card).toHaveTextContent('3 points')
    expect(card).toHaveTextContent('1 point')
  })

  it('disables the upgrade button when the server says it is unavailable', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    const locked = await screen.findByRole('article', { name: 'Quick Response' })
    const button = within(locked).getByRole('button', { name: 'Locked' })
    expect(button).toBeDisabled()
    // The server's own wording, not a locally invented one.
    expect(locked).toHaveTextContent('Rapid Execution level 2 required')
  })

  it('shows a prerequisite with the progress against it', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    const locked = await screen.findByRole('article', { name: 'Quick Response' })
    const prerequisites = within(locked).getByLabelText('Quick Response prerequisites')
    expect(prerequisites).toHaveTextContent('Requires')
    expect(prerequisites).toHaveTextContent('Rapid Execution 2')
    expect(prerequisites).toHaveTextContent('(0 / 2)')
  })

  it('shows a fully upgraded skill with no cost', async () => {
    mockTree()

    renderWithProviders(<SkillsPage />)

    const maxed = await screen.findByRole('article', { name: 'Hardened Core' })
    expect(maxed).toHaveTextContent('Level 5 / 5')
    expect(maxed).toHaveTextContent('MAX')
    expect(within(maxed).getByRole('button', { name: 'Locked' })).toBeDisabled()
  })

  it('shows a loading state before the tree arrives', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockReturnValueOnce(new Promise(() => {}))

    renderWithProviders(<SkillsPage />)

    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('surfaces a backend error when the tree cannot be loaded', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: false, message: 'Tree unavailable' }, 500))

    renderWithProviders(<SkillsPage />)

    expect(await screen.findByText('Tree unavailable')).toBeInTheDocument()
  })

  it('upgrades a skill and confirms the cost the server charged', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: skillTree() }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: unlockResult }))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: skillTree({ skillPoints: 3 }) }),
      )

    renderWithProviders(<SkillsPage />)

    const card = await screen.findByRole('article', { name: 'Rapid Execution' })
    await user.click(within(card).getByRole('button', { name: 'Upgrade' }))

    expect(
      await screen.findByText(/is now level 1 of 5, for 1 point/i),
    ).toBeInTheDocument()

    // The upgrade request carried the skill id and no body at all: cost, level
    // and effect have no field a client could fill in.
    const unlockCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/unlock'))
    expect(unlockCall).toBeDefined()
    const [, options] = unlockCall as [string, RequestInit]
    expect(options.body).toBeUndefined()
    expect(options.method).toBe('POST')
    expect(String(unlockCall?.[0])).toContain(
      '/api/v1/player/skills/31111111-0000-4000-8000-000000000001/unlock',
    )
  })

  it('re-reads the tree after an upgrade rather than patching locally', async () => {
    const user = userEvent.setup()
    const upgraded = skillTree({
      skillPoints: 3,
      branches: [
        {
          branch: 'SPEED',
          skills: [
            skillTree().branches[0].skills[0],
            skillTree().branches[0].skills[1],
          ],
        },
        ...skillTree().branches.slice(1),
      ],
    })
    // The server now reports level 1 and the balance it left.
    const afterLevel1 = {
      ...upgraded,
      branches: [
        {
          branch: 'SPEED' as const,
          skills: [
            { ...skillTree().branches[0].skills[0], currentLevel: 1 },
            skillTree().branches[0].skills[1],
          ],
        },
        ...upgraded.branches.slice(1),
      ],
    }

    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: skillTree() }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: unlockResult }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: afterLevel1 }))

    renderWithProviders(<SkillsPage />)

    const card = await screen.findByRole('article', { name: 'Rapid Execution' })
    await user.click(within(card).getByRole('button', { name: 'Upgrade' }))

    await waitFor(() =>
      expect(screen.getByRole('article', { name: 'Rapid Execution' })).toHaveTextContent(
        'Level 1 / 5',
      ),
    )
    expect(within(screen.getByLabelText('Skill points')).getByText('3')).toBeInTheDocument()
    expect(screen.getByLabelText('Skill points')).toHaveTextContent('2/4 skills started')
  })

  it('shows the servers refusal when an upgrade is rejected', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }))
      .mockResolvedValueOnce(jsonResponse({ success: true, data: skillTree() }))
      .mockResolvedValueOnce(
        jsonResponse({ success: false, message: 'Not enough skill points: this costs 2' }, 400),
      )

    renderWithProviders(<SkillsPage />)

    const card = await screen.findByRole('article', { name: 'Rapid Execution' })
    await user.click(within(card).getByRole('button', { name: 'Upgrade' }))

    expect(await screen.findByText('Not enough skill points: this costs 2')).toBeInTheDocument()
    // The level did not move, because nothing was patched locally.
    expect(screen.getByRole('article', { name: 'Rapid Execution' })).toHaveTextContent(
      'Level 0 / 5',
    )
  })

  it('shows the capped effective bonuses the server reported', async () => {
    mockTree(
      skillTree({
        effectiveBonuses: [{ type: 'EXPERIENCE_BONUS', percent: 50 }],
        bonuses: {
          // Uncapped and much larger: displaying this instead would promise the
          // player a bonus the engine is not applying.
          equipment: {},
          skills: { EXPERIENCE_BONUS: 61 },
        },
      }),
    )

    renderWithProviders(<SkillsPage />)

    const bonuses = await screen.findByLabelText('Active bonuses')
    expect(bonuses).toHaveTextContent('+50% XP')
    expect(bonuses).not.toHaveTextContent('+61% XP')
  })
})