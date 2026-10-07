import { useEffect, useMemo, useRef, useState } from 'react'
import type { LogEntry, RichSeg } from '../api/types'
import { withSymbols } from '../lib/mana'
import { useGame, type LogFilter } from '../store/game'
import { SEAT_COLORS } from './format'

/** Routine, die der Filter "Wichtiges" ausblendet (ohne "(source: …)" = keine Folge eines Effekts) */
const ROUTINE: RegExp[] = [
  /^turn \d+ for /i,
  /^match score/i,
  /^attacked player:/i,
  /\bdraws a card$/i,
  /library is shuffled$/i,
  /\bskip attack$/i,
  /\bfrom hand onto the battlefield$/i, // Landdrop, danach kommt "plays …"
  /\bfrom stack onto the battlefield$/i, // Aufloesen nach "casts …"
  /\bfrom stack into (their|its owner's) graveyard$/i,
  /\bannounces a value of\b/i,
]

/** Objekt-Kuerzel "[3fa]" - beim Zusammenfassen gleicher Zeilen ignoriert ("creates a Scute Swarm [998] token" ×112) */
const OBJ_TAG = /\s*\[[0-9a-f]{3,}\]/gi

/** Farbe der Zug-Leiste ohne aktiven Spieler (Spielbeginn) */
const NO_SEAT = 'var(--color-line-3)'

interface Line {
  key: number
  entry: LogEntry
  same: string
  count: number
}

interface TurnGroup {
  turn: number
  active?: string
  lines: Line[]
  /** Eintraege nach Filter (eingeklappte Gruppe zeigt diese Zahl) */
  shown: number
}

function plainOf(e: LogEntry): string {
  return e.rich.map((r) => r.text ?? (r.br ? ' ' : '')).join('').trim()
}

function filterOf(f: LogFilter, plain: string): boolean {
  return f === 'all' || !ROUTINE.some((r) => r.test(plain))
}

/**
 * Spielverlauf nach Zuegen gruppiert; Filter aus store.logFilter (Wichtiges/Alles).
 * Gruppen beginnen mit einer 3x12-Leiste in Platzfarbe, die letzten zwei sind offen, eingeklappte zeigen die Anzahl.
 * Neue Zeilen erscheinen ohne Animation; die Liste haftet unten, solange man nicht hochscrollt.
 */
export function LogPanel() {
  const filter = useGame((s) => s.logFilter)
  const log = useGame((s) => s.log)
  const players = useGame((s) => s.state?.players)
  const spectator = useGame((s) => s.spectator)
  const setHover = useGame((s) => s.setHover)
  const ref = useRef<HTMLDivElement>(null)
  const [stick, setStick] = useState(true)
  /** manuell auf-/zugeklappte Zuege */
  const [toggled, setToggled] = useState<Record<number, boolean>>({})

  const seats = useMemo(() => {
    const m = new Map<string, { color: string; label: string }>()
    ;(players ?? []).forEach((p, i) => m.set(p.name, { color: SEAT_COLORS[i % SEAT_COLORS.length], label: p.me && !spectator ? 'Du' : p.name }))
    return m
  }, [players, spectator])

  const groups = useMemo(() => {
    const out: TurnGroup[] = []
    let started = false
    log.forEach((entry, i) => {
      // Mischen/Mulligan vor dem ersten Zug: eigene Gruppe "Spielbeginn"
      if (entry.active) started = true
      const turn = started || entry.turn > 1 ? entry.turn : 0
      let g = out[out.length - 1]
      if (!g || g.turn !== turn) {
        g = { turn, active: entry.active, lines: [], shown: 0 }
        out.push(g)
      }
      g.active ??= entry.active
      const plain = plainOf(entry)
      if (!filterOf(filter, plain)) return
      g.shown++
      const same = plain.replace(OBJ_TAG, '')
      const last = g.lines[g.lines.length - 1]
      if (last && last.same === same) last.count++
      else g.lines.push({ key: i, entry, same, count: 1 })
    })
    return out
  }, [log, filter])

  const lastTurn = groups[groups.length - 1]?.turn ?? 0
  const lastTwo = new Set(groups.slice(-2).map((g) => g.turn))
  const isOpen = (t: number) => toggled[t] ?? lastTwo.has(t)

  useEffect(() => {
    if (stick && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [groups, stick, toggled])

  const onObject = (id: string) => {
    const c = useGame.getState().objects.get(id)
    if (c) setHover(c)
  }

  return (
    <div
      ref={ref}
      className="scrollbar-thin flex h-full flex-col overflow-y-auto"
      onScroll={(e) => {
        const el = e.currentTarget
        setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
      }}
    >
      {groups.length === 0 && <div className="py-2 text-[12.5px] text-fg-4">Noch keine Einträge.</div>}
      {groups.map((g) => {
        const open = isOpen(g.turn)
        const seat = g.active ? seats.get(g.active) : undefined
        const title = g.turn > 0 ? `Zug ${g.turn}${g.active ? ` · ${seat?.label ?? g.active}` : ''}` : 'Spielbeginn'
        const bar = <span className="h-3 w-[3px] shrink-0" style={{ background: seat?.color ?? NO_SEAT }} />
        if (!open) {
          return (
            <button
              key={g.turn}
              type="button"
              className="flex w-full shrink-0 items-center gap-2 border-b border-line-1 py-1.5 text-left text-[12.5px] text-fg-3 transition-colors duration-1 hover:text-fg-2"
              title="Aufklappen"
              onClick={() => setToggled({ ...toggled, [g.turn]: true })}
            >
              {bar}
              <span className="min-w-0 truncate">{title}</span>
              <span className="flex-1" />
              <span className="num text-[13px]">{g.shown}</span>
            </button>
          )
        }
        return (
          <div key={g.turn} className="flex shrink-0 flex-col gap-[5px] border-b border-line-1 py-2">
            <button
              type="button"
              className="flex w-full items-center gap-2 text-left text-[12.5px] font-semibold text-fg-1"
              title={g.turn === lastTurn ? undefined : 'Einklappen'}
              onClick={() => setToggled({ ...toggled, [g.turn]: false })}
            >
              {bar}
              <span className="min-w-0 truncate">{title}</span>
            </button>
            {g.lines.map((l) => (
              <LogLine key={l.key} line={l} onObject={onObject} />
            ))}
            {g.lines.length === 0 && <div className="ml-[11px] text-[12.5px] text-fg-4">Nur Routine</div>}
          </div>
        )
      })}
    </div>
  )
}

function LogLine({ line, onObject }: { line: Line; onObject: (id: string) => void }) {
  const status = line.entry.kind === 'STATUS'
  return (
    <div className={`ml-[11px] text-[13px] leading-[1.4] [overflow-wrap:anywhere] ${status ? 'text-fg-3' : 'text-fg-2'}`}>
      <LogRich segs={line.entry.rich} onObject={onObject} />
      {line.count > 1 && (
        <span className="chip-count ml-1.5 align-[1px]" style={{ padding: '1px 4px', fontSize: 11 }}>
          ×{line.count}
        </span>
      )}
    </div>
  )
}

/** Engine-Text des Verlaufs: Kartennamen fg-1/600 (Hover zeigt die Karte gross), Symbole als Manasymbole. */
function LogRich({ segs, onObject }: { segs: RichSeg[]; onObject: (id: string) => void }) {
  return (
    <>
      {segs.map((s, i) => {
        if (s.br) return <br key={i} />
        const content = withSymbols(s.text ?? '', `r${i}`)
        if (s.obj) {
          const id = s.obj
          return (
            <span key={i} data-ref={id} className="cursor-help font-semibold text-fg-1 decoration-line-4 underline-offset-2 hover:underline" onMouseEnter={() => onObject(id)}>
              {content}
            </span>
          )
        }
        return (
          <span key={i} className={s.b ? 'font-semibold' : s.i ? 'italic' : undefined}>
            {content}
          </span>
        )
      })}
    </>
  )
}
