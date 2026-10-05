import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, TextField } from '@/components/ui'
import { useAuth } from '@/context/AuthContext'
import { ApiError } from '@/services/apiClient'
import {
  passwordStrength,
  validateRegister,
  type ValidationErrors,
} from '@/utils/validation'

type RegisterValues = {
  username: string
  email: string
  password: string
  confirmPassword: string
}

const STRENGTH_LABELS = ['Too weak', 'Weak', 'Fair', 'Strong', 'Excellent']

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()

  const [values, setValues] = useState<RegisterValues>({
    username: '',
    email: '',
    password: '',
    confirmPassword: '',
  })
  const [errors, setErrors] = useState<ValidationErrors<RegisterValues>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const strength = useMemo(() => passwordStrength(values.password), [values.password])

  function update(field: keyof RegisterValues, value: string) {
    setValues((previous) => ({ ...previous, [field]: value }))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError(null)

    const validationErrors = validateRegister(values)
    setErrors(validationErrors)
    if (Object.keys(validationErrors).length > 0) return

    setIsSubmitting(true)
    try {
      await register({
        username: values.username.trim(),
        email: values.email.trim(),
        password: values.password,
      })
      navigate('/dashboard', { replace: true })
    } catch (error) {
      if (error instanceof ApiError) {
        if (Object.keys(error.fieldErrors).length > 0) {
          setErrors(error.fieldErrors as ValidationErrors<RegisterValues>)
        }
        setFormError(error.message)
      } else {
        setFormError('Something went wrong. Please try again.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  const strengthColor =
    strength < 40 ? 'bg-rose-500' : strength < 70 ? 'bg-amber-400' : 'bg-lime-400'

  return (
    <div>
      <h2 className="mb-1 text-xl font-bold text-slate-100">Create your runner</h2>
      <p className="mb-6 text-sm text-slate-400">Pick an alias and get your first 100 coins.</p>

      {formError && (
        <div className="mb-4">
          <Alert>{formError}</Alert>
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <TextField
          label="Username"
          name="username"
          autoComplete="username"
          value={values.username}
          onChange={(event) => update('username', event.target.value)}
          error={errors.username}
          placeholder="shadow"
        />

        <TextField
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          value={values.email}
          onChange={(event) => update('email', event.target.value)}
          error={errors.email}
          placeholder="shadow@example.com"
        />

        <div>
          <TextField
            label="Password"
            type="password"
            name="password"
            autoComplete="new-password"
            value={values.password}
            onChange={(event) => update('password', event.target.value)}
            error={errors.password}
            placeholder="••••••••"
          />

          {values.password && !errors.password && (
            <div className="mt-2">
              <div className="h-1 w-full overflow-hidden rounded-full bg-slate-800">
                <div
                  className={`h-full transition-[width] duration-300 ${strengthColor}`}
                  style={{ width: `${strength}%` }}
                />
              </div>
              <p className="mt-1 text-xs text-slate-500">
                Strength: {STRENGTH_LABELS[Math.min(Math.floor(strength / 25), 4)]}
              </p>
            </div>
          )}
        </div>

        <TextField
          label="Confirm password"
          type="password"
          name="confirmPassword"
          autoComplete="new-password"
          value={values.confirmPassword}
          onChange={(event) => update('confirmPassword', event.target.value)}
          error={errors.confirmPassword}
          placeholder="••••••••"
        />

        <Button type="submit" isLoading={isSubmitting} fullWidth>
          Create account
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-slate-400">
        Already registered?{' '}
        <Link to="/login" className="font-semibold text-neon hover:underline">
          Login
        </Link>
      </p>
    </div>
  )
}