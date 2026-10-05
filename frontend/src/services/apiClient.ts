import type { ApiErrorResponse, ApiResponse } from '@/types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/** Error thrown for any non-2xx backend response, carrying the safe message. */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }

  /** True when the caller simply needs to sign in again. */
  get isUnauthorized(): boolean {
    return this.status === 401
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** Raw access token; omitted for public endpoints. */
  token?: string | null
  signal?: AbortSignal
}

/**
 * The single place where HTTP calls are made.
 *
 * <p>Components never touch `fetch` directly, which keeps auth headers,
 * error translation and response unwrapping in one auditable place.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, token, signal } = options

  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'Unable to reach the server. Please try again.')
  }

  if (!response.ok) throw await toApiError(response)

  // 204 and empty bodies are legitimate outcomes for some endpoints.
  const text = await response.text()
  if (!text) return undefined as T

  const parsed = JSON.parse(text) as ApiResponse<T> | T
  // Unwrap the envelope when the backend used it.
  if (parsed && typeof parsed === 'object' && 'success' in parsed && 'data' in parsed) {
    return (parsed as ApiResponse<T>).data
  }
  return parsed as T
}

/** Converts a failed response into an {@link ApiError} with a safe message. */
async function toApiError(response: Response): Promise<ApiError> {
  let message = `Request failed with status ${response.status}`
  let fieldErrors: Record<string, string> = {}

  try {
    const text = await response.text()
    if (text) {
      const body = JSON.parse(text) as Partial<ApiErrorResponse>
      if (body?.message) message = body.message
      if (body?.errors) fieldErrors = body.errors
    }
  } catch {
    // Keep the generic message when the body is not our JSON envelope.
  }

  return new ApiError(response.status, message, fieldErrors)
}