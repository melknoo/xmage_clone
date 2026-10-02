import { useEffect, useMemo, useState } from 'react'
import { api, cardImageUrl } from '../api/client'
import { ColorPips } from '../lib/mana'
import { masteryLevel } from './DecksScreen'

interface Overview {
  totals: { games: number; wins: number; avgPlace: number | null; avgTurns: number | null; avgDurationMs: number | null; avgMulligans: number | null; gamesWithMulligan: number; xp: number }
  byTempo: { tempo: string; games: number; wins: number; avgPlace: number }[]
  byMulligans: { mulligans: number; games: number; wins: number }[]
  opponents: { commander: string; games: number; humanWins: number; avgPlace: number }[]
  recentPlaces: { placement: number; result: string; endedAt: number }[]
}

interface DeckStat {
  deckId: number
  deckName: string
  commander: string
  games: number
  wins: number
  avgPlace: number
  avgTurns: number
  avgMulligans: number
  lastPlayed: number
  masteryXp?: number
  colors?: string
  commanderSet?: string
  commanderNum?: string
}

interface CardStat {
  name: string
  opening: number
  drawn: number
  gamesCast: number
  cast: number
  avgFirstCastTurn: number | null
  winsWhenCast: number
  gamesSeen: number
}

interface HistoryGame {
  id: string
  endedAt: number
  durationMs: number
  turns: number
  deckName: string
  commander: string
  result: string
  placement: number
  tempo: string
  mulligans: number
  xp: number
  endReason: string
  seats: { name: string; human: number; commander: string; placement: number; eliminatedTurn?: number; life: number }[]
}

const pct = (a: number, b: number) => (b > 0 ? Math.round((a / b) * 100) : 0)
const num = (v: number | null | undefined, d = 1) => (v === null || v === undefined ? '–' : v.toFixed(d).replace('.', ','))
const TEMPO: Record<string, string> = { BLITZ: 'Blitz', NORMAL: 'Normal', BEDACHT: 'Bedacht', MAX: 'Max' }

