import type { Card, PlayerState } from '../api/types'
import { Avatar, Chip } from '../components/ui'
import { Icon } from '../lib/icons'
import { useGame } from '../store/game'
import { podDecor } from './boardDecor'
import { CommandZone } from './CommandZone'
import { CommanderDamage } from './CommanderDamage'
import { commanderArt, commanderCard, counterName, SEAT_COLORS } from './format'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'
import { LifeTotal } from './LifeTotal'
import { ManaPool } from './ManaPool'
import { SKIP_LABEL } from './promptActions'
import { PlayerZones } from './ZoneCounter'

export interface PlayerInfoProps {
  /** eigener Spieler (beim Zuschauen: Blickwinkel-Spieler) */
  p: PlayerState
  inter: Interaction
  onHover: (c: Card | null) => void
  /** infoW, infoPad, infoGap, avatar, lifeBig */
  layout: BoardLayoutState
}

/** Klick ging auf eine Karte (eigener Klick-Handler) - dann nicht zusaetzlich den Spieler waehlen */
export function onCard(t: EventTarget | null): boolean {
  return t instanceof Element && !!t.closest('[data-obj], [data-objs]')
}

/** Rahmen (inset 2px) eines Spieler-Bereichs: Ziel gelb > gewaehlt gruen > angegriffen Karmin */
export function playerRing(d: { targetable: boolean; chosen: boolean; attackersN: number }): string | undefined {
  if (d.targetable) return 'inset 0 0 0 2px var(--color-target)'
  if (d.chosen) return 'inset 0 0 0 2px var(--color-chosen)'
  if (d.attackersN > 0) return 'inset 0 0 0 2px var(--color-attack)'
  return undefined
}

/**
 * Eigene Infospalte links im eigenen Bereich (data-player = FX-Anker und Spielerziel): Avatar, "Du" (beim
 * Zuschauen der Name), Deck, Leben, Zonen 2x2, Manapool, Marken, laufendes F-Tasten-Passen, Commander-Schaden,
 * Kommandozone (auch kompakt).
 */
export function PlayerInfo({ p, inter, onHover, layout }: PlayerInfoProps) {
  const answer = useGame((s) => s.answer)
  const state = useGame((s) => s.state)
  const stackFocus = useGame((s) => s.stackFocus)
  const spectator = useGame((s) => s.spectator)
  const decor = state ? podDecor(p.id, { state, inter, stackFocus, compact: layout.compact }) : null
  const targetable = !spectator && !!decor?.targetable
  const ring = decor ? playerRing({ ...decor, targetable }) : undefined
  const manaPick = !spectator && inter.mode === 'mana' ? (type: string) => answer({ mana: { playerId: p.id, type } }) : undefined
  const cmd = commanderCard(p)
  return (
    <div
      data-player={p.id}
      data-testid="player-info"
      className={`relative flex min-h-0 min-w-0 flex-col overflow-y-auto overflow-x-hidden border-r border-line-2 scrollbar-thin ${targetable ? 'cursor-pointer' : ''}`}
      style={{ padding: layout.infoPad, gap: layout.infoGap }}
      onClick={(e) => targetable && !onCard(e.target) && inter.click(p.id)}
    >
      {ring && <span className="pointer-events-none absolute inset-0 z-[3]" style={{ boxShadow: ring }} aria-hidden />}
      <div className="flex shrink-0 items-center gap-2.5">
        <span className="flex shrink-0" onMouseEnter={cmd ? () => onHover(cmd) : undefined} onMouseLeave={cmd ? () => onHover(null) : undefined}>
          <Avatar src={commanderArt(p)} name={p.name} size={layout.avatar} seatColor={SEAT_COLORS[0]} />
        </span>
        <div className="flex min-w-0 flex-col gap-[3px]">
          <span className="truncate font-display text-[17px] font-semibold leading-none tracking-[.03em] text-fg-1">
            {spectator ? p.name : <span className="uppercase">Du</span>}
          </span>
          {p.deckName && <span className="truncate text-[12px] leading-tight text-fg-3">{p.deckName}</span>}
        </div>
        <span className="flex-1" />
        {p.active ? (
          <Chip tone="turn" size="sm" className="shrink-0">
            Am Zug
          </Chip>
        ) : decor && decor.attackersN > 0 ? (
          <Chip tone="attack" fill size="sm" className="shrink-0">
            {decor.attackersN} Angreifer
          </Chip>
        ) : null}
      </div>
      {(decor?.stackTarget || p.lost) && (
        <div className="flex shrink-0 flex-wrap gap-1.5">
          {decor?.stackTarget && (
            <Chip tone="target" size="xs" icon="target">
              Ziel
            </Chip>
          )}
          {p.lost && (
            <Chip tone="outline" size="xs">
              Raus
            </Chip>
          )}
        </div>
      )}
      <LifeTotal life={p.life} lost={p.lost} size={layout.lifeBig} className="self-start" />
      <div className="grid shrink-0 grid-cols-2 justify-items-start gap-y-2">
        <PlayerZones p={p} inter={inter} onHover={onHover} variant="info" handAnchor={false} />
      </div>
      <PlayerChips p={p} />
      <ManaPool mana={p.mana} onPick={manaPick} variant="info" />
      {!spectator && p.skips && p.skips.length > 0 && <SkipBadge skips={p.skips} />}
      <CommanderDamage dmg={p.commanderDamage} />
      <CommandZone objects={p.command} inter={inter} onHover={onHover} interactive={!spectator} />
    </div>
  )
}

/** Laufendes F-Tasten-Passen: Klick = abbrechen (wie F3) */
function SkipBadge({ skips }: { skips: string[] }) {
  const action = useGame((s) => s.action)
  return (
    <button
      type="button"
      className="chip-ember shrink-0 self-start text-left"
      style={{ padding: '4px 6px', fontSize: 12, whiteSpace: 'normal' }}
      title="Klick oder F3: automatisches Passen beenden"
      onClick={(e) => {
        e.stopPropagation()
        action('PASS_PRIORITY_CANCEL_ALL_ACTIONS')
      }}
    >
      <Icon name="autoPass" size={12} />
      {SKIP_LABEL[skips[0]] ?? 'Passe automatisch'}
      <kbd className="kbd-dim">F3</kbd>
    </button>
  )
}

/**
 * Spielermarken, Monarch, Initiative als Text-Chips (im Design nicht gezeichnet). Gift ist Karmin.
 * size xs fuer die Infospalte, im Pod ebenfalls xs.
 */
export function PlayerChips({ p, inline }: { p: PlayerState; inline?: boolean }) {
  const counters = (p.counters ?? []).filter((c) => c.count > 0)
  if (counters.length === 0 && !p.monarch && !p.initiative) return null
  const chips = (
    <>
      {counters.map((c) => (
        <Chip key={c.name} tone={c.name.toLowerCase().includes('poison') ? 'attack' : 'neutral'} size="xs" title={c.name}>
          {counterName(c.name)} {c.count}
        </Chip>
      ))}
      {p.monarch && (
        <Chip tone="neutral" size="xs" title="Monarch: zieht am Ende des Zuges eine Karte">
          Monarch
        </Chip>
      )}
      {p.initiative && (
        <Chip tone="neutral" size="xs" title="Initiative: erkundet die Unterstadt">
          Initiative
        </Chip>
      )}
    </>
  )
  if (inline) return chips
  return <div className="flex shrink-0 flex-wrap gap-1.5">{chips}</div>
}
