import { useEffect, useState } from 'react'
import { statsApi, type DeckStat, type HistoryGame, type StatsOverview } from '../api/stats'
import { Button, EmptyState } from '../components/ui'
import { useAuth } from '../store/auth'
import { useNav } from '../store/nav'
import { Breakdowns } from './stats/Breakdowns'
import { DeckTable } from './stats/DeckTable'
import { FormChart } from './stats/FormChart'
import { HistoryTable } from './stats/HistoryTable'
import { Kpis } from './stats/Kpis'

type Tab = 'overview' | 'history'

const TABS: { id: Tab; label: string; testId: string }[] = [
  { id: 'overview', label: 'Übersicht', testId: 'stats-tab-overview' },
  { id: 'history', label: 'Verlauf', testId: 'stats-tab-history' },
]

const HISTORY_LIMIT = 60

/** Statistik: Übersicht (KPIs, Formkurve, Tempo/Mulligans/Gegner, Deck-Tabelle) und Verlauf. */
export function StatsScreen() {
  const [tab, setTab] = useState<Tab>(() => useNav.getState().intent?.statsTab ?? 'overview')
  const intent = useNav((s) => s.intent)
  const [ov, setOv] = useState<StatsOverview | null>(null)
  const [failed, setFailed] = useState(false)
  const [decks, setDecks] = useState<DeckStat[]>([])
  const [history, setHistory] = useState<HistoryGame[]>([])
  const go = useNav((s) => s.go)
  const server = useAuth((s) => s.mode === 'server')

  // Auftrag "Verlauf" (z. B. vom Held) uebernehmen und verbrauchen
  useEffect(() => {
    if (!intent?.statsTab) return
    setTab(intent.statsTab)
    useNav.getState().consumeIntent()
  }, [intent])

  useEffect(() => {
    let alive = true
    statsApi
      .overview()
      .then((o) => alive && setOv(o))
      .catch(() => alive && setFailed(true))
    statsApi
      .decks()
      .then((d) => alive && setDecks(d))
      .catch(() => alive && setDecks([]))
    statsApi
      .history(HISTORY_LIMIT)
      .then((h) => alive && setHistory(h))
      .catch(() => alive && setHistory([]))
    return () => {
      alive = false
    }
  }, [])

  const empty = !!ov && (ov.totals.games ?? 0) === 0

  return (
    <div className="scrollbar-thin flex h-full min-h-0 flex-col gap-[18px] overflow-auto px-9 py-7 board:gap-7 board:px-16 board:py-12" data-testid="stats-screen">
      <div className="flex flex-none items-end gap-7 border-b border-line-2">
        <h1 className="m-0 pb-3 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Statistik</h1>
        <div role="tablist" className="flex items-end gap-7">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={tab === t.id}
              className="tab"
              style={{ fontSize: 16, paddingBottom: 12 }}
              data-testid={t.testId}
              onClick={() => setTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {failed && !ov && <p className="m-0 text-[14px] text-fg-3">Die Statistik konnte nicht geladen werden.</p>}

      {empty && (
        <EmptyState
          className="flex-1"
          testId="stats-empty"
          titleSize={32}
          icon="stats"
          title="Noch keine Partien"
          text="Nach der ersten Partie erscheinen hier Formkurve, Deck-Auswertung, häufigste Gegner und der Verlauf."
          primary={
            <Button variant="primary" icon="start" onClick={() => go(server ? 'solo' : 'play')}>
              Erstes Spiel
            </Button>
          }
        />
      )}

      {ov && !empty && tab === 'overview' && (
        <>
          <Kpis totals={ov.totals} />
          <div className="grid gap-12" style={{ gridTemplateColumns: 'minmax(0,1.3fr) minmax(0,1fr)' }}>
            <FormChart recent={ov.recentPlaces} />
            <Breakdowns ov={ov} />
          </div>
          {decks.length > 0 && <DeckTable decks={decks} />}
        </>
      )}

      {ov && !empty && tab === 'history' && <HistoryTable games={history} />}
    </div>
  )
}
