import { request } from './apiClient'
import type {
  AuthTokens,
  AuthUser,
  EquipmentLoadout,
  EquipmentSlot,
  InventoryItem,
  LoginPayload,
  Mission,
  MissionCategory,
  MissionCompletion,
  MissionStart,
  PlayerProfile,
  PurchaseResult,
  PuzzleChallenge,
  PuzzleSubmission,
  RegisterPayload,
  ShopCatalogue,
  ShopItem,
  SkillTree,
  SkillUnlockResult,
} from '@/types'

/** Registration, login, refresh and logout calls. */
export const authService = {
  register(payload: RegisterPayload): Promise<AuthUser> {
    return request<AuthUser>('/api/v1/auth/register', {
      method: 'POST',
      body: payload,
    })
  },

  login(payload: LoginPayload): Promise<AuthTokens> {
    return request<AuthTokens>('/api/v1/auth/login', {
      method: 'POST',
      body: payload,
    })
  },

  refresh(refreshToken: string): Promise<AuthTokens> {
    return request<AuthTokens>('/api/v1/auth/refresh', {
      method: 'POST',
      body: { refreshToken },
    })
  },

  logout(refreshToken: string): Promise<void> {
    return request<void>('/api/v1/auth/logout', {
      method: 'POST',
      body: { refreshToken },
    })
  },
}

/** Endpoints that act on the authenticated caller only. */
export const userService = {
  me(token: string): Promise<AuthUser> {
    return request<AuthUser>('/api/v1/users/me', { token })
  },
}

/**
 * Player endpoints.
 *
 * <p>No method accepts a user id: the backend always resolves the caller from
 * the access token, so the UI cannot request another player's data.
 */
export const playerService = {
  profile(token: string): Promise<PlayerProfile> {
    return request<PlayerProfile>('/api/v1/player/profile', { token })
  },
}

/**
 * Shop endpoints.
 *
 * <p>`purchase` is the clearest expression of the whole security model in the
 * codebase: it takes an item id and nothing else. There is no price, quantity,
 * rarity or bonus argument, so the client has no way to state what an item
 * costs or what it should pay - those come from the catalogue row the server
 * reads. Sending a body would not help either, since the endpoint declares none.
 */
export const shopService = {
  catalogue(token: string): Promise<ShopCatalogue> {
    return request<ShopCatalogue>('/api/v1/player/shop', { token })
  },

  item(token: string, itemId: string): Promise<ShopItem> {
    return request<ShopItem>(`/api/v1/player/shop/items/${itemId}`, { token })
  },

  /** Buys an item at its server-defined price. */
  purchase(token: string, itemId: string): Promise<PurchaseResult> {
    return request<PurchaseResult>(`/api/v1/player/shop/items/${itemId}/purchase`, {
      method: 'POST',
      token,
    })
  },
}

/**
 * Skill tree endpoints.
 *
 * <p>`unlock` sends a skill id and nothing else. The cost, the level reached,
 * the effect granted and whether the player can afford it are all decided by the
 * server, so there is no request shape here that could state them.
 */
export const skillService = {
  tree(token: string): Promise<SkillTree> {
    return request<SkillTree>('/api/v1/player/skills', { token })
  },

  unlock(token: string, skillId: string): Promise<SkillUnlockResult> {
    return request<SkillUnlockResult>(`/api/v1/player/skills/${skillId}/unlock`, {
      method: 'POST',
      token,
    })
  },
}

/**
 * Inventory and loadout endpoints.
 *
 * <p>Reading the inventory takes no argument beyond the token. Equipping sends
 * one field: which ownership row to put in a slot, which the server checks
 * belongs to the caller before it writes anything.
 */
export const equipmentService = {
  inventory(token: string): Promise<{ items: InventoryItem[] }> {
    return request<{ items: InventoryItem[] }>('/api/v1/player/inventory', { token })
  },

  loadout(token: string): Promise<EquipmentLoadout> {
    return request<EquipmentLoadout>('/api/v1/player/equipment', { token })
  },

  equip(token: string, slot: EquipmentSlot, inventoryItemId: string): Promise<InventoryItem> {
    return request<InventoryItem>(`/api/v1/player/equipment/${slot}`, {
      method: 'POST',
      token,
      body: { inventoryItemId },
    })
  },

  /** Empties a slot. The item stays in the inventory. */
  unequip(token: string, slot: EquipmentSlot): Promise<void> {
    return request<void>(`/api/v1/player/equipment/${slot}`, {
      method: 'DELETE',
      token,
    })
  },
}

/**
 * Mission and puzzle endpoints.
 *
 * <p>Start sends no body at all and now returns the mission plus its puzzle.
 * Submission is the only call in the game loop that sends a body, and what it
 * sends is exactly two fields: a puzzle id and the player's answer. There is no
 * method that could pass a user id, a reward amount or a success flag - the
 * server decides all three.
 */
export const missionService = {
  list(token: string, category?: MissionCategory): Promise<Mission[]> {
    const query = category ? `?category=${encodeURIComponent(category)}` : ''
    return request<Mission[]>(`/api/v1/player/missions${query}`, { token })
  },

  detail(token: string, missionId: string): Promise<Mission> {
    return request<Mission>(`/api/v1/player/missions/${missionId}`, { token })
  },

  /** Starts a mission, spends its energy and returns the generated puzzle. */
  start(token: string, missionId: string): Promise<MissionStart> {
    return request<MissionStart>(`/api/v1/player/missions/${missionId}/start`, {
      method: 'POST',
      token,
    })
  },

  /**
   * Re-reads the caller's live puzzle, so a page reload does not cost a
   * restart. The server re-derives it from the stored seed and sends no answer.
   */
  puzzle(token: string, missionId: string): Promise<PuzzleChallenge> {
    return request<PuzzleChallenge>(`/api/v1/player/missions/${missionId}/puzzle`, { token })
  },

  /**
   * Submits an answer.
   *
   * <p>The whole attack surface of the game loop: a puzzle id to look up and a
   * string to compare. Everything the response reports is computed server-side.
   */
  submitPuzzle(
    token: string,
    missionId: string,
    puzzleId: string,
    answer: string,
  ): Promise<PuzzleSubmission> {
    return request<PuzzleSubmission>(`/api/v1/player/missions/${missionId}/puzzle/submit`, {
      method: 'POST',
      token,
      body: { puzzleId, answer },
    })
  },

  /**
   * Legacy completion call, kept for Phase 2 clients.
   *
   * <p>It can no longer pay anything: only a solved puzzle completes a mission.
   */
  complete(token: string, missionId: string): Promise<MissionCompletion> {
    return request<MissionCompletion>(`/api/v1/player/missions/${missionId}/complete`, {
      method: 'POST',
      token,
    })
  },
}