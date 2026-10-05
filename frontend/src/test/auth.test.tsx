import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LoginPage } from '@/pages/LoginPage'
import { jsonResponse, renderWithProviders, tokens } from './helpers'

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('LoginPage', () => {
  it('shows validation errors for empty fields and does not call the API', async () => {
    const user = userEvent.setup()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')

    renderWithProviders(<LoginPage />)
    await user.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('submits credentials and navigates to the dashboard on success', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: true, data: tokens, message: 'Login successful' }),
    )

    renderWithProviders(<LoginPage />)
    await user.type(screen.getByLabelText(/email/i), 'shadow@example.com')
    await user.type(screen.getByLabelText(/password/i), 'Str0ng!Passw0rd')
    await user.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('Dashboard loaded')).toBeInTheDocument()
  })

  it('shows the backend error message when credentials are rejected', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ success: false, message: 'Invalid email or password' }, 401),
    )

    renderWithProviders(<LoginPage />)
    await user.type(screen.getByLabelText(/email/i), 'shadow@example.com')
    await user.type(screen.getByLabelText(/password/i), 'WrongPassword1!')
    await user.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument()
  })

  it('surfaces field-level validation errors returned by the API', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse(
        {
          success: false,
          message: 'Validation failed',
          errors: { email: 'email must be a valid email address' },
        },
        400,
      ),
    )

    renderWithProviders(<LoginPage />)
    await user.type(screen.getByLabelText(/email/i), 'nope')
    await user.type(screen.getByLabelText(/password/i), 'Str0ng!Passw0rd')
    await user.click(screen.getByRole('button', { name: /login/i }))

    expect(await screen.findByText('email must be a valid email address')).toBeInTheDocument()
  })
})