import type { StatsOverview } from '../../api/stats'
import { dec, hours, pct, relDay } from '../../lib/format'
import { SectionLabel } from './parts'

interface Kpi {
  id: string
  label: string
  value: string
  sub: string
  ember?: boolean
}

/** "seit Sa" / "seit heute" / "seit 12.09." (relDay ohne Uhrzeit) */
function since(ts: number | null | undefined): string {
  if (!ts) return 'Partien gesamt'
  return `seit ${relDay(ts).split(',')[0]}`
}

/** Spielzeit: unter einer Stunde Minuten, sonst h:mm */
function playTime(t: StatsOverview['totals']): { value: string; sub: string } {
  const ms = t.totalDurationMs ?? (t.avgDurationMs ? t.avgDurationMs * t.games : null)
  if (!ms || ms <= 0) return { value: '–', sub: 'Stunden gesamt' }
  const min = Math.round(ms / 60_000)
  if (min < 60) return { value: String(min), sub: 'Minuten gesamt' }
  return { value: hours(ms), sub: 'Stunden gesamt' }
}

/** 5 Kennzahlen: Spiele, Siege, Ø Platz, Ø Züge, Spielzeit */
export function Kpis({ totals }: { totals: StatsOverview['totals'] }) {
  const time = playTime(totals)
  const items: Kpi[] = [
    { id: 'games', label: 'Spiele', value: String(totals.games), sub: since(totals.firstEndedAt) },
    { id: 'wins', label: 'Siege', value: String(totals.wins ?? 0), sub: `${totals.wins ? pct(totals.wins, totals.games) : '–'} Siegquote`, ember: true },
    { id: 'place', label: 'Ø Platz', value: dec(totals.avgPlace), sub: 'von 4' },
    { id: 'turns', label: 'Ø Züge', value: dec(totals.avgTurns, 0), sub: 'pro Partie' },
    { id: 'time', label: 'Spielzeit', value: time.value, sub: time.sub },
  ]
  return (
    <div className="grid grid-cols-5 border-b border-line-2 pb-5" data-testid="stats-kpis">
      {items.map((k) => (
        <div key={k.id} className="flex min-w-0 flex-col gap-2.5 border-l border-line-2 pl-5" data-kpi={k.id}>
          <SectionLabel>{k.label}</SectionLabel>
          <span
            className="num text-[52px] leading-[.85]"
            style={{ color: k.ember ?'var(--color-ember)' : k.value === '–' ? 'var(--color-fg-4)' : 'var(--color-fg-1)' }}
          >
            {k.value}
          </span>
          <span className="truncate text-[12.5px] text-fg-3">{k.sub}</span>
        </div>
      ))}
    </div>
  )
}
