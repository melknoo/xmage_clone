import type { ReactNode } from 'react'
import { Icon, type IconName } from '../../lib/icons'

/**
 * Leerzustand: Icon 44 fg-4, Titel Barlow 600 30 bzw. titleSize (Versalien per CSS), ein Satz Erklaerung,
 * genau eine Primaeraktion (+ optional eine sekundaere).
 */
export function EmptyState({
  icon,
  title,
  text,
  primary,
  secondary,
  className = '',
  testId,
  titleSize = 30,
}: {
  icon: IconName
  title: ReactNode
  text: ReactNode
  primary?: ReactNode
  secondary?: ReactNode
  className?: string
  testId?: string
  /** Titelgroesse in px; 32 in Decks/Statistik-Leerzustand */
  titleSize?: 30 | 32
}) {
  return (
    <div className={`flex flex-col items-center justify-center gap-4 text-center ${className}`} data-testid={testId}>
      <Icon name={icon} size={44} className="text-fg-4" />
      <div className="font-display font-semibold uppercase leading-none tracking-[.03em] text-fg-1" style={{ fontSize: titleSize }}>
        {title}
      </div>
      <p className="m-0 max-w-[460px] text-[15px] leading-[1.5] text-fg-3">{text}</p>
      {(primary || secondary) && (
        <div className="mt-1.5 flex flex-wrap items-center justify-center gap-2.5">
          {primary}
          {secondary}
        </div>
      )}
    </div>
  )
}
