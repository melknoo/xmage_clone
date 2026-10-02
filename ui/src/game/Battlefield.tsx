import { memo, useMemo } from 'react'
import type { Card, CombatGroup, Permanent } from '../api/types'
import { CardView, type CardSize } from '../components/CardView'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

interface Group {
  key: string
  cards: Permanent[]
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

/**
 * Gleichartige Permanents zusammenfassen: gleicher Name, Zustand, P/T, Hervorhebung, im Kampf gleiches Ziel.
 * Nicht zusammengefasst werden Karten mit Marken, Anlagen, Schaden oder als Ziel eines Stapelobjekts.
 */
function group(perms: Permanent[], inter: Interaction, targeted: Set<string>, ci: CombatIndex): Group[] {
  const out: Group[] = []
  const index = new Map<string, Group>()
  for (const p of perms) {
    const simple = !p.counters?.length && !p.attachments?.length && !p.damage && !targeted.has(p.id)
    const fight = p.attacking ? `att:${ci.defenderOf.get(p.id) ?? ''}` : p.blocking ? `blk:${ci.blockedBy.get(p.id) ?? ''}` : ''
    const key = simple ? `${p.name}|${p.tapped ? 1 : 0}|${p.sick ? 1 : 0}|${inter.highlight(p.id)}|${p.power ?? ''}/${p.toughness ?? ''}|${fight}` : p.id
    const g = index.get(key)
    if (g && simple) {
      g.cards.push(p)
    } else {
      const ng = { key, cards: [p] }
      index.set(key, ng)
      out.push(ng)
    }
  }
  return out
}

interface RowProps {
  perms: Permanent[]
  size: CardSize
  inter: Interaction
  onHover: (c: Card | null) => void
  attachmentsOf: Map<string, Permanent[]>
  targeted: Set<string>
  ci: CombatIndex
}

function Row({ perms, size, inter, onHover, attachmentsOf, targeted, ci }: RowProps) {
  const groups = group(perms, inter, targeted, ci)
  return (
    <div className="flex min-h-0 flex-wrap content-start items-end gap-x-1.5 gap-y-1">
      {groups.map((g) => {
        const first = g.cards.find((c) => inter.canClick(c.id)) ?? g.cards[0]
        const attachments = attachmentsOf.get(first.id) ?? []
        const ids = g.cards.map((c) => c.id)
        return (
          <div
            key={g.key}
            className="relative"
            style={{ paddingTop: attachments.length * 10 }}
            // zusammengefasste Karten: Pfeile/Ziele finden sie ueber data-objs
            data-objs={ids.length > 1 ? ids.join(' ') : undefined}
            // Shift+Klick markiert - keine Textauswahl
            onMouseDown={(e) => e.shiftKey && e.preventDefault()}
          >
            {attachments.map((a, i) => (
              <div key={a.id} className="absolute left-0 right-0" style={{ top: i * 10 }}>
                <CardView card={a} size={size} highlight={inter.highlight(a.id)} onHover={onHover} onClick={(e) => inter.click(a.id, e)} />
              </div>
            ))}
            <div className="relative">
              <CardView
                card={first}
                size={size}
                highlight={inter.highlight(first.id)}
                onHover={onHover}
                onClick={(e) => inter.click(first.id, e, ids)}
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
  size: CardSize
  inter: Interaction
  onHover: (c: Card | null) => void
  /** Reihenfolge: Kreaturen zuerst (eigene Seite) oder Laender zuerst (Gegner oben) */
  landsFirst?: boolean
}

export const Battlefield = memo(function Battlefield({ perms, size, inter, onHover, landsFirst }: BattlefieldProps) {
  const stack = useGame((s) => s.state?.stack)
  const combat = useGame((s) => s.state?.combat)
  // Ziele von Stapelobjekten nicht zusammenfassen, damit der Zielpfeil sie findet
  const targeted = useMemo(() => new Set((stack ?? []).flatMap((c) => c.targets ?? [])), [stack])
  const ci = useMemo(() => combatIndex(combat), [combat])
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
  const others = visible.filter((p) => p.row === 'other')
  const lands = visible.filter((p) => p.row === 'land')

  const rows = [
    { key: 'c', perms: creatures },
    { key: 'o', perms: others },
    { key: 'l', perms: lands },
  ]
  if (landsFirst) rows.reverse()

  return (
    <div className="flex h-full min-h-0 flex-col gap-1.5 overflow-y-auto overflow-x-hidden scrollbar-thin p-1">
      {rows.map((r) =>
        r.perms.length > 0 ? (
          <Row key={r.key} perms={r.perms} size={size} inter={inter} onHover={onHover} attachmentsOf={attachmentsOf} targeted={targeted} ci={ci} />
        ) : null,
      )}
    </div>
  )
})
