import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState, type CSSProperties } from 'react'
import type { Card, TargetRef } from '../api/types'
import { CardView } from '../components/CardView'
import { Chip, Kbd } from '../components/ui'
import { Icon } from '../lib/icons'
import { RulesText } from '../lib/mana'
import { enter } from '../lib/motion'
import { useGame } from '../store/game'
import { SEAT_COLORS, shortName, typeLabel } from './format'
import type { Interaction } from './interaction'
import { useBoardLayout } from './layout'

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
    a.x === b.x &&
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

const NO_CARDS: Card[] = []

/** Tönung des obersten Objekts */
const TOP_TINT = 'rgba(255,122,61,.06)'

export interface StackPanelProps {
  inter: Interaction
  onHover: (c: Card | null) => void
}

/**
 * Stapel, schwebt oben rechts im eigenen Bereich (absolute right/top 14 – der Aufrufer haengt ihn direkt in den
 * relativen eigenen Bereich, nie ueber einen Gegner-Pod). Liest state.stack/players aus dem Store; die Zeile unter der
 * Maus wird store.stackFocus (ihre Ziele bekommen das Etikett "Ziel", siehe boardDecor) und erscheint im Zoom.
 */
export function StackPanel({ inter, onHover }: StackPanelProps) {
  const stack = useGame((s) => s.state?.stack ?? NO_CARDS)
  const players = useGame((s) => s.state?.players)
  const spectator = useGame((s) => s.spectator)
  const focusId = useGame((s) => s.stackFocus)
  const onFocus = useGame((s) => s.setStackFocus)
  const prompt = inter.prompt
  const { stackW } = useBoardLayout()
  const [collapsed, setCollapsed] = useState(false)
  const top = stack[0]
  // neues oberstes Objekt -> wieder aufklappen
  useEffect(() => {
    if (top?.id) setCollapsed(false)
  }, [top?.id])
  // Stapel leer -> kein Fokus mehr (Ziel-Etiketten wieder fuer alle)
  useEffect(() => {
    if (!top) onFocus(null)
  }, [top, onFocus])

  const who = (id?: string) => {
    const i = players?.findIndex((x) => x.id === id) ?? -1
    if (i < 0 || !players) return null
    const p = players[i]
    return { name: p.me && !spectator ? 'Du' : shortName(p.name), color: SEAT_COLORS[i % SEAT_COLORS.length] }
  }

  // gleiche Objekte direkt untereinander zu einer Zeile mit ×N zusammenfassen
  const rows: StackRow[] = []
  stack.forEach((c, i) => {
    const last = rows[rows.length - 1]
    if (last && sameAbility(last.card, c)) last.count++
    else rows.push({ card: c, count: 1, pos: i + 1 })
  })

  // Wird gerade gewirkt (Ziel waehlen / bezahlen fuer ein Stapelobjekt)?
  const castingId =
    !spectator && prompt && (prompt.kind === 'PICK_TARGET' || prompt.kind === 'PLAY_MANA' || prompt.kind === 'PLAY_X_MANA') && prompt.sourceId && stack.some((c) => c.id === prompt.sourceId)
      ? prompt.sourceId
      : null
  const priority = !spectator && prompt?.kind === 'SELECT' && prompt.mode === 'priority'

  return (
    <AnimatePresence>
      {top && (
        <motion.div
          key="stack"
          {...enter}
          className="pointer-events-auto absolute top-3.5 right-3.5 z-[6] flex flex-col rounded-md bg-bg-3 shadow-stack"
          style={{ width: stackW, maxHeight: 'calc(100% - 28px)' }}
          data-testid="stack-panel"
          onMouseLeave={() => onFocus(null)}
        >
          <div className="flex shrink-0 items-center gap-2 border-b border-line-2 px-3 py-2.5" data-stack={collapsed ? top.id : undefined}>
            <Icon name="stack" size={15} className="text-fg-3" />
            <span className="label" style={{ fontSize: 13 }}>
              Stapel · {stack.length}
            </span>
            {collapsed && <span className="min-w-0 truncate text-[12.5px] text-fg-2">{top.name}</span>}
            <span className="flex-1" />
            <button
              type="button"
              className="flex text-fg-4 transition-colors duration-1 hover:text-fg-1"
              title={collapsed ? 'Aufklappen' : 'Einklappen'}
              aria-label={collapsed ? 'Stapel aufklappen' : 'Stapel einklappen'}
              aria-expanded={!collapsed}
              onClick={() => setCollapsed(!collapsed)}
            >
              <Icon name={collapsed ? 'chevronRight' : 'chevronDown'} size={16} />
            </button>
          </div>
          {!collapsed && (
            <>
              <div className="scrollbar-thin flex min-h-0 flex-col overflow-y-auto">
                {rows.map((r) => (
                  <Row
                    key={r.card.id}
                    row={r}
                    top={r.pos === 1}
                    focused={r.card.id === focusId}
                    casting={r.card.id === castingId}
                    who={who(r.card.controllerId)}
                    inter={inter}
                    onHover={onHover}
                    onFocus={onFocus}
                  />
                ))}
              </div>
              <div className="shrink-0 px-3 py-2 text-[12px] leading-[1.4] text-fg-3">
                {castingId ? (
                  prompt?.kind === 'PICK_TARGET' ? (
                    'Ziel auf dem Brett wählen. Mögliche Ziele sind markiert.'
                  ) : (
                    <>
                      <Kbd tone="dim">Esc</Kbd> bricht das Wirken ab
                    </>
                  )
                ) : (
                  <>
                    Oberstes Objekt löst als Nächstes auf
                    {priority && (
                      <>
                        {' · '}
                        <Kbd tone="dim">Space</Kbd> gibt Priorität ab
                      </>
                    )}
                  </>
                )}
              </div>
            </>
          )}
        </motion.div>
      )}
    </AnimatePresence>
  )
}

