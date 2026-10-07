import type { CSSProperties, ReactNode } from 'react'

export interface SegmentedItem<T extends string = string> {
  id: T
  label: ReactNode
  /** Tooltip (z. B. Langtext des Tempos) */
  title?: string
  disabled?: boolean
  testId?: string
}

/**
 * inline: Kopfleiste/Pause (Barlow 13, aktiv mit Ember-Unterstrich) ·
 * boxed: Dialoge/Login/Import (Container bg-0 + Kontur, aktiv #34312c)
 */
export function Segmented<T extends string>({
  variant,
  items,
  value,
  onChange,
  disabled = false,
  className = '',
  itemStyle,
  ariaLabel,
}: {
  variant: 'inline' | 'boxed'
  items: SegmentedItem<T>[]
  value: T | null
  onChange: (id: T) => void
  disabled?: boolean
  className?: string
  /** Feinmasse je Kontext, z. B. {padding: '6px 10px'} fuer xN im Dialog */
  itemStyle?: CSSProperties
  ariaLabel?: string
}) {
  const boxed = variant === 'boxed'
  return (
    <div role="radiogroup" aria-label={ariaLabel} className={`${boxed ? 'seg-boxed' : 'seg-inline'} ${className}`}>
      {items.map((it) => (
        <button
          key={it.id}
          type="button"
          role="radio"
          aria-checked={it.id === value}
          className={boxed ? 'seg-boxed-item' : 'seg-inline-item'}
          style={itemStyle}
          title={it.title}
          data-testid={it.testId}
          disabled={disabled || it.disabled}
          onClick={() => it.id !== value && onChange(it.id)}
        >
          {it.label}
        </button>
      ))}
    </div>
  )
}
