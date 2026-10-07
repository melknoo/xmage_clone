import type { Card, CommandObject } from '../api/types'
import { CardView } from '../components/CardView'
import { Icon } from '../lib/icons'
import { commandCard } from './format'
import type { Interaction } from './interaction'

const CARD_W = 44

const isCommander = (o: CommandObject) => o.kind === 'commander' || o.kind === 'commander-away'

export interface CommandZoneProps {
  objects: CommandObject[]
  inter: Interaction
  onHover: (c: Card | null) => void
  /** false: nur ansehen (Zuschauer) */
  interactive?: boolean
}

/**
 * Eigene Kommandozone (Infospalte, auch kompakt): Karten 44 px (Klick wirkt den Commander, Hover zoomt), daneben
 * Krone + "Command Zone" und "Steuer +N" je Commander. Commander auf dem Feld/anderswo: abgedunkelt, ohne data-obj.
 */
export function CommandZone({ objects, inter, onHover, interactive = true }: CommandZoneProps) {
  if (objects.length === 0) return null
  const commanders = objects.filter(isCommander)
  return (
    <div className="mt-auto flex shrink-0 items-center gap-2.5" data-zone="command">
      <div className="flex shrink-0 items-end gap-1.5">
        {objects.map((o) => {
          const away = o.kind === 'commander-away'
          return (
            <CardView
              key={o.id ?? `${o.kind}:${o.name}`}
              card={commandCard(o)}
              width={CARD_W}
              dim={away}
              anchor={!away}
              highlight={away ? 'none' : inter.highlight(o.id)}
              onHover={onHover}
              onClick={interactive && !away ? (e) => inter.click(o.id, e) : undefined}
            />
          )
        })}
      </div>
      <div className="flex min-w-0 flex-col gap-[5px]">
        <span className="label flex items-center gap-[5px] whitespace-nowrap" style={{ fontSize: 12 }}>
          <Icon name="commander" size={13} className="text-target" />
          Command Zone
        </span>
        {commanders.map((o) => (
          <span
            key={o.id ?? `${o.kind}:${o.name}`}
            className="truncate text-[13px] font-semibold text-fg-1"
            title={`${o.name}: ${o.casts ?? 0}× gewirkt${o.kind === 'commander-away' ? ' · nicht in der Kommandozone' : ''}`}
          >
            Steuer {(o.tax ?? 0) > 0 ? `+${o.tax}` : '–'}
          </span>
        ))}
      </div>
    </div>
  )
}
