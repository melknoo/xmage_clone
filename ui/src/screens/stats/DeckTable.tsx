import { Fragment, useCallback, useState } from 'react'
import type { DeckStat } from '../../api/stats'
import { Num, ProgressBar, TableHead, TableRow, Th } from '../../components/ui'
import { Icon } from '../../lib/icons'
import { dec, pct } from '../../lib/format'
import { mastery } from '../../lib/mastery'
import { DeckCardStats, type DeckCardData } from './DeckCardStats'
import { ArtThumb, deckArtUrl } from './parts'

const COLS = 'minmax(160px,2fr) repeat(4,minmax(56px,1fr)) minmax(140px,1.5fr) 24px'

/** Meisterschaft: Stern + Stufe (Gelb) + 3-px-Balken; vorgefertigte Decks "–" */
function MasteryCell({ d }: { d: DeckStat }) {
  if (d.masteryXp === undefined || d.masteryXp === null) {
    return (
      <span role="cell" className="num text-[15px] leading-none text-fg-4">
        –
      </span>
    )
  }
  const m = mastery(d.masteryXp)
  const level = d.masteryLevel ?? m.level
  const threshold = d.masteryNext ?? m.threshold
  return (
    <span role="cell" className="flex min-w-0 items-center gap-2.5" title={m.max ? `${m.xp} XP` : `${m.xp} / ${threshold} XP`}>
      <span className="num flex w-[34px] flex-none items-center gap-[3px] text-[15px] leading-none text-target">
        <Icon name="mastery" size={12} />
        {level}
      </span>
      <ProgressBar value={m.max ? 1 : m.into} max={m.max ? 1 : m.next} height={3} tone="target" className="flex-1" />
    </span>
  )
}

/** Deck-Tabelle; ein Klick auf ein eigenes Deck klappt die Kartenstatistik darunter auf. */
export function DeckTable({ decks }: { decks: DeckStat[] }) {
  const [open, setOpen] = useState<number | null>(null)
  const [cards, setCards] = useState<Record<number, DeckCardData>>({})
  const onLoaded = useCallback((id: number, d: DeckCardData) => setCards((c) => ({ ...c, [id]: d })), [])

  return (
    <div className="flex flex-col" role="table" data-testid="stats-decks">
      <TableHead columns={COLS}>
        <Th>Deck</Th>
        <Th num>Spiele</Th>
        <Th num>Siege</Th>
        <Th num>Quote</Th>
        <Th num>Ø Platz</Th>
        <Th>Meisterschaft</Th>
        <span />
      </TableHead>
      {decks.map((d) => {
        const own = d.deckId > 0
        const isOpen = own && open === d.deckId
        return (
          <Fragment key={`${d.deckId}:${d.deckName}`}>
            <TableRow
              columns={COLS}
              open={own ? isOpen : undefined}
              onClick={own ? () => setOpen((o) => (o === d.deckId ? null : d.deckId)) : undefined}
              testId="stats-deck-row"
            >
              <span role="cell" className="flex min-w-0 items-center gap-3" title={d.commander}>
                <ArtThumb src={deckArtUrl(d)} width={44} height={30} radius={2} />
                <span className="truncate text-[14px] font-semibold text-fg-1">{d.deckName}</span>
              </span>
              <Num value={d.games} />
              <Num value={d.wins ?? 0} />
              <Num value={d.games ? d.wins / d.games : 0} format={() => pct(d.wins, d.games)} zero="dash" />
              <Num value={d.avgPlace} format={(v) => dec(v)} zero="dash" />
              <MasteryCell d={d} />
              <span role="cell" className="flex justify-end text-fg-3">
                {own && <Icon name="chevronRight" size={16} className="transition-transform duration-2" style={{ transform: isOpen ? 'rotate(90deg)' : undefined }} />}
              </span>
            </TableRow>
            {isOpen && <DeckCardStats deck={d} cache={cards[d.deckId]} onLoaded={onLoaded} />}
          </Fragment>
        )
      })}
    </div>
  )
}
