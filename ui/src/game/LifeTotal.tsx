export interface LifeTotalProps {
  life: number
  /** ausgeschieden: fg-5 */
  lost?: boolean
  /** Schriftgroesse in px (layout.lifeBig bzw. layout.lifeOpp) */
  size: number
  className?: string
}

/**
 * Lebenspunkte (Barlow 600, tabellarisch, Zeilenhoehe .8). Bis 10 Leben Karmin, ausgeschieden fg-5.
 * data-life ist der Anker fuer FX (Lebens-Delta schwebt dort; FxLayer) und fuer Spielerziele.
 */
export function LifeTotal({ life, lost, size, className = '' }: LifeTotalProps) {
  const tone = lost ? 'text-fg-5' : life <= 10 ? 'text-attack' : 'text-fg-1'
  return (
    <span
      data-life
      className={`num relative shrink-0 whitespace-nowrap ${tone} ${className}`}
      style={{ fontSize: size, lineHeight: 0.8 }}
      title="Lebenspunkte"
    >
      {life}
    </span>
  )
}
