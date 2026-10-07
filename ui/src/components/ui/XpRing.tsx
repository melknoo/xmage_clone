import { useEffect, useRef, useState } from 'react'

/**
 * Zaehlt von `from` auf `to` hoch (ease-out kubisch), wie im Spielende-Prototyp: 1,2 s nach 0,5 s Verzoegerung.
 * Bei prefers-reduced-motion oder enabled=false steht sofort der Zielwert. Neuer Zielwert startet neu.
 */
export function useCountUp(to: number, opts?: { from?: number; duration?: number; delay?: number; enabled?: boolean }): number {
  const from = opts?.from ?? 0
  const duration = opts?.duration ?? 1200
  const delay = opts?.delay ?? 500
  const enabled = opts?.enabled ?? true
  const reduced = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  const animate = enabled && !reduced && to !== from
  const [v, setV] = useState(animate ? from : to)
  const raf = useRef(0)
  useEffect(() => {
    if (!animate) {
      setV(to)
      return
    }
    setV(from)
    let t0 = 0
    const tick = (t: number) => {
      if (!t0) t0 = t
      const k = Math.min(1, Math.max(0, (t - t0 - delay) / duration))
      setV(from + (to - from) * (1 - Math.pow(1 - k, 3)))
      if (k < 1) raf.current = requestAnimationFrame(tick)
    }
    raf.current = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf.current)
  }, [animate, from, to, duration, delay])
  return v
}

/**
 * XP-Ring: Strich 4, Track #2a2824, Bogen gelb, Start oben. 96 (Spielende, r 42) oder 112 (r 50).
 * Mitte: "Level" + Zahl (gelb nach Level-up). Zum Hochzaehlen progress/level pro Frame aus useCountUp
 * ableiten und transition={false} setzen; sonst folgt der Bogen per CSS-Transition (240 ms).
 */
export function XpRing({
  level,
  progress,
  size,
  levelUp = false,
  className = '',
  transition = true,
}: {
  level: number
  progress: number
  size: 96 | 112
  levelUp?: boolean
  className?: string
  /** false: Bogen ohne CSS-Transition (Wert wird pro Frame gesetzt) */
  transition?: boolean
}) {
  const r = size === 96 ? 42 : 50
  const c = 2 * Math.PI * r
  const p = Math.max(0, Math.min(1, Number.isFinite(progress) ? progress : 0))
  const mid = size / 2
  return (
    <div className={`relative shrink-0 ${className}`} style={{ width: size, height: size }} data-testid="xp-ring">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden>
        <circle cx={mid} cy={mid} r={r} fill="none" stroke="var(--color-line-2)" strokeWidth={4} />
        <circle
          cx={mid}
          cy={mid}
          r={r}
          fill="none"
          stroke="var(--color-target)"
          strokeWidth={4}
          strokeDasharray={c}
          strokeDashoffset={c * (1 - p)}
          transform={`rotate(-90 ${mid} ${mid})`}
          style={transition ? { transition: 'stroke-dashoffset var(--duration-3) var(--ease-out)' } : undefined}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-0.5">
        <span className="font-display font-semibold uppercase leading-none tracking-[.14em] text-fg-3" style={{ fontSize: size === 96 ? 10 : 11 }}>
          Level
        </span>
        <span className={`num ${levelUp ? 'text-target' : 'text-fg-1'}`} style={{ fontSize: size === 96 ? 38 : 44, lineHeight: 0.85 }}>
          {level}
        </span>
      </div>
    </div>
  )
}
