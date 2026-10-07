import { memo, useMemo } from 'react'
import type { Card, CombatGroup, Permanent } from '../api/types'
import { CardView, type CardLabel } from '../components/CardView'
import { useGame } from '../store/game'
import { cardDecor, type DecorCtx } from './boardDecor'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'

interface Group {
  key: string
  cards: Permanent[]
  label?: CardLabel
}

/** Wen greift ein Angreifer an, wen blockt ein Blocker (fuer die Gruppierung im Kampf). */
interface CombatIndex {
  defenderOf: Map<string, string>
  blockedBy: Map<string, string>
}

function combatIndex(combat: CombatGroup[] | undefined): CombatIndex {
  const defenderOf = new Map<string, string>()
  const blockedBy = new Map<string, string[]>()
  for (const g of combat ?? []) {
    for (const a of g.attackers) defenderOf.set(a, g.defenderId)
    for (const b of g.blockers) blockedBy.set(b, [...(blockedBy.get(b) ?? []), ...g.attackers])
  }
  return { defenderOf, blockedBy: new Map([...blockedBy].map(([b, list]) => [b, list.join(',')])) }
}

type LabelOf = (id: string) => CardLabel | undefined

/**
 * Gleichartige Permanents zusammenfassen (×N): gleicher Name, Zustand, P/T, Hervorhebung, Etikett, im Kampf gleiches
 * Ziel. Nicht zusammengefasst werden Karten mit Marken, Anlagen, Schaden oder als Ziel eines Stapelobjekts.
 */
function group(perms: Permanent[], inter: Interaction, targeted: Set<string>, ci: CombatIndex, labelOf: LabelOf): Group[] {
  const out: Group[] = []
  const index = new Map<string, Group>()
  for (const p of perms) {
    const simple = !p.counters?.length && !p.attachments?.length && !p.damage && !targeted.has(p.id)
    const label = labelOf(p.id)
    const fight = p.attacking ? `att:${ci.defenderOf.get(p.id) ?? ''}` : p.blocking ? `blk:${ci.blockedBy.get(p.id) ?? ''}` : ''
    const key = simple
      ? `${p.name}|${p.tapped ? 1 : 0}|${p.sick ? 1 : 0}|${inter.highlight(p.id)}|${p.power ?? ''}/${p.toughness ?? ''}|${fight}|${label ? `${label.tone}:${label.text}` : ''}`
      : p.id
    const g = index.get(key)
    if (g && simple) {
      g.cards.push(p)
    } else {
      const ng: Group = { key, cards: [p], label }
      index.set(key, ng)
      out.push(ng)
    }
  }
  return out
}

interface RowProps {
  perms: Permanent[]
  /** Kartenbreite px */
  width: number
  /** Abstand zwischen Karten / zwischen umbrochenen Zeilen */
  gap: number
  rowGap: number
  opponent: boolean
  interactive: boolean
  inter: Interaction
  onHover: (c: Card | null) => void
  attachmentsOf: Map<string, Permanent[]>
  targeted: Set<string>
  ci: CombatIndex
  labelOf: LabelOf
  testId?: string
}

