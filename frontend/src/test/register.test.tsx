import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RegisterPage } from '@/pages/RegisterPage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'

/** Fills the whole registration form with sensible values. */
async function fillForm(
  user: ReturnType<typeof userEvent.setup>,
  password = 'Str0ng!Passw0rd',
  confirm = password,
) {
  await user.type(screen.getByLabelText(/^username/i), 'shadow')
  await user.type(screen.getByLabelText(/^email/i), 'shadow@example.com')
  await user.type(screen.getByLabelText(/^password/i), password)
  await user.type(screen.getByLabelText(/confirm password/i), confirm)
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('RegisterPage', () => {
  it('blocks submission when the passwords do not match', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    renderWithProviders(<RegisterPage />)
    await fillForm(user, 'Str0ng!Passw0rd', 'Different1!')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('rejects a weak password before contacting the API', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    renderWithProviders(<RegisterPage />)
    await fillForm(user, 'weak', 'weak')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('registers, signs in and lands on the dashboard', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(jsonResponse({ success: true, data: tokens.user }, 201))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: tokens, message: 'Login successful' }),
      )

    renderWithProviders(<RegisterPage />)
    await fillForm(user)
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Dashboard loaded')).toBeInTheDocument()
  })

  it('shows a duplicate username error from the backend', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: false, message: 'Username is already taken' }, 409),
    )

    renderWithProviders(<RegisterPage />)
    await fillForm(user)
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Username is already taken')).toBeInTheDocument()
  })
})
