import { useLayoutEffect, useState } from 'react'
import type { Card } from '../api/types'
import { anchorOf, center, curve, type Point } from './overlayGeometry'

interface Arrow {
  key: string
  from: Point
  to: Point
}

/**
 * Zielpfeile vom Stapeleintrag zu seinen Zielen (Spieler, Permanents, andere Stapelobjekte).
 * Gezeigt fuer den gehoverten Eintrag, sonst fuer das oberste Stapelobjekt.
 */
export function TargetOverlay({ stack, focusId, seq }: { stack: Card[]; focusId: string | null; seq: number }) {
  const [arrows, setArrows] = useState<Arrow[]>([])
  const item = stack.find((c) => c.id === focusId) ?? stack[0]
  const targets = item?.targets ?? []
  const itemId = item?.id
  const key = targets.join(',')

  useLayoutEffect(() => {
    const compute = () => {
      const out: Arrow[] = []
      const from = itemId ? center(`[data-stack="${itemId}"]`) : null
      if (from) {
        for (const t of targets) {
          const to = anchorOf(t)
          if (to) out.push({ key: `${itemId}-${t}`, from, to })
        }
      }
      setArrows(out)
    }
    compute()
    // Stapel-Eintraege und Karten animieren beim Erscheinen -> nachmessen
    const t1 = window.setTimeout(compute, 250)
    const t2 = window.setTimeout(compute, 600)
    window.addEventListener('resize', compute)
    return () => {
      window.clearTimeout(t1)
      window.clearTimeout(t2)
      window.removeEventListener('resize', compute)
    }
  }, [itemId, key, seq])

  if (arrows.length === 0) return null
  // z-[15]: unter dem Stapel-Panel (z-20), damit der Pfeil dessen Text nicht verdeckt
  return (
    <svg className="pointer-events-none fixed inset-0 z-[15] h-full w-full">
      <defs>
        <marker id="arrow-target" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#e9c46a" />
        </marker>
      </defs>
      {arrows.map((a) => (
        <g key={a.key}>
          <path
            d={curve(a.from, a.to)}
            stroke="#e9c46a"
            strokeWidth={3}
            strokeOpacity={0.9}
            fill="none"
            strokeDasharray="2 6"
            strokeLinecap="round"
            markerEnd="url(#arrow-target)"
            style={{ filter: 'drop-shadow(0 0 4px #e9c46a)' }}
          />
          <circle cx={a.to.x} cy={a.to.y} r={14} fill="none" stroke="#e9c46a" strokeWidth={2} strokeOpacity={0.8} />
        </g>
      ))}
    </svg>
  )
}