function Row({ perms, width, gap, rowGap, opponent, interactive, inter, onHover, attachmentsOf, targeted, ci, labelOf, testId }: RowProps) {
  const groups = group(perms, inter, targeted, ci, labelOf)
  return (
    <div className="flex shrink-0 flex-wrap content-start items-end" style={{ columnGap: gap, rowGap }} data-testid={testId}>
      {groups.map((g) => {
        const first = g.cards.find((c) => inter.canClick(c.id)) ?? g.cards[0]
        const attachments = attachmentsOf.get(first.id) ?? []
        const ids = g.cards.map((c) => c.id)
        return (
          <div
            key={g.key}
            className="relative"
            style={{ paddingTop: attachments.length * 10 }}
            // zusammengefasste Karten: FX/Ziele finden sie ueber data-objs
            data-objs={ids.length > 1 ? ids.join(' ') : undefined}
            // Shift+Klick markiert - keine Textauswahl
            onMouseDown={(e) => e.shiftKey && e.preventDefault()}
          >
            {attachments.map((a, i) => (
              <div key={a.id} className="absolute left-0 right-0" style={{ top: i * 10 }}>
                <CardView
                  card={a}
                  width={width}
                  highlight={inter.highlight(a.id)}
                  onHover={onHover}
                  onClick={interactive ? (e) => inter.click(a.id, e) : undefined}
                />
              </div>
            ))}
            <div className="relative">
              <CardView
                card={first}
                width={width}
                label={g.label}
                labelVariant={opponent ? 'opponent' : 'own'}
                highlight={inter.highlight(first.id)}
                onHover={onHover}
                onClick={interactive ? (e) => inter.click(first.id, e, ids) : undefined}
                count={g.cards.length}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}

export interface BattlefieldProps {
  perms: Permanent[]
  inter: Interaction
  onHover: (c: Card | null) => void
  /** own: Kreaturen oben, Laender/Artefakte darunter; opponent: Pod-Feld eines Gegners */
  variant: 'own' | 'opponent'
  /** Kartenbreiten (creatureW, landW, oppCardW), Abstaende (rowGap, bfPad, myPad, myGap), compact */
  layout: BoardLayoutState
}

const SECTION_LABEL = { fontSize: 12, letterSpacing: '.14em', color: 'var(--color-fg-4)' } as const

export const Battlefield = memo(function Battlefield({ perms, inter, onHover, variant, layout }: BattlefieldProps) {
  const state = useGame((s) => s.state)
  const stackFocus = useGame((s) => s.stackFocus)
  const spectator = useGame((s) => s.spectator)
  const stack = state?.stack
  const combat = state?.combat
  // Ziele von Stapelobjekten nicht zusammenfassen, damit "Ziel" genau eine Karte trifft
  const targeted = useMemo(() => new Set((stack ?? []).flatMap((c) => c.targets ?? [])), [stack])
  const ci = useMemo(() => combatIndex(combat), [combat])
  const labelOf: LabelOf = useMemo(() => {
    if (!state) return () => undefined
    const ctx: DecorCtx = { state, inter, stackFocus, compact: layout.compact }
    return (id: string) => cardDecor(id, ctx).label
  }, [state, inter, stackFocus, layout.compact])

  const ids = new Set(perms.map((p) => p.id))
  const attachmentsOf = new Map<string, Permanent[]>()
  const attached = new Set<string>()
  for (const p of perms) {
    if (p.attachedTo && ids.has(p.attachedTo)) {
      const list = attachmentsOf.get(p.attachedTo) ?? []
      list.push(p)
      attachmentsOf.set(p.attachedTo, list)
      attached.add(p.id)
    }
  }
  const visible = perms.filter((p) => !attached.has(p.id))
  const creatures = visible.filter((p) => p.row === 'creature')
  const others = visible.filter((p) => p.row !== 'creature' && p.row !== 'land')
  const lands = visible.filter((p) => p.row === 'land')
  const common = { inter, onHover, attachmentsOf, targeted, ci, labelOf, interactive: !spectator }

  if (variant === 'opponent') {
    // Pod-Feld: oben Nicht-Laender, unten Laender (gleiche Namen ×N)
    const top = [...creatures, ...others]
    return (
      <div
        className="flex min-h-0 flex-1 flex-col overflow-y-auto overflow-x-hidden border-t border-line-board bg-bg-board scrollbar-thin"
        style={{ padding: layout.bfPad, gap: layout.rowGap }}
      >
        {top.length > 0 && <Row {...common} perms={top} width={layout.oppCardW} gap={9} rowGap={14} opponent />}
        {lands.length > 0 && <Row {...common} perms={lands} width={layout.oppCardW} gap={9} rowGap={9} opponent />}
      </div>
    )
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-col overflow-y-auto overflow-x-hidden scrollbar-thin" style={{ padding: layout.myPad, gap: layout.myGap }}>
      <span className="label shrink-0" style={SECTION_LABEL}>
        Kreaturen
      </span>
      <Row {...common} perms={creatures} width={layout.creatureW} gap={16} rowGap={18} opponent={false} testId="battlefield-creatures" />
      <span className="label mt-1.5 shrink-0" style={SECTION_LABEL}>
        Länder · Artefakte
      </span>
      <Row {...common} perms={[...lands, ...others]} width={layout.landW} gap={12} rowGap={12} opponent={false} testId="battlefield-lands" />
    </div>
  )
})
