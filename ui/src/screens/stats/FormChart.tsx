import type { StatsOverview } from '../../api/stats'
import { relDay } from '../../lib/format'
import { SectionLabel } from './parts'

const MAX_BARS = 9

/** Formkurve: letzte bis zu 9 Platzierungen, aelteste links. Hoehe (5 - Platz) / 4 von 82 %, 1. Platz in Ember. */
export function FormChart({ recent }: { recent: StatsOverview['recentPlaces'] }) {
  const list = recent.slice(0, MAX_BARS).reverse()
  return (
    <div className="flex min-w-0 flex-col gap-3.5" data-testid="stats-form">
      <div className="flex items-baseline justify-between gap-4">
        <SectionLabel>Formkurve · letzte {list.length} Partien</SectionLabel>
        <span className="text-[12.5px] text-fg-3">Platz pro Partie, höher ist besser</span>
      </div>
      <div className="relative flex h-[140px] items-end gap-2.5 border-b border-line-3 board:h-[200px]">
        {list.map((r, i) => {
          const p = Math.min(4, Math.max(1, r.placement || 4))
          const first = p === 1
          return (
            <div key={`${r.endedAt}-${i}`} className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1.5" title={`${relDay(r.endedAt)} · Platz ${r.placement}`}>
              <span className="num text-[16px] leading-none" style={{ color: first ? 'var(--color-ember)' : 'var(--color-fg-2)' }}>
                {r.placement}
              </span>
              <div
                className="w-full"
                style={{ height: `${((5 - p) / 4) * 82}%`, background: first ? 'var(--color-ember)' : 'var(--color-line-3)', borderRadius: '2px 2px 0 0' }}
              />
            </div>
          )
        })}
      </div>
      <div className="flex justify-between text-[12px] text-fg-4">
        <span>älter</span>
        <span>neuer</span>
      </div>
    </div>
  )
}
