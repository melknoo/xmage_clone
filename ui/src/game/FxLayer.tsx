import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { Card, Tempo } from '../api/types'
import { CardView } from '../components/CardView'
import { fxIcon, Icon } from '../lib/icons'
import { cardFlight, DUR, EASE_OUT, enter } from '../lib/motion'
import { useGame, type FxItem } from '../store/game'
import { lifeAnchor } from './overlayGeometry'

/** Icon-Farbe je Ereignisart (Karmin = Schaden/Tod, Gruen = Leben, Gelb = Zaehler, sonst fg-3) */
const TONE: Partial<Record<FxItem['kind'], string>> = {
  died: 'text-attack',
  tokenDied: 'text-attack',
  countered: 'text-attack',
  damage: 'text-attack',
  life: 'text-chosen',
  counter: 'text-target',
}

const ZONE_KINDS = new Set(['died', 'tokenDied', 'exiled', 'bounced', 'tucked', 'discarded', 'milled', 'resolved', 'command', 'countered'])

export function describeFx(e: FxItem, playerName: (id?: string) => string): string {
  const n = e.amount && e.amount > 1 && (e.kind === 'tokenDied' || e.kind === 'died') ? `${e.amount}× ` : ''
  const name = e.name ?? 'Karte'
  switch (e.kind) {
    case 'died':
    case 'tokenDied':
      return `${n}${name} ${e.amount && e.amount > 1 ? 'sterben' : 'stirbt'}`
    case 'exiled':
      return `${name} → Exil`
    case 'bounced':
      return `${name} → Hand`
    case 'tucked':
      return `${name} → Bibliothek`
    case 'discarded':
      return `${name} abgeworfen`
    case 'milled':
      return `${name} gemillt`
    case 'resolved':
      return `${name} verrechnet`
    case 'command':
      return `${name} → Kommandozone`
    case 'countered':
      return `${name} neutralisiert`
    case 'damage':
      return e.objectId ? `${e.sourceName ?? 'Schaden'} → ${name}: −${e.amount}` : `${e.sourceName ?? 'Schaden'} → ${playerName(e.playerId)}: −${e.amount}`
    case 'life':
      return `${playerName(e.playerId)}: ${(e.amount ?? 0) > 0 ? '+' : ''}${e.amount} Leben`
    case 'counter':
      return `${e.sourceName ?? name}: +${e.amount} ${name}`
    default:
      return name
  }
}

/**
 * Mini-Animationen zu Spielereignissen (fliegende Geisterkarte beim Zonenwechsel, Lebens-Delta oben rechts an der
 * Lebensanzeige, Zahlen an Objekten) und die Ereignisleiste unten links ueber der Aktionsleiste - damit beim
 * Auto-Passen klar bleibt, was gerade passiert ist. Bei reduzierter Bewegung erzeugt der Store keine Animationen.
 */
export function FxLayer() {
  const fx = useGame((s) => s.fx)
  const recent = useGame((s) => s.recent)
  const fxEnabled = useGame((s) => s.fxEnabled)
  const tempo = useGame((s) => s.tempo)
  const players = useGame((s) => s.state?.players)
  const spectator = useGame((s) => s.spectator)
  const playerName = (id?: string) => {
    const p = players?.find((x) => x.id === id)
    return p ? (p.me && !spectator ? 'Du' : p.name) : '?'
  }

  return (
    <div className="pointer-events-none fixed inset-0 z-[10]">
      {fxEnabled &&
        fx.map((e) => {
          if (!e.src) return null
          if (ZONE_KINDS.has(e.kind)) return <GhostCard key={e.key} e={e} tempo={tempo} />
          if (e.kind === 'damage' || e.kind === 'life' || e.kind === 'counter') return <Floater key={e.key} e={e} />
          return null
        })}
      <div className="absolute left-3.5 flex max-w-[360px] flex-col items-start gap-1" style={{ bottom: 'calc(var(--prompt-h, 62px) + 8px)' }} data-testid="fx-strip">
        <AnimatePresence>
          {recent.map((e) => (
            <motion.div key={e.key} {...enter} className="flex max-w-full items-center gap-2 rounded-sm bg-bg-4 px-2 py-[5px] text-[12.5px] text-fg-1 shadow-toast">
              <Icon name={fxIcon(e.kind)} size={14} className={TONE[e.kind] ?? 'text-fg-3'} />
              <span className="truncate">{describeFx(e, playerName)}</span>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}

const GHOST_W = 62
const GHOST_H = Math.round((GHOST_W * 88) / 63)

function GhostCard({ e, tempo }: { e: FxItem; tempo: Tempo }) {
  const from = e.src!
  const to = e.dst ?? { x: from.x, y: from.y - 40 }
  const card: Card = e.card ?? { id: e.objectId ?? e.key.toString(), name: e.name ?? '' }
  const fade = e.kind === 'tokenDied'
  // 420 ms, bei Blitz 210 ms; ausgeblendet wird erst gegen Ende
  const flight = cardFlight(tempo)
  return (
    <motion.div
      className="absolute"
      style={{ left: from.x - GHOST_W / 2, top: from.y - GHOST_H / 2, width: GHOST_W }}
      initial={{ x: 0, y: 0, scale: 1, opacity: 0.95 }}
      animate={{ x: to.x - from.x, y: to.y - from.y, scale: fade ? 0.8 : 0.5, opacity: [0.95, 0.95, 0] }}
      transition={{ ...flight, opacity: { ...flight, times: [0, 0.7, 1] } }}
    >
      <CardView card={card} width={GHOST_W} anchor={false} upright />
    </motion.div>
  )
}

function Floater({ e }: { e: FxItem }) {
  // Spieler-Leben/-Schaden: oben rechts an der Lebensanzeige (Position beim Erscheinen, die Anzeige bewegt sich nicht)
  const [pos] = useState(() => (!e.objectId && e.playerId && (e.kind === 'damage' || e.kind === 'life') ? lifeAnchor(e.playerId) : null))
  const at = pos ?? e.src!
  const amount = e.amount ?? 0
  const text = e.kind === 'damage' ? `−${amount}` : e.kind === 'life' ? (amount > 0 ? `+${amount}` : `−${Math.abs(amount)}`) : `+${amount}`
  const color = e.kind === 'counter' ? 'text-target' : e.kind === 'life' && amount > 0 ? 'text-chosen' : 'text-attack'
  return (
    <motion.div
      className={`absolute font-display text-[22px] leading-none font-semibold whitespace-nowrap tabular-nums ${color} ${pos ? '' : '-translate-x-1/2'}`}
      style={pos ? { left: at.x + 2, top: at.y - 18 } : { left: at.x, top: at.y - 14 }}
      initial={{ y: 0, opacity: 1 }}
      animate={{ y: -20, opacity: [1, 1, 0] }}
      transition={{ duration: DUR.xp, ease: EASE_OUT, opacity: { duration: DUR.xp, times: [0, 0.6, 1] } }}
    >
      {text}
      {e.kind === 'counter' && e.name && <span className="ml-1 font-sans text-[12px] font-semibold">{e.name}</span>}
    </motion.div>
  )
}
