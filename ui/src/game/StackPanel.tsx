import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
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

/** Gleiche Faehigkeiten (Name, Text, Controller, ohne Ziele), z.B. 100 Landfall-Trigger - werden zusammengefasst. */
function sameAbility(a: Card, b: Card): boolean {
  return (
    a.kind === 'ability' &&
    b.kind === 'ability' &&
    a.name === b.name &&
    a.controllerId === b.controllerId &&
    (a.rules?.[0] ?? '') === (b.rules?.[0] ?? '') &&
    !a.targetRefs?.length &&
    !b.targetRefs?.length
  )
}

interface StackRow {
  card: Card
  count: number
  /** Position des ersten Objekts (1 = oberstes) */
  pos: number
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
  const [collapsed, setCollapsed] = useState(false)
  const top = stack[0]
  // neues oberstes Objekt -> wieder aufklappen
  useEffect(() => {
    if (top?.id) setCollapsed(false)
  }, [top?.id])
  const who = (id?: string) => {
    const p = players.find((x) => x.id === id)
    return p ? { name: p.me ? 'Du' : p.name, me: p.me } : null
  }
  const activeId = stack.some((c) => c.id === focusId) ? focusId : top?.id
  // gleiche Objekte direkt unter dem obersten zaehlen, den Rest zu Zeilen mit ×N zusammenfassen
  let topCount = top ? 1 : 0
  while (top && topCount < stack.length && sameAbility(top, stack[topCount])) topCount++
  const rows: StackRow[] = []
  for (let i = topCount; i < stack.length; i++) {
    const last = rows[rows.length - 1]
    if (last && sameAbility(last.card, stack[i])) last.count++
    else rows.push({ card: stack[i], count: 1, pos: i + 1 })
  }

  return (
    <AnimatePresence>
      {top && collapsed && (
        <motion.button
          key="pill"
          data-stack={top.id}
          className="pointer-events-auto flex items-center gap-2 rounded-full bg-ink-900/90 px-4 py-1.5 text-sm font-semibold text-gold-300 shadow-2xl ring-2 ring-gold-400/60 backdrop-blur"
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0 }}
          onClick={() => setCollapsed(false)}
          title="Stapel aufklappen"
        >
          <span className="rounded-full bg-gold-400 px-1.5 text-xs text-ink-950">{stack.length}</span>
          Stapel · <span className="max-w-[220px] truncate text-ink-100">{top.name}</span> ▸
        </motion.button>
      )}
      {top && !collapsed && (
        <motion.div
          key="panel"
          className="pointer-events-auto flex max-h-[48vh] w-[440px] flex-col overflow-hidden rounded-2xl bg-ink-900/90 shadow-[0_12px_48px_rgba(0,0,0,0.7),0_0_36px_rgba(245,184,74,0.2)] ring-2 ring-gold-400/55 backdrop-blur-md"
          initial={{ opacity: 0, scale: 0.92, y: 10 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.95 }}
          onMouseLeave={() => onFocus?.(null)}
        >
          <div className="flex shrink-0 items-center justify-between gap-2 border-b border-gold-400/20 bg-gold-400/10 px-3 py-1.5">
            <div className="flex items-baseline gap-2">
              <span className="font-display text-sm font-bold uppercase tracking-widest text-gold-300">Stapel</span>
              <span className="rounded-full bg-gold-400 px-1.5 text-xs font-bold text-ink-950">{stack.length}</span>
              {stack.length > 1 && <span className="text-[11px] text-ink-300">oberstes löst zuerst auf</span>}
            </div>
            <button className="rounded px-1.5 text-ink-300 hover:bg-white/10 hover:text-white" onClick={() => setCollapsed(true)} title="Einklappen">
              ▾
            </button>
          </div>
          <div className="flex min-h-0 flex-col gap-1.5 overflow-y-auto p-2 scrollbar-thin">
            {/* oberstes Objekt gross */}
            <motion.div
              key={top.id}
              data-stack={top.id}
              initial={{ opacity: 0, scale: 0.94 }}
              animate={{ opacity: 1, scale: 1, boxShadow: ['0 0 0 0 rgba(245,184,74,0.7)', '0 0 0 10px rgba(245,184,74,0)'] }}
              transition={{ duration: 0.6 }}
              className={`flex gap-3 rounded-xl bg-gold-400/10 p-2 ring-1 ${activeId === top.id ? 'ring-gold-400/60' : 'ring-gold-400/30'}`}
              onMouseEnter={() => onFocus?.(top.id)}
            >
              <CardView card={top} size="lg" anchor={false} highlight={inter.highlight(top.id)} onHover={onHover} onClick={() => inter.click(top.id)} />
              <div className="min-w-0 flex-1 text-[12px] leading-snug">
                <div className="font-display text-base font-bold leading-tight text-ink-100">{top.name}</div>
                <Origin c={top} who={who(top.controllerId)} />
                {top.typeLine && top.kind !== 'ability' && <div className="mt-0.5 text-ink-300">{top.typeLine}</div>}
                {top.targetRefs && top.targetRefs.length > 0 && <TargetChips refs={top.targetRefs} onHover={onHover} />}
                {top.kind === 'ability' && top.rules?.[0] && (
                  <div className="mt-1 line-clamp-6 text-ink-200">
                    <RulesText text={top.rules[0]} />
                  </div>
                )}
                {topCount > 1 && (
                  <div className="mt-1 inline-block rounded bg-gold-400/20 px-1.5 text-[11px] font-semibold text-gold-300">+{topCount - 1} gleiche darunter</div>
                )}
              </div>
            </motion.div>
            {/* darunter liegende Objekte kompakt */}
            {rows.map(({ card: c, count, pos }) => (
              <motion.div
                key={c.id}
                data-stack={c.id}
                layout
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                className={`flex items-start gap-2 rounded-xl bg-ink-950/50 p-1.5 ${c.id === activeId ? 'ring-1 ring-gold-400/40' : ''}`}
                onMouseEnter={() => onFocus?.(c.id)}
              >
                <span className="w-4 shrink-0 pt-0.5 text-center text-[11px] font-bold tabular-nums text-ink-400">{pos}</span>
                <CardView card={c} size="sm" anchor={false} highlight={inter.highlight(c.id)} onHover={onHover} onClick={() => inter.click(c.id)} />
                <div className="min-w-0 flex-1 text-[11px] leading-snug">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate font-semibold text-ink-100">{c.name}</span>
                    {count > 1 && <span className="shrink-0 rounded bg-gold-400/20 px-1 font-bold text-gold-300">×{count}</span>}
                  </div>
                  <Origin c={c} who={who(c.controllerId)} />
                  {c.targetRefs && c.targetRefs.length > 0 && <TargetChips refs={c.targetRefs} onHover={onHover} />}
                  {c.kind === 'ability' && c.rules?.[0] && (
                    <div className="mt-0.5 line-clamp-2 text-ink-300">
                      <RulesText text={c.rules[0]} />
                    </div>
                  )}
                </div>
              </motion.div>
            ))}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}

function Origin({ c, who }: { c: Card; who: { name: string; me: boolean } | null }) {
  return (
    <div className="flex items-center gap-1.5 text-[11px]">
      <span className="text-ink-300">{c.kind === 'ability' ? 'Fähigkeit' : 'Zauber'}</span>
      {who && (
        <span className={`rounded px-1 font-semibold ${who.me ? 'bg-arcane-500/15 text-arcane-400' : 'bg-white/5 text-ink-200'}`}>{who.name}</span>
      )}
    </div>
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
