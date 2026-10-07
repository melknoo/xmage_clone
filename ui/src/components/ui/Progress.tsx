import type { CSSProperties } from 'react'

export type ProgressTone = 'target' | 'attack' | 'ember' | 'fg'

const TONE_VAR: Record<ProgressTone, string> = {
  target: 'var(--color-target)',
  attack: 'var(--color-attack)',
  ember: 'var(--color-ember)',
  fg: 'var(--color-fg-2)',
}

/**
 * Balken ohne Radius, Track line-2. XP 4 px (target, `gained` = neu verdiente XP halbtransparent),
 * Meisterschaft 3 px (target), Commander-Schaden 3 px (attack), Bot rechnet 2 px (fg).
 */
export function ProgressBar({
  value,
  max,
  height,
  tone,
  gained,
  className = '',
  style,
  testId,
}: {
  value: number
  max: number
  height: 2 | 3 | 4
  tone: ProgressTone
  /** zusaetzlicher Anteil hinter value, 40 % deckend */
  gained?: number
  className?: string
  style?: CSSProperties
  testId?: string
}) {
  const pct = (n: number) => (max > 0 ? Math.max(0, Math.min(100, (n / max) * 100)) : 0)
  const base = pct(value)
  const extra = gained ? Math.max(0, pct(value + gained) - base) : 0
  const color = TONE_VAR[tone]
  return (
    <div
      className={`bar-track ${className}`}
      style={{ height, ...style }}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
      data-testid={testId}
    >
      <div className="bar-fill" style={{ width: `${base}%`, background: color }} />
      {extra > 0 && <div className="bar-fill" style={{ left: `${base}%`, width: `${extra}%`, background: `color-mix(in oklab, ${color} 40%, transparent)` }} />}
    </div>
  )
}
