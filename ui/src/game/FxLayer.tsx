import { AnimatePresence, motion } from 'motion/react'
import type { Card } from '../api/types'
import { CardView } from '../components/CardView'
import { useGame, type FxItem } from '../store/game'

/** Farbe/Icon je Ereignisart (Ereignisleiste + Rand der fliegenden Karte) */
const STYLE: Record<string, { icon: string; ring: string; text: string }> = {
  died: { icon: '💀', ring: 'ring-blood-400', text: 'text-blood-400' },
  tokenDied: { icon: '💀', ring: 'ring-blood-400', text: 'text-blood-400' },
  exiled: { icon: '🌀', ring: 'ring-purple-400', text: 'text-purple-300' },
  bounced: { icon: '↩', ring: 'ring-sky-400', text: 'text-sky-300' },
  tucked: { icon: '📚', ring: 'ring-sky-400', text: 'text-sky-300' },
  discarded: { icon: '🗑', ring: 'ring-ink-400', text: 'text-ink-300' },
  milled: { icon: '🪦', ring: 'ring-ink-400', text: 'text-ink-300' },
  resolved: { icon: '✓', ring: 'ring-ink-400', text: 'text-ink-300' },
  command: { icon: '👑', ring: 'ring-gold-400', text: 'text-gold-300' },
  countered: { icon: '✖', ring: 'ring-blood-400', text: 'text-blood-400' },
  damage: { icon: '♥', ring: 'ring-blood-400', text: 'text-blood-400' },
  life: { icon: '♥', ring: 'ring-emerald-400', text: 'text-emerald-300' },
  counter: { icon: '●', ring: 'ring-gold-400', text: 'text-gold-300' },
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
 * Mini-Animationen zu Spielereignissen (fliegende Geisterkarte beim Zonenwechsel, schwebende Zahlen bei Schaden/Leben)
 * und eine kurze Ereignisleiste - damit beim Auto-Passen klar bleibt, was gerade passiert ist.
 */
export function FxLayer() {
  const fx = useGame((s) => s.fx)
  const recent = useGame((s) => s.recent)
  const fxEnabled = useGame((s) => s.fxEnabled)
  const players = useGame((s) => s.state?.players ?? [])
  const playerName = (id?: string) => players.find((p) => p.id === id)?.name ?? '?'

  return (
    <div className="pointer-events-none fixed inset-0 z-[45]">
      {fxEnabled &&
        fx.map((e) => {
          if (!e.src) return null
          if (ZONE_KINDS.has(e.kind)) return <GhostCard key={e.key} e={e} />
          if (e.kind === 'damage' || e.kind === 'life' || e.kind === 'counter') return <Floater key={e.key} e={e} />
          return null
        })}
      <div className="absolute bottom-[76px] left-3 flex max-w-[360px] flex-col gap-1">
        <AnimatePresence>
          {recent.map((e) => {
            const st = STYLE[e.kind] ?? STYLE.resolved
            return (
              <motion.div
                key={e.key}
                initial={{ opacity: 0, x: -16 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -16 }}
                className="glass flex items-center gap-2 rounded-lg px-2 py-1 text-xs text-ink-100 ring-1 ring-white/10"
              >
                <span className={`w-4 text-center ${st.text}`}>{st.icon}</span>
                <span className="truncate">{describeFx(e, playerName)}</span>
              </motion.div>
            )
          })}
        </AnimatePresence>
      </div>
    </div>
  )
}

const GHOST_W = 62
const GHOST_H = 86

function GhostCard({ e }: { e: FxItem }) {
  const from = e.src!
  const to = e.dst ?? { x: from.x, y: from.y - 40 }
  const card: Card = e.card ?? { id: e.objectId ?? e.key.toString(), name: e.name ?? '' }
  const st = STYLE[e.kind] ?? STYLE.resolved
  const fade = e.kind === 'tokenDied'
  return (
    <motion.div
      className={`absolute rounded-md ring-2 ${st.ring} shadow-2xl`}
      style={{ left: from.x - GHOST_W / 2, top: from.y - GHOST_H / 2, width: GHOST_W }}
      initial={{ x: 0, y: 0, scale: 1, opacity: 0.95 }}
      animate={{ x: to.x - from.x, y: to.y - from.y, scale: fade ? 0.6 : 0.45, opacity: 0 }}
      transition={{ duration: fade ? 0.7 : 0.55, ease: 'easeIn' }}
    >
      <CardView card={card} size="sm" anchor={false} upright />
    </motion.div>
  )
}

function Floater({ e }: { e: FxItem }) {
  const from = e.src!
  const text = e.kind === 'damage' ? `−${e.amount}` : e.kind === 'life' ? `${(e.amount ?? 0) > 0 ? '+' : ''}${e.amount}` : `+${e.amount}`
  const color = e.kind === 'damage' ? (e.objectId ? 'text-orange-400' : 'text-blood-400') : e.kind === 'life' ? ((e.amount ?? 0) > 0 ? 'text-emerald-400' : 'text-blood-400') : 'text-gold-300'
  return (
    <motion.div
      className={`font-display absolute -translate-x-1/2 text-2xl font-bold drop-shadow-[0_1px_2px_rgba(0,0,0,0.9)] ${color}`}
      style={{ left: from.x, top: from.y - 14 }}
      initial={{ y: 0, opacity: 1, scale: 0.8 }}
      animate={{ y: -40, opacity: 0, scale: 1.1 }}
      transition={{ duration: 1.0, ease: 'easeOut' }}
    >
      {text}
      {e.kind === 'counter' && <span className="ml-1 text-xs font-semibold text-gold-200">{e.name}</span>}
    </motion.div>
  )
}
