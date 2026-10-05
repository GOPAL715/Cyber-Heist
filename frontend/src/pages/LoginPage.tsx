import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, TextField } from '@/components/ui'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import { validateLogin, type ValidationErrors } from '@/utils/validation'

interface LocationState {
  from?: string
}

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<ValidationErrors<{ email: string; password: string }>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const redirectTo = (location.state as LocationState | null)?.from ?? '/dashboard'

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError(null)

    const validationErrors = validateLogin({ email, password })
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) return

    setIsSubmitting(true)
    try {
      await login({ email: email.trim(), password })
      navigate(redirectTo, { replace: true })
    } catch (error) {
      // Field-level messages from the backend take precedence over the generic one.
      if (error instanceof ApiError) {
        if (Object.keys(error.fieldErrors).length > 0) {
          setErrors(error.fieldErrors as typeof errors)
        }
        setFormError(error.message)
      } else {
        setFormError('Something went wrong. Please try again.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div>
      <h2 className="mb-1 text-xl font-bold text-slate-100">Jack in</h2>
      <p className="mb-6 text-sm text-slate-400">Access your hideout to continue the run.</p>

      {formError && (
        <div className="mb-4">
          <Alert>{formError}</Alert>
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={errors.email}
          placeholder="shadow@example.com"
        />

        <TextField
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.password}
          placeholder="••••••••"
        />

        <Button type="submit" isLoading={isSubmitting} fullWidth>
          Login
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-400">
        No account yet?{' '}
        <Link to="/register" className="font-semibold text-neon hover:underline">
          Create account
        </Link>
      </p>
    </div>
  )
}