interface RowProps {
  row: StackRow
  top: boolean
  focused: boolean
  casting: boolean
  who: { name: string; color: string } | null
  inter: Interaction
  onHover: (c: Card | null) => void
  onFocus: (id: string | null) => void
}

function Row({ row, top, focused, casting, who, inter, onHover, onFocus }: RowProps) {
  const { card: c, count } = row
  const hl = inter.highlight(c.id)
  const clickable = inter.canClick(c.id)
  const kind = [casting ? 'Wird gewirkt' : '', typeLabel(c) || (c.kind === 'ability' ? 'Fähigkeit' : 'Zauber'), count > 1 ? `×${count} gleiche` : ''].filter(Boolean).join(' · ')
  const rules = (c.rules ?? []).join('\n')
  const ring = hl === 'target' ? 'var(--color-target)' : hl === 'chosen' ? 'var(--color-chosen)' : null
  const style: CSSProperties = {
    background: top ? TOP_TINT : focused ? 'var(--color-bg-4)' : undefined,
    boxShadow: ring ? `inset 2px 0 0 ${ring}` : undefined,
  }
  return (
    <div
      data-stack={c.id}
      className={`flex shrink-0 gap-2.5 border-b border-line-1 px-3 py-2.5 ${clickable ? 'cursor-pointer' : ''}`}
      style={style}
      onMouseEnter={() => {
        onFocus(c.id)
        onHover(c)
      }}
      onClick={clickable ? (e) => inter.click(c.id, e) : undefined}
    >
      <div className="relative shrink-0">
        <CardView card={c} width={40} anchor={false} highlight={hl} count={count} upright />
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-1.5">
          <span className="min-w-0 truncate text-[13.5px] font-semibold text-fg-1">{c.name}</span>
          <span className="flex-1" />
          {who && (
            <span className="font-display text-[11px] leading-none font-semibold tracking-[.08em] whitespace-nowrap uppercase" style={{ color: who.color }}>
              {who.name}
            </span>
          )}
        </div>
        <span className="font-display text-[11px] leading-none font-semibold tracking-[.1em] text-fg-3 uppercase">{kind}</span>
        {rules && (
          <span className={`text-[12px] leading-[1.35] text-fg-2 ${top ? 'line-clamp-5' : 'line-clamp-2'}`}>
            <RulesText text={rules} />
          </span>
        )}
        {(c.targetRefs?.length || c.x != null) && (
          <div className="flex flex-wrap items-center gap-1">
            {c.targetRefs?.map((t) => <TargetChip key={t.id} t={t} onHover={onHover} />)}
            {c.x != null && (
              <Chip tone="neutral" size="xs" title="Angesagter Wert für X">
                X = {c.x}
              </Chip>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function TargetChip({ t, onHover }: { t: TargetRef; onHover: (c: Card | null) => void }) {
  const obj = useGame((s) => s.objects.get(t.id))
  const where = t.kind === 'card' && t.zone ? (ZONES[t.zone] ?? t.zone.toLowerCase()) : null
  const title = [t.owner && t.kind !== 'player' ? `von ${t.owner}` : null, where].filter(Boolean).join(' · ')
  return (
    <span
      className="inline-flex max-w-full items-center gap-1 rounded-xs px-[5px] py-[3px] font-display text-[12px] leading-none font-semibold tracking-[.04em] text-target shadow-[inset_0_0_0_1px_rgba(255,210,63,.5)]"
      title={title || undefined}
      onMouseEnter={() => {
        if (obj) onHover(obj)
      }}
    >
      <Icon name="target" size={12} />
      <span className="min-w-0 truncate">
        {t.name}
        {where && <span className="text-fg-3"> ({where})</span>}
      </span>
    </span>
  )
}
