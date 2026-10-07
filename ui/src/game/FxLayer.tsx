import { AnimatePresence, motion } from 'motion/react'
import { Fragment, useState } from 'react'
import type { Card, Tempo } from '../api/types'
import { cardHeight, CardView } from '../components/CardView'
import { fxIcon, Icon } from '../lib/icons'
import { cardFlight, DUR, EASE_IN, EASE_OUT, enter, fxTiming } from '../lib/motion'
import { useGame, type FxItem } from '../store/game'
import { lifeAnchor, type Point, type Rect } from './overlayGeometry'

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
 * Mini-Animationen zu Spielereignissen und die Ereignisleiste unten links ueber der Aktionsleiste - damit beim
 * Auto-Passen klar bleibt, was gerade passiert ist:
 * - Zonenwechsel: fliegende Geisterkarte (GhostCard).
 * - Schaden: Funke fliegt von der Quelle zum Ziel, dann Einschlag-Ring und roter Blitz ueber der Karte bzw. Glut an der
 *   Lebensanzeige (HitSpark); die Schadenszahl startet erst beim Einschlag.
 * - Tod: roter Blitz und Splitter ueber der Kartenflaeche (DeathBurst); die Geisterkarte steht so lange in
 *   Kartengroesse an der alten Stelle (das Brett ist dann schon umgebrochen) und fliegt danach.
 * - Lebens-Delta oben rechts an der Lebensanzeige, Zahlen an Objekten (Floater).
 * Betreten und Angriffsstoss laufen als CSS-Animation in Battlefield. Positionen und Wartezeiten erfasst der Store beim
 * Empfang (FxItem); Dauern aus fxTiming/cardFlight, bei Blitz kuerzer. Bei reduzierter Bewegung oder "Effekte aus"
 * erzeugt der Store keine Animationen.
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
          if ((e.kind === 'died' || e.kind === 'tokenDied') && e.rect) {
            return (
              <Fragment key={e.key}>
                <GhostCard e={e} tempo={tempo} />
                <DeathBurst e={e} rect={e.rect} tempo={tempo} />
              </Fragment>
            )
          }
          if (ZONE_KINDS.has(e.kind)) return <GhostCard key={e.key} e={e} tempo={tempo} />
          if (e.kind === 'damage') {
            return (
              <Fragment key={e.key}>
                <HitSpark e={e} tempo={tempo} />
                <Floater e={e} />
              </Fragment>
            )
          }
          if (e.kind === 'life' || e.kind === 'counter') return <Floater key={e.key} e={e} />
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

/** Eckenradius der Kartenflaeche (getappt quer) */
const faceRadius = (r: Rect) => (r.width > r.height ? '3.2% / 4.5%' : '4.5% / 3.2%')

function GhostCard({ e, tempo }: { e: FxItem; tempo: Tempo }) {
  // Tod mit Kartenflaeche: steht zuerst in Kartengroesse (getappt quer) an der alten Stelle, bis Funke und Zerbersten
  // durch sind - das Brett ist dann schon umgebrochen -, dann fliegt bzw. verblasst sie
  const r = e.rect
  const from = r ? { x: r.left + r.width / 2, y: r.top + r.height / 2 } : e.src!
  const fade = e.kind === 'tokenDied'
  const to = fade && r ? from : (e.dst ?? { x: from.x, y: from.y - 40 })
  const card: Card = e.card ?? { id: e.objectId ?? e.key.toString(), name: e.name ?? '' }
  const w = r ? Math.max(24, Math.round(Math.min(r.width, r.height))) : GHOST_W
  const h = cardHeight(w)
  const rot = r && r.width > r.height ? 90 : 0
  const delay = r ? ((e.delay ?? 0) + fxTiming(tempo).death * 0.55) / 1000 : 0
  // 420 ms, bei Blitz 210 ms; ausgeblendet wird erst gegen Ende
  const flight = cardFlight(tempo)
  return (
    <motion.div
      className="absolute"
      style={{ left: from.x - w / 2, top: from.y - h / 2, width: w }}
      initial={{ x: 0, y: 0, scale: 1, rotate: rot, opacity: 0.95 }}
      animate={{ x: to.x - from.x, y: to.y - from.y, scale: fade ? 0.8 : (0.5 * GHOST_W) / w, rotate: fade ? rot : 0, opacity: [0.95, 0.95, 0] }}
      transition={{ ...flight, delay, opacity: { ...flight, delay, times: [0, 0.7, 1] } }}
    >
      <CardView card={card} width={w} anchor={false} upright />
    </motion.div>
  )
}

