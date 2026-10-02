import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Card, LogEntry, Permanent } from '../api/types'
import { CardView } from '../components/CardView'
import { ManaCost, Rich, RulesText } from '../lib/mana'
import { useGame } from '../store/game'

/** Grosse Kartenvorschau beim Hovern. */
export function ZoomPanel({ card: hovered }: { card: Card | null }) {
  const [back, setBack] = useState(false)
  // ohne Hover: oberstes Stapel-Objekt zeigen
  const stackTop = useGame((s) => s.state?.stack[0] ?? null)
  const card = hovered ?? stackTop
  useEffect(() => setBack(false), [card?.id])
  return (
    <AnimatePresence mode="wait">
      {card && (
        <motion.div
          key={card.id}
          initial={{ opacity: 0, x: 10 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.12 }}
          className="pointer-events-none flex flex-col gap-2"
        >
          <CardView card={card} size="zoom" showBack={back} upright />
          <div className="glass rounded-xl p-3 text-[12px] leading-snug">
            <div className="flex items-start justify-between gap-2">
              <div className="font-semibold text-ink-100">{card.name}</div>
              <ManaCost cost={card.manaCost} />
            </div>
            {card.typeLine && <div className="text-ink-300">{card.typeLine}</div>}
            {(card as Permanent).tapped && <div className="mt-0.5 text-[11px] font-semibold text-ink-400">↷ getappt</div>}
            {card.rules?.map((r, i) => (
              <div key={i} className="mt-1 text-ink-200">
                <RulesText text={r} />
              </div>
            ))}
            {card.back && <div className="mt-2 text-[11px] text-ink-400">Rückseite: {card.back.name}</div>}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

/** Farben pro Sitz (ich zuerst, dann Zugfolge) – auch fuer die Spielerkennung im Verlauf */
const SEAT_COLORS = ['#38e1c6', '#f5b84a', '#5cb8ff', '#c084fc']

// Reihenfolge zaehlt: erster Treffer gewinnt (XMage-Logtexte sind englisch)
const ICONS: [RegExp, string][] = [
  [/\b(concedes?|loses the game|has lost|wins the game|has won)\b|gibt auf/, '🏳'],
  [/\bmulligan/, '🔄'],
  [/\bcasts\b/, '✨'],
  [/\battacks\b|^attacker:/, '⚔'],
  [/\bblocks?\b|^blocker:/, '🛡'],
  [/\b(dies|destroyed|sacrificed?)\b|exile zone|\bexiles?\b/, '💀'],
  [/\b(loses|gains) \d+ life\b|\bdamage\b/, '♥'],
  [/\bplays\b/, '🏞'],
  [/ability triggers|\bactivates?\b/, '⚡'],
  [/\bcreates? .* token/, '✦'],
  [/\bsearches\b|\breveals?\b|\blooks at\b/, '🔍'],
  [/\bdraws?\b/, '🂠'],
]

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

export type LogFilter = 'important' | 'all'

interface Line {
  key: number
  entry: LogEntry
  plain: string
  count: number
}

interface TurnGroup {
  turn: number
  active?: string
  lines: Line[]
  total: number
}

function plainOf(e: LogEntry): string {
  return e.rich.map((r) => r.text ?? (r.br ? ' ' : '')).join('').trim()
}

export function LogPanel({ filter }: { filter: LogFilter }) {
  const log = useGame((s) => s.log)
  const objects = useGame((s) => s.objects)
  const players = useGame((s) => s.state?.players)
  const setHover = useGame((s) => s.setHover)
  const ref = useRef<HTMLDivElement>(null)
  const [stick, setStick] = useState(true)
  /** manuell auf-/zugeklappte Zuege */
  const [toggled, setToggled] = useState<Record<number, boolean>>({})

  // Spieler -> Farbe; laengere Namen zuerst, damit "Bot 1" nicht in "Bot 10" trifft
  const seats = useMemo(
    () =>
      (players ?? [])
        .map((p, i) => ({ name: p.name, color: SEAT_COLORS[i % SEAT_COLORS.length] }))
        .sort((a, b) => b.name.length - a.name.length),
    [players],
  )
  const colorOf = (text: string) => {
    let best: { idx: number; color: string } | null = null
    for (const s of seats) {
      const idx = text.indexOf(s.name)
      if (idx >= 0 && (!best || idx < best.idx)) best = { idx, color: s.color }
    }
    return best?.color
  }

  const groups = useMemo(() => {
    const out: TurnGroup[] = []
    let started = false
    log.forEach((entry, i) => {
      // Mischen/Mulligan vor dem ersten Zug: eigene Gruppe "Spielbeginn"
      if (entry.active) started = true
      const turn = started || entry.turn > 1 ? entry.turn : 0
      let g = out[out.length - 1]
      if (!g || g.turn !== turn) {
        g = { turn, active: entry.active, lines: [], total: 0 }
        out.push(g)
      }
      g.active ??= entry.active
      g.total++
      const plain = plainOf(entry)
      if (filter === 'important' && ROUTINE.some((r) => r.test(plain))) return
      const last = g.lines[g.lines.length - 1]
      if (last && last.plain === plain) last.count++
      else g.lines.push({ key: i, entry, plain, count: 1 })
    })
    return out
  }, [log, filter])

  const lastTurn = groups[groups.length - 1]?.turn ?? 0
  const isOpen = (t: number) => toggled[t] ?? t >= lastTurn - 1

  useEffect(() => {
    if (stick && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [groups, stick])

  return (
    <div
      ref={ref}
      className="h-full overflow-y-auto px-2 py-1.5 text-[12px] leading-snug scrollbar-thin"
      onScroll={(e) => {
        const el = e.currentTarget
        setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
      }}
    >
      {groups.map((g) => {
        const open = isOpen(g.turn)
        const color = g.active ? colorOf(g.active) : undefined
        return (
          <div key={g.turn} className="mb-1">
            <button
              className={`sticky top-0 z-10 flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-left text-[10px] font-semibold uppercase tracking-wider backdrop-blur ${
                g.turn === lastTurn ? 'bg-ink-700/90 text-ink-100' : 'bg-ink-850/90 text-ink-300 hover:text-ink-100'
              }`}
              onClick={() => setToggled({ ...toggled, [g.turn]: !open })}
            >
              <span className="w-2.5 text-ink-400">{open ? '▾' : '▸'}</span>
              {color && <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: color }} />}
              <span className="min-w-0 flex-1 truncate">
                {g.turn > 0 ? `Zug ${g.turn}` : 'Spielbeginn'}
                {g.active && g.turn > 0 && <span className="normal-case tracking-normal text-ink-200"> · {g.active}</span>}
              </span>
              <span className="tabular-nums font-normal text-ink-400">{g.total}</span>
            </button>
            {open && (
              <div className="mt-0.5 flex flex-col gap-px">
                {g.lines.map((l) => (
                  <LogLine key={l.key} line={l} color={colorOf(l.plain)} onObject={(id) => setHover(objects.get(id) ?? null)} />
                ))}
                {g.lines.length === 0 && <div className="px-2 py-0.5 text-[11px] italic text-ink-400">nur Routine</div>}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

function LogLine({ line, color, onObject }: { line: Line; color?: string; onObject: (id: string) => void }) {
  const lower = line.plain.toLowerCase()
  const icon = ICONS.find(([r]) => r.test(lower))?.[1]
  return (
    <div
      className={`flex items-start gap-1.5 rounded-r border-l-2 py-0.5 pl-1.5 pr-1 hover:bg-white/5 ${line.entry.kind === 'STATUS' ? 'text-gold-300/90' : 'text-ink-200'}`}
      style={{ borderColor: color ?? 'rgba(141,151,179,0.25)' }}
    >
      <span className="w-4 shrink-0 text-center text-[11px] opacity-80">{icon ?? '·'}</span>
      <span className="min-w-0 flex-1">
        <Rich segs={line.entry.rich} onObject={onObject} />
      </span>
      {line.count > 1 && <span className="shrink-0 rounded bg-ink-950/70 px-1 text-[10px] font-bold text-gold-300">×{line.count}</span>}
    </div>
  )
}

export function Toasts() {
  const toasts = useGame((s) => s.toasts)
  const dismiss = useGame((s) => s.dismissToast)
  return (
    <div className="pointer-events-none fixed left-1/2 top-4 z-[60] flex -translate-x-1/2 flex-col items-center gap-2">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className={`pointer-events-auto max-w-xl rounded-xl px-4 py-2 text-sm shadow-xl ring-1 ${t.level === 'error' ? 'bg-blood-500/90 text-white ring-blood-400' : 'glass text-ink-100'}`}
            onClick={() => dismiss(t.id)}
          >
            <Rich segs={t.rich} />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}

/** Aufgedeckte / angesehene Karten (XMage zeigt sie nur einen Moment im State) – bleiben kurz stehen. */
export function RevealPopups() {
  const reveals = useGame((s) => s.reveals)
  const dismiss = useGame((s) => s.dismissReveal)
  const setHover = useGame((s) => s.setHover)
  return (
    <div className="pointer-events-none fixed right-[336px] top-14 z-40 flex max-w-[min(560px,45vw)] flex-col items-end gap-2">
      <AnimatePresence>
        {reveals.map((r) => (
          <motion.div
            key={r.key}
            initial={{ opacity: 0, x: 20 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 20 }}
            className="glass pointer-events-auto rounded-xl p-2 shadow-2xl ring-1 ring-gold-400/40"
          >
            <div className="mb-1.5 flex items-center justify-between gap-3 px-1 text-[11px] font-semibold uppercase tracking-wider">
              <span className={r.looked ? 'text-arcane-400' : 'text-gold-300'}>
                {r.looked ? '👁 Angesehen' : '✋ Aufgedeckt'}: <span className="normal-case tracking-normal text-ink-100">{r.name}</span>
              </span>
              <button className="rounded px-1 text-ink-300 hover:bg-white/10 hover:text-white" onClick={() => dismiss(r.key)} title="Schließen">
                ✕
              </button>
            </div>
            <div className="flex flex-wrap justify-end gap-1.5">
              {r.cards.map((c) => (
                <CardView key={c.id} card={c} size="md" anchor={false} onHover={setHover} />
              ))}
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
