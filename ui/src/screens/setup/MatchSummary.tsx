import { Button } from '../../components/ui'

export interface SummaryRow {
  k: string
  v: string
}

// Platzfarben (Du, Platz 2-4); Tempo ohne Quadrat. Ausgeschrieben fuer Tailwind.
const SQUARE = ['bg-seat-1', 'bg-seat-2', 'bg-seat-3', 'bg-seat-4']

/**
 * Panel "Partie": Zusammenfassung (Du, Platz 2-4, Tempo) und "Spiel starten" (Enter).
 * Laeuft schon eine Partie, verlangt der Start einen zweiten Klick ("Laufende Partie beenden?").
 */
export function MatchSummary({ seats, tempo, busy, armed, disabled, onStart }: { seats: SummaryRow[]; tempo: string; busy: boolean; armed: boolean; disabled: boolean; onStart: () => void }) {
  const big = { height: 52, fontSize: 20, letterSpacing: '.08em' }
  return (
    <aside className="outline-panel flex flex-col gap-4 self-start p-[22px]">
      <span className="label" style={{ letterSpacing: '.14em' }}>
        Partie
      </span>
      <div className="flex flex-col">
        {[...seats.map((r, i) => ({ ...r, sq: SQUARE[i] ?? '' })), { k: 'Tempo', v: tempo, sq: '' }].map((r) => (
          <div key={r.k} className="flex min-w-0 items-center gap-2.5 border-b border-line-board py-2.5 text-[13.5px]">
            <span className={`h-2 w-2 flex-none ${r.sq}`} />
            <span className="w-[70px] flex-none text-fg-3">{r.k}</span>
            <span className="truncate font-semibold text-fg-1" title={r.v}>
              {r.v}
            </span>
          </div>
        ))}
      </div>
      {busy ? (
        <Button variant="wait" icon="thinking" className="w-full" style={big}>
          Mische Decks …
        </Button>
      ) : armed ? (
        <Button variant="dangerConfirm" kbd="Enter" testId="setup-start" data-armed="true" className="w-full" style={big} onClick={onStart}>
          Laufende Partie beenden?
        </Button>
      ) : (
        <Button variant="primary" icon="start" kbd="Enter" testId="setup-start" className="w-full" style={big} disabled={disabled} onClick={onStart}>
          Spiel starten
        </Button>
      )}
      <span className="text-[12.5px] leading-[1.45] text-fg-3">Startet mit dieser Konfiguration. Sie wird als Schnellstart auf dem Held-Screen gemerkt.</span>
    </aside>
  )
}