/** Funke: heller Kern, Glut in Ember/Karmin, Schweif nach hinten */
const SPARK_CORE = 'radial-gradient(circle, var(--color-fg-1) 0 25%, var(--color-ember) 65%)'
const SPARK_GLOW = '0 0 6px 2px var(--color-ember), 0 0 14px 4px color-mix(in oklab, var(--color-attack) 65%, transparent)'
const SPARK_TRAIL = 'linear-gradient(to right, transparent, color-mix(in oklab, var(--color-attack) 70%, transparent) 55%, var(--color-ember))'
const RING_GLOW = '0 0 10px color-mix(in oklab, var(--color-ember) 60%, transparent)'
const BURST_BG =
  'radial-gradient(circle, color-mix(in oklab, var(--color-ember) 85%, transparent) 0%, color-mix(in oklab, var(--color-attack) 55%, transparent) 40%, transparent 70%)'

/** Treffer-Funke: gluehender Kern mit kurzem Schweif, beschleunigt von der Quelle zum Ziel */
function Spark({ from, to, ms }: { from: Point; to: Point; ms: number }) {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const trail = Math.min(40, Math.hypot(dx, dy) * 0.35)
  const s = ms / 1000
  return (
    // 0x0-Element am Start: dreht um den Kern in Flugrichtung, x/y laufen in Bildschirmkoordinaten
    <motion.div
      className="absolute"
      style={{ left: from.x, top: from.y, rotate: (Math.atan2(dy, dx) * 180) / Math.PI }}
      initial={{ x: 0, y: 0, opacity: 0 }}
      animate={{ x: dx, y: dy, opacity: [0, 1, 1, 0] }}
      transition={{ duration: s, ease: EASE_IN, opacity: { duration: s, times: [0, 0.12, 0.9, 1] } }}
    >
      <div className="absolute rounded-full" style={{ right: 0, top: -1.5, width: trail, height: 3, background: SPARK_TRAIL }} />
      <div className="absolute rounded-full" style={{ left: -4, top: -4, width: 8, height: 8, background: SPARK_CORE, boxShadow: SPARK_GLOW }} />
    </motion.div>
  )
}

/**
 * Schaden: Funke von der Quelle (falls sichtbar), beim Einschlag Ring + roter Blitz ueber der Karte bzw. Glut an der
 * Lebensanzeige. e.src = Ziel (Kartenmitte bzw. Mitte der Lebensanzeige), e.delay = Flugzeit.
 */
function HitSpark({ e, tempo }: { e: FxItem; tempo: Tempo }) {
  const t = fxTiming(tempo)
  const at = e.src!
  const r = e.rect
  const impact = { duration: t.impact / 1000, delay: (e.delay ?? 0) / 1000, ease: EASE_OUT }
  const ring = r ? Math.min(64, Math.max(26, Math.min(r.width, r.height) * 0.7)) : 44
  return (
    <>
      {e.hit && <Spark from={e.hit} to={at} ms={t.hit} />}
      {r ? (
        <motion.div
          className="absolute bg-attack"
          style={{ left: r.left, top: r.top, width: r.width, height: r.height, borderRadius: faceRadius(r) }}
          initial={{ opacity: 0 }}
          animate={{ opacity: [0, 0.5, 0] }}
          transition={{ ...impact, times: [0, 0.2, 1] }}
        />
      ) : (
        <motion.div
          className="absolute rounded-full"
          style={{ left: at.x - 32, top: at.y - 32, width: 64, height: 64, background: BURST_BG }}
          initial={{ scale: 0.4, opacity: 0 }}
          animate={{ scale: 1.2, opacity: [0, 1, 0] }}
          transition={{ ...impact, opacity: { ...impact, times: [0, 0.25, 1] } }}
        />
      )}
      <motion.div
        className="absolute rounded-full border-2 border-attack"
        style={{ left: at.x - ring / 2, top: at.y - ring / 2, width: ring, height: ring, boxShadow: RING_GLOW }}
        initial={{ scale: 0.3, opacity: 0 }}
        animate={{ scale: [0.3, 1.5], opacity: [0.95, 0] }}
        transition={impact}
      />
    </>
  )
}

