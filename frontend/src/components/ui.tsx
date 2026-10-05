import type { InputHTMLAttributes, ReactNode } from 'react'

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  label: string
  error?: string
  hint?: string
}

export function TextField({ label, error, hint, id, ...inputProps }: TextFieldProps) {
  const fieldId = id ?? `field-${label.toLowerCase().replace(/\s+/g, '-')}`
  const errorId = `${fieldId}-error`
  const hintId = `${fieldId}-hint`

  return (
    <div>
      <label htmlFor={fieldId} className="field-label">
        {label}
      </label>

      <input
        id={fieldId}
        // Associates the message with the input so screen readers announce it.
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : hint ? hintId : undefined}
        className={`field-input ${error ? 'field-error' : ''}`}
        {...inputProps}
      />

      {error ? (
        <p id={errorId} role="alert" className="mt-1.5 text-xs text-rose-400">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="mt-1.5 text-xs text-slate-500">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

interface AlertProps {
  children: ReactNode
  variant?: 'error' | 'success' | 'info'
}

export function Alert({ children, variant = 'error' }: AlertProps) {
  const styles = {
    error: 'border-rose-500/40 bg-rose-500/10 text-rose-300',
    success: 'border-lime-400/40 bg-lime-400/10 text-lime-300',
    info: 'border-neon/40 bg-neon/10 text-neon',
  }[variant]

  return (
    <div role="alert" className={`rounded-lg border px-3 py-2.5 text-sm ${styles}`}>
      {children}
    </div>
  )
}

interface ButtonProps {
  children: ReactNode
  onClick?: () => void
  type?: 'submit' | 'button'
  variant?: 'primary' | 'secondary'
  disabled?: boolean
  isLoading?: boolean
  fullWidth?: boolean
}

export function Button({
  children,
  onClick,
  type = 'submit',
  variant = 'primary',
  disabled = false,
  isLoading = false,
  fullWidth = false,
}: ButtonProps) {
  const isDisabled = disabled || isLoading

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={isDisabled}
      aria-busy={isLoading}
      className={`${variant === 'primary' ? 'btn-primary' : 'btn-secondary'} ${
        fullWidth ? 'w-full' : ''
      }`}
    >
      {isLoading && (
        <span
          aria-hidden="true"
          className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
        />
      )}
      {children}
    </button>
  )
}

/** Full-page spinner shown while the session is being restored. */
export function FullPageLoader() {
  return (
    <div className="flex min-h-screen items-center justify-center" role="status" aria-live="polite">
      <div className="flex flex-col items-center gap-3">
        <div
          aria-hidden="true"
          className="h-8 w-8 animate-spin rounded-full border-2 border-neon border-t-transparent"
        />
        <p className="text-xs uppercase tracking-[0.3em] text-slate-500">Establishing uplink</p>
      </div>
    </div>
  )
}