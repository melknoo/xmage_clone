import type { HistoryGame, HistorySeat } from '../../api/stats'
import { TableHead, TableRow, Th } from '../../components/ui'
import { duration, placeColor, relDay, xp } from '../../lib/format'
import { tempoLabel } from '../../lib/tempo'
import { ArtThumb, commanderArtUrl } from './parts'

const COLS = '54px minmax(150px,1.4fr) minmax(130px,1fr) repeat(3,minmax(60px,.5fr)) 90px'

/** Alle Sitze ausser dem eigenen (eigener = Mensch mit der Platzierung des Spiels, erster Treffer). */
function opponents(g: HistoryGame): HistorySeat[] {
  const own = g.seats.findIndex((s) => !!s.human && s.placement === g.placement)
  return g.seats.filter((_, i) => i !== own)
}

/** Verlauf als Tabelle: Platz, Deck + Datum, Gegner-Avatare, Züge, Dauer, Tempo, XP */
export function HistoryTable({ games }: { games: HistoryGame[] }) {
  return (
    <div className="flex flex-col" role="table" data-testid="stats-history">
      <TableHead columns={COLS}>
        <Th>Platz</Th>
        <Th>Deck</Th>
        <Th>Gegner</Th>
        <Th num>Züge</Th>
        <Th num>Dauer</Th>
        <Th num>Tempo</Th>
        <Th num>XP</Th>
      </TableHead>
      {games.map((g) => {
        const xpText = xp(g.xp)
        const meta = relDay(g.endedAt) + (g.endReason === 'concede' ? ' · aufgegeben' : g.endReason === 'error' ? ' · abgebrochen' : '')
        return (
          <TableRow key={g.id} columns={COLS} style={{ padding: '10px 12px' }} testId="stats-history-row">
            <span role="cell" className="num text-[28px] leading-none" style={{ color: placeColor(g.placement) }}>
              {g.placement ? `${g.placement}.` : '–'}
            </span>
            <span role="cell" className="flex min-w-0 flex-col gap-[3px]">
              <span className="truncate text-[14px] font-semibold text-fg-1" title={g.commander}>
                {g.deckName}
              </span>
              <span className="truncate text-[12px] text-fg-3">{meta}</span>
            </span>
            <span role="cell" className="flex min-w-0 gap-1 overflow-hidden">
              {opponents(g).map((s, i) => (
                <ArtThumb key={`${s.name}-${i}`} src={commanderArtUrl(s.commander)} width={28} height={28} radius={2} title={s.commander || s.name} />
              ))}
            </span>
            <span role="cell" className="num text-right text-[17px] leading-none text-fg-1">
              {g.turns}
            </span>
            <span role="cell" className="num text-right text-[17px] leading-none" style={{ color: g.durationMs > 0 ? 'var(--color-fg-1)' : 'var(--color-fg-4)' }}>
              {duration(g.durationMs)}
            </span>
            <span role="cell" className="num truncate text-right text-[14px] uppercase leading-none tracking-[.06em] text-fg-2">
              {tempoLabel(g.tempo)}
            </span>
            <span
              role="cell"
              className="num text-right text-[20px] leading-none"
              style={{ color: xpText === '–' ? 'var(--color-fg-4)' : g.placement === 1 ? 'var(--color-target)' : 'var(--color-fg-2)' }}
            >
              {xpText}
            </span>
          </TableRow>
        )
      })}
    </div>
  )
}
