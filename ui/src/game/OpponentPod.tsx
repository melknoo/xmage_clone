import { memo } from 'react'
import type { Card, PlayerState } from '../api/types'
import { CardView } from '../components/CardView'
import { Avatar, Button, Chip } from '../components/ui'
import { Icon } from '../lib/icons'
import { useGame } from '../store/game'
import { Battlefield } from './Battlefield'
import { podDecor } from './boardDecor'
import { CommanderDamageInline } from './CommanderDamage'
import { commandCard, commanderArt, commanderCard, seatColor } from './format'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'
import { LifeTotal } from './LifeTotal'
import { ManaPool } from './ManaPool'
import { onCard, PlayerChips, playerRing } from './PlayerInfo'
import { PlayerZones } from './ZoneCounter'

export interface OpponentPodProps {
  p: PlayerState
  inter: Interaction
  onHover: (c: Card | null) => void
  /** oppH, podPad, bfPad, avatar, lifeOpp, oppCardW, compact */
  layout: BoardLayoutState
}

/**
 * Gegner-Pod: Kopf (Avatar in Platzfarbe, Name, Deck, Chips, Leben), Zonenzeile, Feld (Nicht-Laender oben,
 * Laender unten). Spielerziel: ganzer Pod gelb umrandet und anklickbar (data-player an der Wurzel).
 * "Denkt" (store.thinking), Verbindung (store.seatConn) und Zonen-Ansicht (store.viewer) aus dem Store.
 */
export const OpponentPod = memo(function OpponentPod({ p, inter, onHover, layout }: OpponentPodProps) {
  const thinking = useGame((s) => s.thinking === p.id)
  const state = useGame((s) => s.state)
  const stackFocus = useGame((s) => s.stackFocus)
  const spectator = useGame((s) => s.spectator)
  const decor = state ? podDecor(p.id, { state, inter, stackFocus, compact: layout.compact }) : null
  const targetable = !spectator && !!decor?.targetable
  const ring = decor ? playerRing({ ...decor, targetable }) : undefined
  const cmd = commanderCard(p)
  const commanders = p.command.filter((o) => (o.kind === 'commander' || o.kind === 'commander-away') && (o.tax ?? 0) > 0)
  const others = p.command.filter((o) => o.kind !== 'commander' && o.kind !== 'commander-away')
  return (
    <div
      data-player={p.id}
      data-testid="opp-pod"
      className={`relative flex h-full min-h-0 min-w-0 flex-1 flex-col border-r border-line-2 last:border-r-0 ${targetable ? 'cursor-pointer' : ''}`}
      style={p.lost ? { opacity: 0.5 } : undefined}
      onClick={(e) => targetable && !onCard(e.target) && inter.click(p.id)}
    >
      {p.active && <span className="pointer-events-none absolute inset-x-0 top-0 z-[4] h-[3px] bg-ember" aria-hidden />}
      {ring && <span className="pointer-events-none absolute inset-0 z-[3]" style={{ boxShadow: ring }} aria-hidden />}

      {/* Kopf */}
      <div className="flex shrink-0 items-center gap-2.5" style={{ padding: layout.podPad }}>
        <span
          className="flex shrink-0"
          data-zone="command"
          onMouseEnter={cmd ? () => onHover(cmd) : undefined}
          onMouseLeave={cmd ? () => onHover(null) : undefined}
        >
          <Avatar src={commanderArt(p)} name={p.name} size={layout.avatar} seatColor={seatColor(p.id, state)} />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-[3px]">
          <span className="truncate font-display text-[17px] font-semibold leading-none tracking-[.03em] text-fg-1" title={p.name}>
            {p.name}
          </span>
          {p.deckName && <span className="truncate text-[12px] leading-tight text-fg-3">{p.deckName}</span>}
        </div>
        <div className="flex min-w-0 shrink items-center justify-end gap-1.5">
          {decor?.stackTarget && (
            <Chip tone="target" size="sm" icon="target">
              Ziel
            </Chip>
          )}
          {thinking && (
            <Chip tone="outline" size="sm" icon="thinking" title="Bot rechnet">
              Denkt
            </Chip>
          )}
          {p.human && !p.lost && <ConnBadge playerId={p.id} />}
          {p.lost && (
            <Chip tone="outline" size="sm">
              Raus
            </Chip>
          )}
          {p.active ? (
            <Chip tone="turn" size="sm">
              Am Zug
            </Chip>
          ) : decor && decor.attackersN > 0 ? (
            <Chip tone="attack" fill size="sm">
              {decor.attackersN} Angreifer
            </Chip>
          ) : null}
        </div>
        <LifeTotal life={p.life} lost={p.lost} size={layout.lifeOpp} />
      </div>

      {/* Zonenzeile */}
      <div className="flex min-w-0 shrink-0 items-center gap-[13px] px-[14px] pb-2">
        <PlayerZones p={p} inter={inter} onHover={onHover} variant="pod" />
        <span className="flex-1" />
        <div className="flex min-w-0 items-center justify-end gap-2.5 overflow-hidden font-display text-[14px] font-semibold leading-none tracking-[.04em]">
          <ManaPool mana={p.mana} variant="pod" />
          <PlayerChips p={p} inline />
          <CommanderDamageInline dmg={p.commanderDamage} />
          {others.map((o) => (
            <span key={o.id ?? `${o.kind}:${o.name}`} className="flex shrink-0" title={o.name}>
              <CardView card={commandCard(o)} width={22} anchor={false} onHover={onHover} />
            </span>
          ))}
          {commanders.map((o) => (
            <span key={o.id ?? `${o.kind}:${o.name}`} className="flex shrink-0 items-center gap-1 text-target" title={`Commander-Steuer ${o.name}: ${o.casts ?? 0}× gewirkt`}>
              <Icon name="commander" size={13} />+{o.tax}
            </span>
          ))}
        </div>
      </div>

      <Battlefield perms={p.battlefield} variant="opponent" inter={inter} onHover={onHover} layout={layout} />
    </div>
  )
})

/** "Getrennt N s" fuer menschliche Mitspieler; nach kickAfterMs "Aufgeben lassen" (Engine wartet sonst ewig auf dessen Prompt). */
function ConnBadge({ playerId }: { playerId: string }) {
  const conn = useGame((s) => s.seatConn[playerId])
  const myConceded = useGame((s) => s.conceded)
  const spectator = useGame((s) => s.spectator)
  const kick = useGame((s) => s.kick)
  // Grenze kommt von der Engine (Produktion 60 s, Dev-Engine 5 s)
  const kickAfterMs = useGame((s) => s.kickAfterMs)
  if (!conn || conn.connected || conn.conceded) return null
  const secs = Math.round(conn.disconnectedMs / 1000)
  return (
    <span className="flex shrink-0 items-center gap-1.5" onClick={(e) => e.stopPropagation()}>
      <Chip tone="attack" size="sm" icon="disconnected" title="Verbindung zum Mitspieler ist weg; das Spiel wartet auf ihn">
        Getrennt {secs} s
      </Chip>
      {conn.disconnectedMs >= kickAfterMs && !myConceded && !spectator && (
        <Button
          variant="danger"
          size="xs"
          icon="kick"
          style={{ height: 22, padding: '0 6px', fontSize: 12 }}
          title="Den getrennten Spieler aufgeben lassen, damit das Spiel weitergeht"
          onClick={() => kick(playerId)}
        >
          Aufgeben lassen
        </Button>
      )}
    </span>
  )
}
