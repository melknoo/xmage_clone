import { useLayoutEffect, useState } from 'react'
import type { CombatGroup } from '../api/types'
import { center, curve, objCenter, type Point } from './overlayGeometry'

interface Line {
  key: string
  x1: number
  y1: number
  x2: number
  y2: number
  kind: 'attack' | 'block'
}

/** Pfeile Angreifer -> Verteidiger und Blocker -> Angreifer (SVG ueber dem Tisch). */
export function CombatOverlay({ combat, seq }: { combat: CombatGroup[]; seq: number }) {
  const [lines, setLines] = useState<Line[]>([])
  useLayoutEffect(() => {
    const compute = () => {
      const out: Line[] = []
      // zusammengefasste Karten (×N) teilen sich einen Mittelpunkt -> jeden Pfeil nur einmal zeichnen
      const seen = new Set<string>()
      const push = (key: string, a: Point, b: Point, kind: Line['kind']) => {
        const k = `${kind}${Math.round(a.x)},${Math.round(a.y)}>${Math.round(b.x)},${Math.round(b.y)}`
        if (seen.has(k)) return
        seen.add(k)
        out.push({ key, x1: a.x, y1: a.y, x2: b.x, y2: b.y, kind })
      }
      for (const g of combat) {
        const def = center(`[data-player="${g.defenderId}"] [data-life]`) ?? center(`[data-player="${g.defenderId}"]`) ?? objCenter(g.defenderId)
        for (const a of g.attackers) {
          const from = objCenter(a)
          if (from && def) push(`a${a}`, from, def, 'attack')
          for (const b of g.blockers) {
            const bl = objCenter(b)
            if (bl && from) push(`b${b}${a}`, bl, from, 'block')
          }
        }
      }
      setLines(out)
    }
    compute()
    const t = window.setTimeout(compute, 250)
    window.addEventListener('resize', compute)
    return () => {
      window.clearTimeout(t)
      window.removeEventListener('resize', compute)
    }
  }, [combat, seq])

  if (lines.length === 0) return null
  return (
    <svg className="pointer-events-none fixed inset-0 z-30 h-full w-full">
      <defs>
        <marker id="arrow-red" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#ff6b6b" />
        </marker>
        <marker id="arrow-blue" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#5cb8ff" />
        </marker>
      </defs>
      {lines.map((l) => {
        const color = l.kind === 'attack' ? '#ff6b6b' : '#5cb8ff'
        return (
          <path
            key={l.key}
            d={curve({ x: l.x1, y: l.y1 }, { x: l.x2, y: l.y2 })}
            stroke={color}
            strokeWidth={3}
            strokeOpacity={0.85}
            fill="none"
            strokeDasharray={l.kind === 'block' ? '6 5' : undefined}
            markerEnd={`url(#arrow-${l.kind === 'attack' ? 'red' : 'blue'})`}
            style={{ filter: `drop-shadow(0 0 4px ${color})` }}
          />
        )
      })}
    </svg>
  )
}