const SHARDS = 7
/** Splitterfarben reihum (ausgeschrieben fuer Tailwind) */
const SHARD_BG = ['bg-attack', 'bg-ember', 'bg-fg-2'] as const

/** fester Pseudo-Zufall 0..1 je Ereignis, damit Re-Renders die Splitter nicht verschieben */
function rand(seed: number, n: number): number {
  const x = Math.sin(seed * 12.9898 + n * 78.233) * 43758.5453
  return x - Math.floor(x)
}

/** Tod: roter Blitz ueber der Kartenflaeche, Splitter fliegen drehend nach aussen (nach e.delay = Rest eines Funkens) */
function DeathBurst({ e, rect: r, tempo }: { e: FxItem; rect: Rect; tempo: Tempo }) {
  const s = fxTiming(tempo).death / 1000
  const delay = (e.delay ?? 0) / 1000
  const cx = r.left + r.width / 2
  const cy = r.top + r.height / 2
  return (
    <>
      <motion.div
        className="absolute bg-attack"
        style={{ left: r.left, top: r.top, width: r.width, height: r.height, borderRadius: faceRadius(r) }}
        initial={{ opacity: 0 }}
        animate={{ opacity: [0, 0.6, 0] }}
        transition={{ duration: s * 0.7, delay, ease: EASE_OUT, times: [0, 0.2, 1] }}
      />
      {Array.from({ length: SHARDS }, (_, i) => {
        const a = ((i + rand(e.key, i) * 0.6 - 0.3) / SHARDS) * Math.PI * 2
        const cos = Math.cos(a)
        const sin = Math.sin(a)
        const reach = 18 + rand(e.key, i + 10) * 26
        const w = 4 + Math.round(rand(e.key, i + 20) * 3)
        const h = 6 + Math.round(rand(e.key, i + 30) * 4)
        const spin = (i % 2 ? -1 : 1) * (140 + rand(e.key, i + 40) * 160)
        return (
          <motion.div
            key={i}
            className={`absolute rounded-xs ${SHARD_BG[i % SHARD_BG.length]}`}
            style={{ left: cx - w / 2, top: cy - h / 2, width: w, height: h }}
            initial={{ x: cos * r.width * 0.25, y: sin * r.height * 0.25, rotate: 0, scale: 1, opacity: 0 }}
            animate={{ x: cos * (r.width / 2 + reach), y: sin * (r.height / 2 + reach), rotate: spin, scale: 0.6, opacity: [1, 1, 0] }}
            transition={{ duration: s, delay, ease: EASE_OUT, opacity: { duration: s, delay, times: [0, 0.55, 1] } }}
          />
        )
      })}
    </>
  )
}

function Floater({ e }: { e: FxItem }) {
  // Spieler-Leben/-Schaden: oben rechts an der Lebensanzeige (Position beim Erscheinen, die Anzeige bewegt sich nicht)
  const [pos] = useState(() => (!e.objectId && e.playerId && (e.kind === 'damage' || e.kind === 'life') ? lifeAnchor(e.playerId) : null))
  const at = pos ?? e.src!
  const amount = e.amount ?? 0
  const text = e.kind === 'damage' ? `−${amount}` : e.kind === 'life' ? (amount > 0 ? `+${amount}` : `−${Math.abs(amount)}`) : `+${amount}`
  const color = e.kind === 'counter' ? 'text-target' : e.kind === 'life' && amount > 0 ? 'text-chosen' : 'text-attack'
  // Schaden mit Funke: Zahl erst beim Einschlag
  const delay = e.kind === 'damage' ? (e.delay ?? 0) / 1000 : 0
  return (
    <motion.div
      className={`absolute font-display text-[22px] leading-none font-semibold whitespace-nowrap tabular-nums ${color} ${pos ? '' : '-translate-x-1/2'}`}
      style={pos ? { left: at.x + 2, top: at.y - 18 } : { left: at.x, top: at.y - 14 }}
      initial={{ y: 0, opacity: delay ? 0 : 1 }}
      animate={{ y: -20, opacity: [1, 1, 0] }}
      transition={{ duration: DUR.xp, ease: EASE_OUT, delay, opacity: { duration: DUR.xp, delay, times: [0, 0.6, 1] } }}
    >
      {text}
      {e.kind === 'counter' && e.name && <span className="ml-1 font-sans text-[12px] font-semibold">{e.name}</span>}
    </motion.div>
  )
}
