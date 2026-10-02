import { memo, useMemo } from 'react'
import type { Card, Permanent } from '../api/types'
import { CardView, type CardSize } from '../components/CardView'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

interface Group {
  key: string
  cards: Permanent[]
}

/** Gleichartige Permanents (gleicher Name, gleicher Zustand, ohne Marken/Anlagen, kein Ziel) zusammenfassen. */
function group(perms: Permanent[], inter: Interaction, collapse: boolean, targeted: Set<string>): Group[] {
  if (!collapse) return perms.map((p) => ({ key: p.id, cards: [p] }))
  const out: Group[] = []
  const index = new Map<string, Group>()
  for (const p of perms) {
    const simple = !p.counters?.length && !p.attachments?.length && !p.attacking && !p.blocking && !p.damage && !targeted.has(p.id)
    const key = simple ? `${p.name}|${p.tapped ? 1 : 0}|${p.sick ? 1 : 0}|${inter.highlight(p.id)}|${p.power ?? ''}/${p.toughness ?? ''}` : p.id
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
  collapse: boolean
  attachmentsOf: Map<string, Permanent[]>
  targeted: Set<string>
}

function Row({ perms, size, inter, onHover, collapse, attachmentsOf, targeted }: RowProps) {
  const groups = group(perms, inter, collapse, targeted)
  return (
    <div className="flex min-h-0 flex-wrap content-start items-end gap-x-1.5 gap-y-1">
      {groups.map((g) => {
        const first = g.cards.find((c) => inter.canClick(c.id)) ?? g.cards[0]
        const attachments = attachmentsOf.get(first.id) ?? []
        return (
          <div key={g.key} className="relative" style={{ paddingTop: attachments.length * 10 }}>
            {attachments.map((a, i) => (
              <div key={a.id} className="absolute left-0 right-0" style={{ top: i * 10 }}>
                <CardView card={a} size={size} highlight={inter.highlight(a.id)} onHover={onHover} onClick={() => inter.click(a.id)} />
              </div>
            ))}
            <div className="relative">
              <CardView
                card={first}
                size={size}
                highlight={inter.highlight(first.id)}
                onHover={onHover}
                onClick={() => inter.click(first.id)}
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
  compact?: boolean
  /** Reihenfolge: Kreaturen zuerst (eigene Seite) oder Laender zuerst (Gegner oben) */
  landsFirst?: boolean
}

export const Battlefield = memo(function Battlefield({ perms, size, inter, onHover, compact, landsFirst }: BattlefieldProps) {
  const stack = useGame((s) => s.state?.stack)
  // Ziele von Stapelobjekten nicht zusammenfassen, damit der Zielpfeil sie findet
  const targeted = useMemo(() => new Set((stack ?? []).flatMap((c) => c.targets ?? [])), [stack])
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
    { key: 'c', perms: creatures, collapse: compact ?? false },
    { key: 'o', perms: others, collapse: true },
    { key: 'l', perms: lands, collapse: true },
  ]
  if (landsFirst) rows.reverse()

  return (
    <div className="flex h-full min-h-0 flex-col gap-1.5 overflow-y-auto overflow-x-hidden scrollbar-thin p-1">
      {rows.map((r) =>
        r.perms.length > 0 ? (
          <Row key={r.key} perms={r.perms} size={size} inter={inter} onHover={onHover} collapse={r.collapse} attachmentsOf={attachmentsOf} targeted={targeted} />
        ) : null,
      )}
    </div>
  )
})
