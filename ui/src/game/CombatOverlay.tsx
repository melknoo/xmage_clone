import { useLayoutEffect, useState } from 'react'
import type { CombatGroup } from '../api/types'
import { center, curve } from './overlayGeometry'

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
      for (const g of combat) {
        const def = center(`[data-player="${g.defenderId}"] [data-life]`) ?? center(`[data-player="${g.defenderId}"]`) ?? center(`[data-obj="${g.defenderId}"]`)
        for (const a of g.attackers) {
          const from = center(`[data-obj="${a}"]`)
          if (from && def) out.push({ key: `a${a}`, x1: from.x, y1: from.y, x2: def.x, y2: def.y, kind: 'attack' })
          for (const b of g.blockers) {
            const bl = center(`[data-obj="${b}"]`)
            if (bl && from) out.push({ key: `b${b}${a}`, x1: bl.x, y1: bl.y, x2: from.x, y2: from.y, kind: 'block' })
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
