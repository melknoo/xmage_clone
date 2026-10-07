import type { ReactNode } from 'react'

/**
 * Auswahlzeile in Dialogen (Faehigkeit, Ersatzeffekt, Wahl). Ausgewaehlt: Ember-Toenung + Kontur.
 * index: fuehrende Ziffer als Tastenhinweis (1..9).
 */
export function OptionRow({
  index,
  selected,
  onClick,
  onDoubleClick,
  children,
  disabled,
  title,
  testId,
  className = '',
  onMouseEnter,
  onMouseLeave,
}: {
  index?: number
  selected: boolean
  onClick: () => void
  onDoubleClick?: () => void
  children: ReactNode
  disabled?: boolean
  title?: string
  testId?: string
  className?: string
  onMouseEnter?: () => void
  onMouseLeave?: () => void
}) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      className={`option-row ${className}`}
      disabled={disabled}
      title={title}
      data-testid={testId}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      {index !== undefined && <kbd className="kbd-soft shrink-0">{index}</kbd>}
      <span className="min-w-0 flex-1">{children}</span>
    </button>
  )
}
