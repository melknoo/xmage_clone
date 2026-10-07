import { useEffect, useMemo, useState } from 'react'
import { statsApi, type CardStat, type DeckCardStats as Data, type DeckStat } from '../../api/stats'
import { Chip } from '../../components/ui'

const COLS = 'minmax(180px,2fr) repeat(4,minmax(70px,1fr))'
const FIRST_ROWS = 15

interface Row {
  name: string
  commander: boolean
  /** Spiele mit der Karte auf der Hand (Commander: alle Spiele) */
  inHand: number
  cast: number
  /** Spielquote 0..100 oder null ("–") */
  quote: number | null
  winsWhenCast: number
}

/** Spiele auf der Hand; aeltere Engines ohne gamesInHand: Naeherung aus Starthand + gezogen */
function inHandOf(c: CardStat): number {
  if (c.gamesInHand !== undefined && c.gamesInHand !== null) return c.gamesInHand
  return Math.min(c.gamesSeen || Number.MAX_SAFE_INTEGER, (c.opening || 0) + (c.drawn || 0))
}

function buildRows(data: Data, commanders: string[]): Row[] {
  const isCmdr = (n: string) => commanders.includes(n)
  const cmdrRows = commanders.map((n) => data.cards.find((c) => c.name === n)).filter((c): c is CardStat => !!c)
  const rest = data.cards.filter((c) => !isCmdr(c.name))
  return [...cmdrRows, ...rest].map((c) => {
    const commander = isCmdr(c.name)
    const inHand = commander ? data.games : inHandOf(c)
    const quote = inHand > 0 ? Math.min(100, Math.round((c.gamesCast / inHand) * 100)) : null
    return { name: c.name, commander, inHand, cast: c.gamesCast, quote, winsWhenCast: c.winsWhenCast }
  })
}

/** Aufgeklappte Kartenstatistik eines eigenen Decks (unter der Deck-Zeile) */
export function DeckCardStats({ deck, cache, onLoaded }: { deck: DeckStat; cache: Data | undefined; onLoaded: (id: number, d: Data) => void }) {
  const [error, setError] = useState(false)
  const [all, setAll] = useState(false)
  useEffect(() => {
    if (cache) return
    let alive = true
    setError(false)
    statsApi
      .deckCards(deck.deckId)
      .then((d) => alive && onLoaded(deck.deckId, d))
      .catch(() => alive && setError(true))
    return () => {
      alive = false
    }
  }, [deck.deckId, cache, onLoaded])

  const commanders = useMemo(
    () =>
      (deck.commander ?? '')
        .split(' & ')
        .map((s) => s.trim())
        .filter(Boolean),
    [deck.commander],
  )
  const rows = useMemo(() => (cache ? buildRows(cache, commanders) : []), [cache, commanders])
  const shown = all ? rows : rows.slice(0, FIRST_ROWS)

  return (
    <div className="flex flex-col border-b border-line-2 bg-bg-2 pt-4 pr-3 pb-5 pl-[68px]" data-testid="stats-deck-cards">
      <div
        className="grid items-end gap-4 border-b border-line-3 pb-2 font-display text-[12px] font-semibold uppercase leading-none tracking-[.12em] text-fg-3"
        style={{ gridTemplateColumns: COLS }}
      >
        <span className="truncate">
          Karte · <span className="normal-case">{deck.deckName}</span>
        </span>
        <span className="text-right">Gezogen</span>
        <span className="text-right">Gespielt</span>
        <span className="text-right">Spielquote</span>
        <span className="text-right">In Siegen</span>
      </div>
      {!cache && (
        <div className="py-3 text-[13.5px] text-fg-3">{error ? 'Kartenstatistik konnte nicht geladen werden.' : 'Lade Kartenstatistik …'}</div>
      )}
      {cache && rows.length === 0 && <div className="py-3 text-[13.5px] text-fg-3">Für dieses Deck gibt es noch keine Kartendaten.</div>}
      {shown.map((r) => (
        <div
          key={r.name}
          className="grid items-center gap-4 border-b border-line-1 py-2 text-[13.5px] text-fg-1"
          style={{ gridTemplateColumns: COLS }}
          data-testid="stats-card-row"
        >
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate">{r.name}</span>
            {r.commander && (
              <Chip tone="target" style={{ padding: '2px 5px', fontSize: 11, lineHeight: 1, letterSpacing: '.08em' }}>
                Commander
              </Chip>
            )}
          </span>
          <span className="num text-right text-[16px] leading-none">{r.inHand}</span>
          <span className="num text-right text-[16px] leading-none">{r.cast}</span>
          <span className="flex items-center justify-end gap-2">
            <span className="bar-track block h-[3px] w-12">
              <span className="bar-fill block bg-fg-2" style={{ width: `${r.quote ?? 0}%` }} />
            </span>
            <span className="num w-10 text-right text-[16px] leading-none" style={{ color: r.quote === null ? 'var(--color-fg-4)' : undefined }}>
              {r.quote === null ? '–' : `${r.quote} %`}
            </span>
          </span>
          <span className="num text-right text-[16px] leading-none" style={{ color: r.winsWhenCast ? undefined : 'var(--color-fg-4)' }}>
            {r.winsWhenCast || '–'}
          </span>
        </div>
      ))}
      {rows.length > FIRST_ROWS && (
        <button
          type="button"
          className="mt-3 self-start font-display text-[13px] font-semibold uppercase tracking-[.08em] text-ember hover:text-ember-hover"
          onClick={() => setAll((v) => !v)}
          data-testid="stats-cards-all"
        >
          {all ? 'Weniger anzeigen' : `Alle ${rows.length} Karten`}
        </button>
      )}
    </div>
  )
}

export type { Data as DeckCardData }
