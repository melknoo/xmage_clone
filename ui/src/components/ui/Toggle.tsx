import type { ReactNode } from 'react'

/**
 * Schalter 34x20 (an: Ember + Knopf #121110). Natives <input type=checkbox role=switch> bleibt im DOM
 * (visuell versteckt), damit Skripte es finden.
 */
export function Toggle({
  checked,
  onChange,
  label,
  hint,
  testId,
  disabled,
  title,
  className = '',
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: ReactNode
  /** zweite Zeile unter dem Label (12.5 fg-3) */
  hint?: ReactNode
  testId?: string
  disabled?: boolean
  title?: string
  className?: string
}) {
  return (
    <label className={`flex items-center gap-3 text-[14px] ${disabled ? 'cursor-default' : 'cursor-pointer'} ${className}`} title={title}>
      <input
        type="checkbox"
        role="switch"
        className="peer sr-only"
        checked={checked}
        disabled={disabled}
        aria-checked={checked}
        data-testid={testId}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="toggle-track" aria-hidden />
      {(label || hint) && (
        <span className="flex min-w-0 flex-col gap-0.5">
          {label && <span className={checked ? 'text-fg-1' : 'text-fg-2'}>{label}</span>}
          {hint && <span className="text-body-s text-fg-3">{hint}</span>}
        </span>
      )}
    </label>
  )
}
