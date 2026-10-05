import { describe, expect, it } from 'vitest'
import {
  passwordStrength,
  validateLogin,
  validateRegister,
} from '@/utils/validation'

describe('validateRegister', () => {
  const valid = {
    username: 'shadow',
    email: 'shadow@example.com',
    password: 'Str0ng!Passw0rd',
    confirmPassword: 'Str0ng!Passw0rd',
  }

  it('accepts a valid registration', () => {
    expect(validateRegister(valid)).toEqual({})
  })

  it('requires every field', () => {
    const errors = validateRegister({ username: '', email: '', password: '', confirmPassword: '' })
    expect(errors.username).toBeDefined()
    expect(errors.email).toBeDefined()
    expect(errors.password).toBeDefined()
    expect(errors.confirmPassword).toBeDefined()
  })

  it('rejects usernames that are too short or contain invalid characters', () => {
    expect(validateRegister({ ...valid, username: 'ab' }).username).toBeDefined()
    expect(validateRegister({ ...valid, username: 'bad name!' }).username).toBeDefined()
  })

  it('rejects malformed emails', () => {
    expect(validateRegister({ ...valid, email: 'not-an-email' }).email).toBeDefined()
  })

  it('rejects weak passwords', () => {
    expect(validateRegister({ ...valid, password: 'short' }).password).toBeDefined()
    expect(validateRegister({ ...valid, password: 'alllowercase1!' }).password).toBeDefined()
  })

  it('rejects a mismatched confirmation', () => {
    const errors = validateRegister({ ...valid, confirmPassword: 'Different1!' })
    expect(errors.confirmPassword).toBe('Passwords do not match')
  })
})

describe('validateLogin', () => {
  it('accepts credentials with no validation errors', () => {
    expect(validateLogin({ email: 'shadow@example.com', password: 'anything' })).toEqual({})
  })

  it('requires both fields', () => {
    expect(validateLogin({ email: '', password: '' })).toEqual({
      email: 'Email is required',
      password: 'Password is required',
    })
  })
})

describe('passwordStrength', () => {
  it('scores an empty password as zero', () => {
    expect(passwordStrength('')).toBe(0)
  })

  it('scores a strong password higher than a weak one', () => {
    expect(passwordStrength('Str0ng!Passw0rd')).toBeGreaterThan(passwordStrength('password'))
  })
})