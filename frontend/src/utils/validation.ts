/**
 * Client-side validation mirroring the backend rules.
 *
 * <p>This exists to give immediate feedback, not to enforce anything: the
 * backend revalidates every field, and a caller can always bypass this file.
 */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const USERNAME_PATTERN = /^[a-zA-Z0-9_]+$/
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).+$/

export type ValidationErrors<T> = Partial<Record<keyof T, string>>

export function validateRegister(values: {
  username: string
  email: string
  password: string
  confirmPassword: string
}): ValidationErrors<typeof values> {
  const errors: ValidationErrors<typeof values> = {}

  if (!values.username.trim()) {
    errors.username = 'Username is required'
  } else if (values.username.trim().length < 3 || values.username.trim().length > 32) {
    errors.username = 'Username must be between 3 and 32 characters'
  } else if (!USERNAME_PATTERN.test(values.username.trim())) {
    errors.username = 'Only letters, numbers and underscores are allowed'
  }

  if (!values.email.trim()) {
    errors.email = 'Email is required'
  } else if (!EMAIL_PATTERN.test(values.email.trim())) {
    errors.email = 'Enter a valid email address'
  }

  if (!values.password) {
    errors.password = 'Password is required'
  } else if (values.password.length < 8) {
    errors.password = 'Password must be at least 8 characters'
  } else if (values.password.length > 72) {
    errors.password = 'Password must not exceed 72 characters'
  } else if (!PASSWORD_PATTERN.test(values.password)) {
    errors.password =
      'Include an uppercase letter, a lowercase letter, a number and a symbol'
  }

  if (!values.confirmPassword) {
    errors.confirmPassword = 'Confirm your password'
  } else if (values.confirmPassword !== values.password) {
    errors.confirmPassword = 'Passwords do not match'
  }

  return errors
}

export function validateLogin(values: {
  email: string
  password: string
}): ValidationErrors<typeof values> {
  const errors: ValidationErrors<typeof values> = {}

  if (!values.email.trim()) errors.email = 'Email is required'
  if (!values.password) errors.password = 'Password is required'

  return errors
}

/** Rough strength score used only to render the meter. */
export function passwordStrength(password: string): number {
  if (!password) return 0

  let score = 0
  if (password.length >= 8) score += 25
  if (password.length >= 12) score += 15
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 25
  if (/\d/.test(password)) score += 20
  if (/[^a-zA-Z0-9]/.test(password)) score += 15

  return Math.min(score, 100)
}