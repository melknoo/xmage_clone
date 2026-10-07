import type { ReactNode } from 'react'
import { Icon } from '../../lib/icons'

/** Haken 18x18 (an: Ember + Check). Natives <input type=checkbox> bleibt im DOM (visuell versteckt). */
export function Checkbox({
  checked,
  onChange,
  label,
  disabled,
  testId,
  title,
  className = '',
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: ReactNode
  disabled?: boolean
  testId?: string
  title?: string
  className?: string
}) {
  return (
    <label className={`flex items-center gap-2.5 text-[14px] text-fg-1 ${disabled ? 'cursor-default' : 'cursor-pointer'} ${className}`} title={title}>
      <input type="checkbox" className="peer sr-only" checked={checked} disabled={disabled} data-testid={testId} onChange={(e) => onChange(e.target.checked)} />
      <span className="check-box" aria-hidden>
        <Icon name="chosen" size={13} strokeWidth={2.4} />
      </span>
      <span className="min-w-0">{label}</span>
    </label>
  )
}
