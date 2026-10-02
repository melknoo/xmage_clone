import { AnimatePresence, motion } from 'motion/react'
import type { Card, PlayerState, TargetRef } from '../api/types'
import { CardView } from '../components/CardView'
import { RulesText } from '../lib/mana'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

const ZONES: Record<string, string> = {
  GRAVEYARD: 'Friedhof',
  EXILED: 'Exil',
  HAND: 'Hand',
  LIBRARY: 'Bibliothek',
  COMMAND: 'Kommandozone',
}

export function StackPanel({
  stack,
  players,
  inter,
  onHover,
  focusId,
  onFocus,
}: {
  stack: Card[]
  players: PlayerState[]
  inter: Interaction
  onHover: (c: Card | null) => void
  /** Eintrag, dessen Zielpfeile gezeigt werden (sonst oberstes Objekt) */
  focusId?: string | null
  onFocus?: (id: string | null) => void
}) {
  const nameOf = (id?: string) => players.find((p) => p.id === id)?.name
  const activeId = stack.some((c) => c.id === focusId) ? focusId : stack[0]?.id
  return (
    <AnimatePresence>
      {stack.length > 0 && (
        <motion.div
          className="glass pointer-events-auto flex max-h-full w-[300px] flex-col gap-1.5 overflow-y-auto rounded-2xl p-2 scrollbar-thin"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          exit={{ opacity: 0, scale: 0.95 }}
          onMouseLeave={() => onFocus?.(null)}
        >
          <div className="px-1 text-[11px] font-semibold uppercase tracking-wider text-ink-300">Stapel ({stack.length})</div>
          {stack.map((c, i) => (
            <motion.div
              key={c.id}
              data-stack={c.id}
              layout
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              className={`flex gap-2 rounded-xl p-1.5 ${i === 0 ? 'bg-gold-400/10 ring-1 ring-gold-400/40' : 'bg-ink-950/40'} ${c.id === activeId && i !== 0 ? 'ring-1 ring-gold-400/30' : ''}`}
              onMouseEnter={() => onFocus?.(c.id)}
            >
              <CardView card={c} size="sm" highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => inter.click(c.id)} />
              <div className="min-w-0 flex-1 text-[11px] leading-snug">
                <div className="truncate font-semibold text-ink-100">{c.name}</div>
                <div className="text-ink-300">
                  {c.kind === 'ability' ? 'Fähigkeit' : 'Zauber'} · {nameOf(c.controllerId) ?? ''}
                </div>
                {c.targetRefs && c.targetRefs.length > 0 && <TargetChips refs={c.targetRefs} onHover={onHover} />}
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

function TargetChips({ refs, onHover }: { refs: TargetRef[]; onHover: (c: Card | null) => void }) {
  const objects = useGame((s) => s.objects)
  return (
    <div className="mt-0.5 flex flex-wrap items-center gap-1">
      <span className="text-gold-300">→ Ziel:</span>
      {refs.map((t) => {
        const obj = objects.get(t.id)
        const where = t.kind === 'card' && t.zone ? ZONES[t.zone] ?? t.zone.toLowerCase() : null
        const title = [t.owner && t.kind !== 'player' ? `von ${t.owner}` : null, where].filter(Boolean).join(' · ')
        return (
          <span
            key={t.id}
            className={`max-w-full truncate rounded px-1 font-semibold ring-1 ${
              t.kind === 'player' ? 'bg-arcane-500/15 text-arcane-400 ring-arcane-400/40' : 'bg-gold-400/15 text-gold-300 ring-gold-400/40'
            }`}
            title={title || undefined}
            onMouseEnter={() => obj && onHover(obj)}
            onMouseLeave={() => obj && onHover(null)}
          >
            {t.kind === 'player' ? '👤 ' : ''}
            {t.name}
            {where ? <span className="font-normal text-ink-300"> ({where})</span> : null}
          </span>
        )
      })}
    </div>
  )
}
