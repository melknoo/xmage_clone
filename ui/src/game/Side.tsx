import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
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

export function LogPanel() {
  const log = useGame((s) => s.log)
  const objects = useGame((s) => s.objects)
  const setHover = useGame((s) => s.setHover)
  const ref = useRef<HTMLDivElement>(null)
  const [stick, setStick] = useState(true)
  useEffect(() => {
    if (stick && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [log, stick])

  let lastTurn = -1
  return (
    <div
      ref={ref}
      className="h-full overflow-y-auto px-3 py-2 text-[12px] leading-snug scrollbar-thin"
      onScroll={(e) => {
        const el = e.currentTarget
        setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
      }}
    >
      {log.map((e: LogEntry, i) => {
        const sep = e.turn !== lastTurn
        lastTurn = e.turn
        return (
          <div key={i}>
            {sep && <div className="my-1.5 border-t border-white/10 pt-1 text-[10px] font-semibold uppercase tracking-wider text-ink-400">Zug {e.turn}</div>}
            <div className={`py-0.5 ${e.kind === 'STATUS' ? 'text-gold-300/90' : 'text-ink-200'}`}>
              <Rich segs={e.rich} onObject={(id) => setHover(objects.get(id) ?? null)} />
            </div>
          </div>
        )
      })}
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
