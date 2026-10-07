import { useEffect, useRef, useState } from 'react'
import type { Card, PlayerState } from '../api/types'
import { Icon, type IconName } from '../lib/icons'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

export type ZoneKind = 'library' | 'hand' | 'graveyard' | 'exile'

/** Zone enthaelt etwas Waehlbares: Ziel (gelb) bzw. Spielbares (Ember) */
export type ZoneTone = 'target' | 'playable' | null

const ICON: Record<ZoneKind, IconName> = { library: 'library', hand: 'hand', graveyard: 'graveyard', exile: 'exile' }

export interface ZoneCounterProps {
  zone: ZoneKind
  count: number
  /** pod: 14 px, Icon 13, Abstand 4 · info: 16 px, Icon 14, Abstand 6 */
  variant: 'pod' | 'info'
  /** Tooltip und zugaenglicher Name ("Friedhof" - Skripte suchen button[title="Friedhof"]) */
  title: string
  /** oeffnet die Zonen-Ansicht; ohne Handler deaktiviert */
  onClick?: () => void
  tone?: ZoneTone
  /** oberste Bibliothekskarte sichtbar: ScanEye statt BookCopy */
  revealed?: boolean
  onHover?: (on: boolean) => void
  /** data-zone (FX-Anker); false, wenn ein anderer Anker die Zone vertritt (eigene Hand-Reihe) */
  anchor?: boolean
}

const FLASH_MS = 600

/** Zonenzaehler: Icon fg-4 + Zahl (Barlow 600 fg-2). Bei Aenderung 600 ms Ember. */
export function ZoneCounter({ zone, count, variant, title, onClick, tone = null, revealed, onHover, anchor = true }: ZoneCounterProps) {
  const prev = useRef(count)
  const [flash, setFlash] = useState(false)
  useEffect(() => {
    if (prev.current === count) return
    prev.current = count
    setFlash(true)
    const t = setTimeout(() => setFlash(false), FLASH_MS)
    return () => clearTimeout(t)
  }, [count])

  const pod = variant === 'pod'
  const accent = flash ? 'text-ember' : tone === 'target' ? 'text-target' : tone === 'playable' ? 'text-ember' : null
  const icon: IconName = zone === 'library' && revealed ? 'revealed' : ICON[zone]
  return (
    <button
      type="button"
      title={title}
      aria-label={title}
      data-zone={anchor ? zone : undefined}
      disabled={!onClick}
      onClick={(e) => {
        // nicht zum Pod/Spieler durchreichen (Spielerziel)
        e.stopPropagation()
        onClick?.()
      }}
      onMouseEnter={onHover ? () => onHover(true) : undefined}
      onMouseLeave={onHover ? () => onHover(false) : undefined}
      className={`flex items-center whitespace-nowrap font-display font-semibold leading-none tabular-nums transition-colors duration-2 enabled:cursor-pointer disabled:cursor-default ${accent ?? 'text-fg-2'} ${onClick ? 'enabled:hover:text-fg-1' : ''}`}
      style={{ gap: pod ? 4 : 6, fontSize: pod ? 14 : 16, letterSpacing: pod ? '.04em' : undefined }}
    >
      <Icon name={icon} size={pod ? 13 : 14} className={accent ? undefined : 'text-fg-4'} />
      {count}
    </button>
  )
}

/** Staerkste Hervorhebung von Karten einer Zone: Ziel vor Spielbar. */
export function zoneTone(cards: (Card | undefined)[], inter: Pick<Interaction, 'highlight'>): ZoneTone {
  let best: ZoneTone = null
  for (const c of cards) {
    if (!c) continue
    const h = inter.highlight(c.id)
    if (h === 'target') return 'target'
    if (h === 'playable' || h === 'special') best = 'playable'
  }
  return best
}

export interface PlayerZonesProps {
  p: PlayerState
  inter: Pick<Interaction, 'highlight'>
  onHover: (c: Card | null) => void
  variant: 'pod' | 'info'
  /** eigene Hand: kein data-zone am Zaehler, die Hand-Reihe ist der FX-Anker */
  handAnchor?: boolean
}

/** Die vier Zonen eines Spielers; Bibliothek (oberste Karte), Friedhof und Exil oeffnen die Zonen-Ansicht. */
export function PlayerZones({ p, inter, onHover, variant, handAnchor = true }: PlayerZonesProps) {
  const setViewer = useGame((s) => s.setViewer)
  const open = (tab: 'gy' | 'ex' | 'lib') => setViewer({ playerId: p.id, tab })
  const top = p.topCard
  const libTitle = top ? `Bibliothek – oberste Karte: ${top.name}${p.topCardPrivate ? ' (nur für dich sichtbar)' : ' (aufgedeckt)'}` : 'Bibliothek'
  return (
    <>
      <ZoneCounter
        zone="library"
        variant={variant}
        title={libTitle}
        count={p.library}
        revealed={!!top}
        tone={top ? zoneTone([top], inter) : null}
        onClick={top ? () => open('lib') : undefined}
        onHover={top ? (on) => onHover(on ? top : null) : undefined}
      />
      <ZoneCounter zone="hand" variant={variant} title="Hand" count={p.handCount} anchor={handAnchor} />
      <ZoneCounter
        zone="graveyard"
        variant={variant}
        title="Friedhof"
        count={p.graveyard.length}
        tone={zoneTone(p.graveyard, inter)}
        onClick={p.graveyard.length ? () => open('gy') : undefined}
      />
      <ZoneCounter
        zone="exile"
        variant={variant}
        title="Exil"
        count={p.exile.length}
        tone={zoneTone(p.exile, inter)}
        onClick={p.exile.length ? () => open('ex') : undefined}
      />
    </>
  )
}