export function StatsScreen() {
  const [ov, setOv] = useState<Overview | null>(null)
  const [decks, setDecks] = useState<DeckStat[]>([])
  const [history, setHistory] = useState<HistoryGame[]>([])
  const [deck, setDeck] = useState<DeckStat | null>(null)
  const [tab, setTab] = useState<'overview' | 'history'>('overview')

  useEffect(() => {
    api.get<Overview>('/api/stats/overview').then(setOv).catch(() => setOv(null))
    api.get<DeckStat[]>('/api/stats/decks').then(setDecks).catch(() => setDecks([]))
    api.get<HistoryGame[]>('/api/history?limit=60').then(setHistory).catch(() => setHistory([]))
  }, [])

  const t = ov?.totals
  return (
    <div className="h-full overflow-y-auto p-8 scrollbar-thin">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="font-display text-3xl font-bold tracking-wide text-gold-300">Statistik</h1>
          <p className="mt-1 text-ink-300">Wie schlagen sich deine Decks?</p>
        </div>
        <div className="flex gap-1 rounded-lg bg-ink-950/60 p-1">
          <button className={`rounded-md px-3 py-1 text-sm ${tab === 'overview' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('overview')}>
            Übersicht
          </button>
          <button className={`rounded-md px-3 py-1 text-sm ${tab === 'history' ? 'bg-gold-400 text-ink-950' : 'text-ink-300'}`} onClick={() => setTab('history')}>
            Verlauf
          </button>
        </div>
      </div>

      {!t || t.games === 0 ? (
        <div className="glass mt-8 rounded-2xl p-10 text-center text-ink-300">Noch keine Spiele – leg los, die Zahlen kommen von selbst.</div>
      ) : tab === 'overview' ? (
        <>
          <div className="mt-6 grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            <Tile label="Spiele" value={String(t.games)} />
            <Tile label="Siegquote" value={`${pct(t.wins, t.games)} %`} sub={`${t.wins} Siege`} />
            <Tile label="Ø Platz" value={num(t.avgPlace)} />
            <Tile label="Ø Züge" value={num(t.avgTurns, 0)} sub="alle Spieler" />
            <Tile label="Ø Dauer" value={t.avgDurationMs ? `${Math.round(t.avgDurationMs / 60000)} min` : '–'} />
            <Tile label="Mulligan-Quote" value={`${pct(t.gamesWithMulligan, t.games)} %`} sub={`Ø ${num(t.avgMulligans)}`} />
          </div>

          {ov.recentPlaces.length > 0 && <FormStrip places={ov.recentPlaces} />}

          <Section title="Decks">
            <div className="overflow-hidden rounded-2xl ring-1 ring-white/10">
              <table className="w-full text-sm">
                <thead className="bg-ink-900/80 text-left text-[11px] uppercase tracking-wider text-ink-400">
                  <tr>
                    <th className="px-4 py-2">Deck</th>
                    <th className="px-3 py-2 text-right">Spiele</th>
                    <th className="px-3 py-2">Siegquote</th>
                    <th className="px-3 py-2 text-right">Ø Platz</th>
                    <th className="px-3 py-2 text-right">Ø Züge</th>
                    <th className="px-3 py-2 text-right">Meisterschaft</th>
                  </tr>
                </thead>
                <tbody>
                  {decks.map((d) => {
                    const art = d.commanderSet && d.commanderNum ? cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' }) : null
                    const clickable = d.deckId > 0
                    return (
                      <tr key={`${d.deckId}-${d.deckName}`} className={`border-t border-white/5 ${clickable ? 'cursor-pointer hover:bg-white/5' : ''} ${deck?.deckId === d.deckId ? 'bg-gold-400/5' : ''}`} onClick={() => clickable && setDeck(d)}>
                        <td className="px-4 py-2">
                          <div className="flex items-center gap-3">
                            <div className="h-8 w-12 shrink-0 overflow-hidden rounded bg-ink-800">{art && <img src={art} alt="" className="h-full w-full object-cover" />}</div>
                            <div className="min-w-0">
                              <div className="truncate font-semibold text-ink-100">{d.deckName}</div>
                              <div className="flex items-center gap-1 truncate text-[11px] text-ink-400">
                                {d.colors !== undefined && d.colors !== null && <ColorPips colors={d.colors} size="sm" />}
                                {d.commander}
                                {!clickable && <span className="ml-1 text-ink-500">(vorgefertigt)</span>}
                              </div>
                            </div>
                          </div>
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums">{d.games}</td>
                        <td className="px-3 py-2">
                          <RateBar rate={pct(d.wins, d.games)} />
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums">{num(d.avgPlace)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{num(d.avgTurns, 0)}</td>
                        <td className="px-3 py-2 text-right text-arcane-400">{d.masteryXp !== undefined && d.masteryXp !== null ? `★ ${masteryLevel(d.masteryXp).level}` : '–'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            {decks.some((d) => d.deckId > 0) && !deck && <div className="mt-2 text-xs text-ink-400">Klick auf ein eigenes Deck zeigt die Kartenstatistik.</div>}
          </Section>

          {deck && <DeckCards deck={deck} onClose={() => setDeck(null)} />}

          <div className="mt-8 grid gap-6 lg:grid-cols-2">
            <Section title="Nach Bot-Tempo">
              <SimpleTable
                head={['Tempo', 'Spiele', 'Siegquote', 'Ø Platz']}
                rows={ov.byTempo.map((r) => [TEMPO[r.tempo] ?? r.tempo, r.games, <RateBar key="r" rate={pct(r.wins, r.games)} />, num(r.avgPlace)])}
              />
            </Section>
            <Section title="Nach Mulligans">
              <SimpleTable head={['Mulligans', 'Spiele', 'Siegquote']} rows={ov.byMulligans.map((r) => [r.mulligans, r.games, <RateBar key="r" rate={pct(r.wins, r.games)} />])} />
            </Section>
          </div>
          {ov.opponents.length > 0 && (
            <Section title="Häufigste Gegner">
              <SimpleTable head={['Bot-Commander', 'Spiele', 'Deine Siegquote', 'Ø Bot-Platz']} rows={ov.opponents.map((o) => [o.commander, o.games, <RateBar key="r" rate={pct(o.humanWins, o.games)} />, num(o.avgPlace)])} />
            </Section>
          )}
        </>
      ) : (
        <History games={history} />
      )}
    </div>
  )
}

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="glass rounded-2xl px-4 py-3">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-400">{label}</div>
      <div className="font-display text-2xl font-bold text-ink-100">{value}</div>
      {sub && <div className="text-xs text-ink-400">{sub}</div>}
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">{title}</h2>
      {children}
    </section>
  )
}

/** Einfarbiger Balken + Zahl (Text in Text-Farbe, nicht in Balkenfarbe). */
function RateBar({ rate }: { rate: number }) {
  return (
    <div className="flex items-center gap-2" title={`${rate} %`}>
      <div className="h-1.5 w-24 overflow-hidden rounded-full bg-ink-800">
        <div className="h-full rounded-full bg-gold-400" style={{ width: `${rate}%` }} />
      </div>
      <span className="w-10 text-right text-xs tabular-nums text-ink-200">{rate} %</span>
    </div>
  )
}

/** Letzte Platzierungen: hoeher = besser (1. Platz = voller Balken). */
function FormStrip({ places }: { places: { placement: number; result: string; endedAt: number }[] }) {
  const list = [...places].reverse()
  return (
    <div className="glass mt-4 rounded-2xl px-5 py-4">
      <div className="mb-2 flex items-baseline justify-between">
        <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-400">Form – letzte {list.length} Spiele</div>
        <div className="text-[11px] text-ink-500">Balkenhöhe = Platzierung (1. Platz = voll)</div>
      </div>
      <div className="flex h-14 items-end gap-[2px]">
        {list.map((p, i) => (
          <div key={i} className="group relative flex h-full flex-1 items-end" title={`${new Date(p.endedAt).toLocaleDateString('de-DE')}: Platz ${p.placement}`}>
            <div className={`w-full rounded-t-[4px] ${p.result === 'win' ? 'bg-gold-400' : 'bg-ink-500'} group-hover:brightness-125`} style={{ height: `${((5 - p.placement) / 4) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1 flex gap-3 text-[11px] text-ink-400">
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-gold-400" /> Sieg
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-sm bg-ink-500" /> kein Sieg
        </span>
      </div>
    </div>
  )
}

function SimpleTable({ head, rows }: { head: string[]; rows: React.ReactNode[][] }) {
  return (
    <div className="overflow-hidden rounded-2xl ring-1 ring-white/10">
      <table className="w-full text-sm">
        <thead className="bg-ink-900/80 text-left text-[11px] uppercase tracking-wider text-ink-400">
          <tr>
            {head.map((h) => (
              <th key={h} className="px-4 py-2">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-white/5">
              {r.map((c, j) => (
                <td key={j} className="px-4 py-2 tabular-nums">
                  {c}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DeckCards({ deck, onClose }: { deck: DeckStat; onClose: () => void }) {
  const [data, setData] = useState<{ games: number; cards: CardStat[]; commander: { avgFirstCastTurn?: number } } | null>(null)
  const [sort, setSort] = useState<keyof CardStat>('gamesCast')
  useEffect(() => {
    api.get<{ games: number; cards: CardStat[]; commander: { avgFirstCastTurn?: number } }>(`/api/stats/decks/${deck.deckId}/cards`).then(setData)
  }, [deck.deckId])
  const cards = useMemo(() => [...(data?.cards ?? [])].sort((a, b) => Number(b[sort] ?? 0) - Number(a[sort] ?? 0)), [data, sort])
  const games = data?.games ?? 0
  const H = ({ k, label }: { k: keyof CardStat; label: string }) => (
    <th className={`cursor-pointer px-3 py-2 text-right hover:text-ink-100 ${sort === k ? 'text-gold-300' : ''}`} onClick={() => setSort(k)}>
      {label}
    </th>
  )
  return (
    <Section title={`Karten – ${deck.deckName}`}>
      <div className="glass rounded-2xl p-4">
        <div className="mb-3 flex items-center justify-between text-sm text-ink-300">
          <span>
            {games} Spiele · Commander Ø gewirkt in Zug {num(data?.commander?.avgFirstCastTurn ?? null)}
          </span>
          <button className="btn-ghost !py-1 !text-xs" onClick={onClose}>
            Schließen
          </button>
        </div>
        <div className="max-h-[50vh] overflow-auto scrollbar-thin">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-ink-900 text-left text-[11px] uppercase tracking-wider text-ink-400">
              <tr>
                <th className="px-3 py-2">Karte</th>
                <H k="opening" label="Starthand" />
                <H k="drawn" label="Gezogen" />
                <H k="gamesCast" label="Gespielt (Spiele)" />
                <H k="avgFirstCastTurn" label="Ø erster Zug" />
                <H k="winsWhenCast" label="Siege wenn gespielt" />
              </tr>
            </thead>
            <tbody>
              {cards.map((c) => (
                <tr key={c.name} className="border-t border-white/5">
                  <td className="px-3 py-1.5 text-ink-100">{c.name}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{pct(c.opening, games)} %</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{c.drawn}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{c.gamesCast}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{num(c.avgFirstCastTurn)}</td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{c.gamesCast ? `${pct(c.winsWhenCast, c.gamesCast)} %` : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </Section>
  )
}

function History({ games }: { games: HistoryGame[] }) {
  if (games.length === 0) return <div className="glass mt-8 rounded-2xl p-10 text-center text-ink-300">Noch keine Spiele.</div>
  return (
    <div className="mt-6 flex flex-col gap-2">
      {games.map((g) => (
        <div key={g.id} className="glass flex items-center gap-4 rounded-2xl px-5 py-3">
          <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl font-display text-xl font-bold ${g.result === 'win' ? 'bg-gold-400 text-ink-950' : 'bg-ink-800 text-ink-200'}`}>{g.placement}</div>
          <div className="min-w-0 flex-1">
            <div className="truncate font-semibold">{g.deckName}</div>
            <div className="truncate text-xs text-ink-400">
              gegen {g.seats.filter((s) => !s.human).map((s) => s.commander || s.name).join(', ')}
            </div>
          </div>
          <div className="text-right text-xs text-ink-400">
            <div>{new Date(g.endedAt).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })}</div>
            <div>
              {g.turns} Züge · {Math.round(g.durationMs / 60000)} min · {TEMPO[g.tempo] ?? g.tempo}
              {g.endReason === 'concede' && ' · aufgegeben'}
            </div>
          </div>
          <div className="w-16 text-right text-sm font-semibold text-gold-300">+{g.xp} XP</div>
        </div>
      ))}
    </div>
  )
}
