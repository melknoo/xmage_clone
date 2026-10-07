import { useState, type CSSProperties } from 'react'

/**
 * Quadratischer Avatar (keine runden Avatare). r3 ab 30 px, darunter r2. Ohne Bild: Initiale auf bg-4.
 * seatColor: 1-px-Kante in Platzfarbe · active: Ember-Kante, volle Deckkraft · dim: Deckkraft .45.
 */
export function Avatar({
  src,
  name,
  size,
  seatColor,
  active,
  dim,
  className = '',
  title,
  loading,
}: {
  src?: string | null
  name: string
  size: number
  seatColor?: string
  active?: boolean
  dim?: boolean
  className?: string
  title?: string
  /** 'lazy' fuer lange Listen */
  loading?: 'lazy' | 'eager'
}) {
  const [broken, setBroken] = useState<string | null>(null)
  const showImg = !!src && broken !== src
  const edge = active ? 'var(--color-ember)' : seatColor
  const style: CSSProperties = {
    width: size,
    height: size,
    borderRadius: size >= 30 ? 3 : 2,
    boxShadow: edge ? `0 0 0 1px ${edge}` : undefined,
    opacity: dim && !active ? 0.45 : undefined,
  }
  const initial = (name.trim()[0] ?? '?').toUpperCase()
  return (
    <span className={`relative inline-flex shrink-0 items-center justify-center overflow-hidden bg-bg-4 ${className}`} style={style} title={title ?? name}>
      {showImg ? (
        <img src={src!} alt="" loading={loading} draggable={false} className="h-full w-full object-cover" onError={() => setBroken(src!)} />
      ) : (
        <span className="font-display font-semibold leading-none text-fg-1" style={{ fontSize: Math.max(10, Math.round(size * 0.47)) }}>
          {initial}
        </span>
      )}
    </span>
  )
}
