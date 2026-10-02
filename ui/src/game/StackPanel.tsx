import { AnimatePresence, motion } from 'motion/react'
import type { Card, PlayerState } from '../api/types'
import { CardView } from '../components/CardView'
import { RulesText } from '../lib/mana'
import type { Interaction } from './interaction'

export function StackPanel({ stack, players, inter, onHover }: { stack: Card[]; players: PlayerState[]; inter: Interaction; onHover: (c: Card | null) => void }) {
  const nameOf = (id?: string) => players.find((p) => p.id === id)?.name
  return (
    <AnimatePresence>
      {stack.length > 0 && (
        <motion.div
          className="glass pointer-events-auto flex max-h-full w-[300px] flex-col gap-1.5 overflow-y-auto rounded-2xl p-2 scrollbar-thin"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.95 }}
        >
          <div className="px-1 text-[11px] font-semibold uppercase tracking-wider text-ink-300">Stapel ({stack.length})</div>
          {stack.map((c, i) => (
            <motion.div
              key={c.id}
              layout
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              className={`flex gap-2 rounded-xl p-1.5 ${i === 0 ? 'bg-gold-400/10 ring-1 ring-gold-400/40' : 'bg-ink-950/40'}`}
            >
              <CardView card={c} size="sm" highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => inter.click(c.id)} />
              <div className="min-w-0 flex-1 text-[11px] leading-snug">
                <div className="truncate font-semibold text-ink-100">{c.name}</div>
                <div className="text-ink-300">
                  {c.kind === 'ability' ? 'Fähigkeit' : 'Zauber'} · {nameOf(c.controllerId) ?? ''}
                </div>
                {c.kind === 'ability' && c.rules?.[0] && (
                  <div className="mt-0.5 line-clamp-3 text-ink-200">
                    <RulesText text={c.rules[0]} />
                  </div>
                )}
              </div>
            </motion.div>
          ))}
        </motion.div>
      )}
    </AnimatePresence>
  )
}
