import { motion } from 'motion/react'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import type { GameState, Prompt, UUID } from '../api/types'
import { fxTiming } from '../lib/motion'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'
import { useBoardLayout } from './layout'
import {
  curve,
  edgeToward,
  entityRect,
  objRect,
  playerRect,
  rectCenter,
  stackRect,
  zoneAnchor,
  zoneKey,
  type Point,
  type Rect,
} from './overlayGeometry'

/*
 * Pfeile wie in MTG Arena, aber nur im Kontext (das Redesign hatte Dauerpfeile entfernt, weil sie Karten verdeckten):
 * - Ziel: Stapelobjekt unter der Maus (sonst das oberste) -> seine Ziele; waehrend der Zielwahl Quelle -> gewaehlte Ziele.
 * - Quelle: Faehigkeit auf dem Stapel <- ihre Quellkarte (Rahmen um die Karte), frisch ausgeloest pulsierend.
 * - Wirken: frischer fremder Zauber <- Pod des Wirkenden (blendet aus).
 * - Kampf: Angreifer -> Verteidiger, Blocker -> Angreifer (gedaempft, das gehoverte Objekt voll).
 * - Live: waehrend der Zielwahl folgt ein Pfeil der Maus und rastet auf gueltigen Zielen ein (LiveArrow).
 * Start/Ende an der Kartenkante (edgeToward), leicht gewoelbt (curve). Ebene z-8: ueber Brett und Stapel-Panel, unter
 * FX (10), Banner (15) und Dialogen (20).
 */

type Kind = 'target' | 'source' | 'cast' | 'attack' | 'block'

/** Endpunkt eines Pfeils: Rechteck eines Spielers/Objekts/Stapelobjekts oder fester Punkt (Zone) */
type End = { id: UUID; as: 'entity' | 'obj' | 'player' | 'stack' } | { point: () => Point | null }

interface Spec {
  key: string
  kind: Kind
  from: End
  to: End
  /** hervorgehoben (Stapelzeile/Objekt unter der Maus) */
  strong?: boolean
  /** frisch auf dem Stapel (Puls, Linie leuchtet nach) */
  fresh?: number
  /** Rahmen um die Quelle zeichnen */
  ring?: boolean
}

interface Line {
  key: string
  kind: Kind
  d: string
  strong: boolean
  fresh?: number
  ring?: Rect
}

const COLOR: Record<Kind, string> = {
  target: 'var(--color-target)',
  source: 'var(--color-ember)',
  cast: 'var(--color-ember)',
  attack: 'var(--color-attack)',
  block: 'var(--color-block)',
}

/** Spitzen je Farbe (marker-ids) */
const HEAD: Partial<Record<Kind, string>> = { target: 'ml-arrow-target', cast: 'ml-arrow-cast', attack: 'ml-arrow-attack', block: 'ml-arrow-block' }

function rectOfEnd(e: End): Rect | null {
  if ('point' in e) return null
  switch (e.as) {
    case 'obj':
      return objRect(e.id)
    case 'player':
      return playerRect(e.id)
    case 'stack':
      return stackRect(e.id)
    default:
      return entityRect(e.id)
  }
}

/** Stapelzeilen liegen rechts im eigenen Bereich: Pfeile immer an der linken Kante (nicht durch Kopf/Nachbarzeilen) */
function stackLeft(r: Rect): Point {
  return { x: r.left - 3, y: r.top + r.height / 2 }
}

const isStack = (e: End) => !('point' in e) && e.as === 'stack'

/** Pfeil von Kante zu Kante (bzw. zu einem festen Punkt); null, wenn ein Ende nicht sichtbar ist */
function measure(s: Spec): Line | null {
  const fr = rectOfEnd(s.from)
  const tr = rectOfEnd(s.to)
  const fp = fr ? rectCenter(fr) : 'point' in s.from ? s.from.point() : null
  const tp = tr ? rectCenter(tr) : 'point' in s.to ? s.to.point() : null
  if (!fp || !tp) return null
  const a = fr ? (isStack(s.from) ? stackLeft(fr) : edgeToward(fr, tp)) : fp
  const b = tr ? (isStack(s.to) ? stackLeft(tr) : edgeToward(tr, fp, s.kind === 'source' ? 3 : 5)) : tp
  if (Math.hypot(b.x - a.x, b.y - a.y) < 8) return null
  return { key: s.key, kind: s.kind, d: curve(a, b), strong: !!s.strong, fresh: s.fresh, ring: s.ring && fr ? fr : undefined }
}

const MAX_FRESH = 6

/** Pfeil-Beschreibungen aus State, Stapel-Fokus, Prompt und Hover (rein, ohne DOM) */
function specs(state: GameState, focus: UUID | null, stackNew: Record<UUID, number>, hoverId: UUID | null, prompt: Prompt | null, targeting: boolean): Spec[] {
  const out: Spec[] = []
  const meId = state.players.find((p) => p.me)?.id
  const ownerByName = new Map(state.players.map((p) => [p.name, p.id]))

  // Stapel: Ziele und Quelle des fokussierten bzw. obersten Objekts
  const item = (focus ? state.stack.find((c) => c.id === focus) : undefined) ?? state.stack[0]
  if (item) {
    const strong = !!focus && focus === item.id
    const seen = new Set<UUID>()
    for (const t of item.targetRefs ?? []) {
      seen.add(t.id)
      const owner = t.owner ? ownerByName.get(t.owner) : undefined
      const zone = t.kind === 'card' ? zoneKey(t.zone) : null
      out.push({
        key: `t:${item.id}:${t.id}`,
        kind: 'target',
        from: { id: item.id, as: 'stack' },
        // Karte im Friedhof/Exil/in der Hand: Zonen-Knopf des Besitzers
        to: zone ? { point: () => zoneAnchor(owner, zone) } : { id: t.id, as: 'entity' },
        strong,
      })
    }
    for (const id of item.targets ?? []) {
      if (!seen.has(id)) out.push({ key: `t:${item.id}:${id}`, kind: 'target', from: { id: item.id, as: 'stack' }, to: { id, as: 'entity' }, strong })
    }
    if (item.kind === 'ability' && item.sourceId) {
      out.push({ key: `s:${item.id}`, kind: 'source', from: { id: item.sourceId, as: 'obj' }, to: { id: item.id, as: 'stack' }, strong, ring: true, fresh: stackNew[item.id] })
    }
  }

  // frisch ausgeloest/gewirkt (nicht das schon gezeigte Objekt): Quelle pulsiert bzw. Linie vom Wirkenden
  const sources = new Set<UUID>()
  if (item?.sourceId) sources.add(item.sourceId)
  let fresh = 0
  for (const c of state.stack) {
    const at = stackNew[c.id]
    if (at === undefined || fresh >= MAX_FRESH) continue
    if (c.kind === 'ability' && c.sourceId) {
      if (c.id === item?.id || sources.has(c.sourceId)) continue
      sources.add(c.sourceId)
      out.push({ key: `s:${c.id}`, kind: 'source', from: { id: c.sourceId, as: 'obj' }, to: { id: c.id, as: 'stack' }, ring: true, fresh: at })
      fresh++
    } else if (c.kind !== 'ability' && c.controllerId && c.controllerId !== meId) {
      out.push({ key: `c:${c.id}`, kind: 'cast', from: { id: c.controllerId, as: 'player' }, to: { id: c.id, as: 'stack' }, fresh: at })
      fresh++
    }
  }

  // Zielwahl: Quelle -> schon gewaehlte Ziele (der Live-Pfeil zeigt das naechste)
  if (targeting && prompt?.sourceId) {
    for (const id of prompt.chosen ?? []) {
      out.push({ key: `p:${prompt.sourceId}:${id}`, kind: 'target', from: { id: prompt.sourceId, as: 'entity' }, to: { id, as: 'entity' }, strong: true })
    }
  }

  // Kampf
  for (const g of state.combat ?? []) {
    for (const a of g.attackers) {
      const hot = hoverId === a || hoverId === g.defenderId
      out.push({ key: `a:${a}`, kind: 'attack', from: { id: a, as: 'obj' }, to: { id: g.defenderId, as: 'entity' }, strong: hot })
      for (const b of g.blockers) {
        out.push({ key: `b:${b}:${a}`, kind: 'block', from: { id: b, as: 'obj' }, to: { id: a, as: 'obj' }, strong: hoverId === b || hoverId === a })
      }
    }
  }
  return out
}

/** Schluessel fuer die Neuberechnung: Art, Enden, Hervorhebung */
function specKey(list: Spec[]): string {
  return list.map((s) => `${s.key}${s.strong ? '!' : ''}${s.fresh ?? ''}`).join('|')
}

export function ArrowLayer({ inter }: { inter: Interaction }) {
  const enabled = useGame((s) => s.arrows)
  const state = useGame((s) => s.state)
  const focus = useGame((s) => s.stackFocus)
  const stackNew = useGame((s) => s.stackNew)
  const hoverId = useGame((s) => s.hover?.id ?? null)
  const tempo = useGame((s) => s.tempo)
  const fxEnabled = useGame((s) => s.fxEnabled)
  const layout = useBoardLayout()
  const targeting = inter.mode === 'target' && !inter.needsCardModal && !inter.prompt?.defenderPick
  const list = useMemo(
    () => (enabled && state ? specs(state, focus, stackNew, hoverId, inter.prompt, targeting) : []),
    [enabled, state, focus, stackNew, hoverId, inter.prompt, targeting],
  )
  const key = specKey(list)
  const [lines, setLines] = useState<Line[]>([])
  const listRef = useRef(list)
  listRef.current = list

  useLayoutEffect(() => {
    let raf = 0
    const compute = () => {
      raf = 0
      const next: Line[] = []
      // zusammengefasste Karten (×N) teilen sich ein Element -> gleiche Strecke nur einmal
      const seen = new Set<string>()
      for (const s of listRef.current) {
        const l = measure(s)
        if (!l) continue
        const k = `${l.kind}${l.d}`
        if (seen.has(k)) continue
        seen.add(k)
        next.push(l)
      }
      setLines(next)
    }
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(compute)
    }
    compute()
    // Einblend-/Tapp-Animationen, eingeklappter Stapel, Scrollen in den Feldern
    const t1 = window.setTimeout(schedule, 250)
    const t2 = window.setTimeout(schedule, 600)
    window.addEventListener('resize', schedule)
    window.addEventListener('scroll', schedule, true)
    document.addEventListener('transitionend', schedule, true)
    document.addEventListener('animationend', schedule, true)
    return () => {
      cancelAnimationFrame(raf)
      window.clearTimeout(t1)
      window.clearTimeout(t2)
      window.removeEventListener('resize', schedule)
      window.removeEventListener('scroll', schedule, true)
      document.removeEventListener('transitionend', schedule, true)
      document.removeEventListener('animationend', schedule, true)
    }
  }, [key, state?.seq, layout.compact, layout.handW])

  if (!enabled || !state) return null
  const animate = fxEnabled
  const pulseMs = fxTiming(tempo).trigger
  return (
    <svg className="pointer-events-none fixed inset-0 z-[8] h-full w-full" aria-hidden data-testid="arrow-layer">
      <defs>
        {(Object.keys(HEAD) as Kind[]).map((k) => (
          <marker key={k} id={HEAD[k]} viewBox="0 0 10 10" refX="7" refY="5" markerWidth="4.5" markerHeight="4.5" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" style={{ fill: COLOR[k] }} />
          </marker>
        ))}
      </defs>
      {lines.map((l) => (
        <ArrowPath key={l.key} l={l} animate={animate} pulseMs={pulseMs} />
      ))}
      {targeting && inter.prompt?.sourceId && <LiveArrow prompt={inter.prompt} />}
    </svg>
  )
}

/** Deckkraft je Art (normal / hervorgehoben) */
const OPACITY: Record<Kind, [number, number]> = {
  target: [0.85, 1],
  source: [0.6, 0.95],
  cast: [0.8, 0.8],
  attack: [0.5, 0.95],
  block: [0.55, 0.95],
}

function ArrowPath({ l, animate, pulseMs }: { l: Line; animate: boolean; pulseMs: number }) {
  const color = COLOR[l.kind]
  const [base, hot] = OPACITY[l.kind]
  const width = l.kind === 'source' ? 1.75 : l.strong ? 2.75 : 2.25
  const style: CSSProperties = { stroke: color, opacity: l.strong ? hot : base, filter: l.strong ? `drop-shadow(0 0 3px ${color})` : undefined }
  const head = HEAD[l.kind] ? `url(#${HEAD[l.kind]})` : undefined
  const dashed = l.kind === 'target'
  const fresh = l.fresh !== undefined && animate
  const s = pulseMs / 1000
  return (
    <g data-arrow={l.kind}>
      {l.kind === 'cast' ? (
        // Wirken: nur solange frisch, blendet aus
        <motion.path d={l.d} fill="none" strokeWidth={2.25} strokeLinecap="round" markerEnd={head} style={{ stroke: color }} initial={{ opacity: 0.9 }} animate={{ opacity: 0 }} transition={{ duration: s, ease: 'easeIn' }} />
      ) : (
        <path
          d={l.d}
          fill="none"
          strokeWidth={width}
          strokeLinecap="round"
          strokeDasharray={dashed ? '7 5' : undefined}
          className={dashed && animate ? 'animate-arrow-dash' : undefined}
          markerEnd={head}
          style={style}
        />
      )}
      {fresh && l.kind === 'source' && (
        // frisch ausgeloest: Linie leuchtet kurz nach
        <motion.path d={l.d} fill="none" strokeWidth={4} strokeLinecap="round" style={{ stroke: color }} initial={{ opacity: 0.9 }} animate={{ opacity: 0 }} transition={{ duration: s, ease: 'easeOut' }} />
      )}
      {l.ring && <SourceRing r={l.ring} strong={l.strong} fresh={fresh} s={s} />}
    </g>
  )
}

/** Rahmen um die Quellkarte einer Faehigkeit; frisch ausgeloest mit Puls nach aussen */
function SourceRing({ r, strong, fresh, s }: { r: Rect; strong: boolean; fresh: boolean; s: number }) {
  const pad = 3
  const box = { x: r.left - pad, y: r.top - pad, width: r.width + pad * 2, height: r.height + pad * 2, rx: Math.min(r.width, r.height) * 0.06 + pad }
  const stroke = { stroke: 'var(--color-ember)' }
  return (
    <>
      <rect {...box} fill="none" strokeWidth={2} style={{ ...stroke, opacity: strong || fresh ? 0.95 : 0.7 }} />
      {fresh && (
        <motion.rect
          {...box}
          fill="none"
          strokeWidth={3}
          style={{ ...stroke, transformBox: 'fill-box', transformOrigin: 'center' }}
          initial={{ scale: 1, opacity: 0.9 }}
          animate={{ scale: 1.22, opacity: 0 }}
          transition={{ duration: s * 0.8, ease: 'easeOut' }}
        />
      )}
    </>
  )
}

/** id des Elements unter der Maus, wenn es ein waehlbares Ziel ist (Karte, ×N-Stapel, Spieler, Stapelobjekt) */
function targetUnder(el: Element | null, targets: Set<UUID>): { id: UUID; rect: Rect } | null {
  const hit = el?.closest('[data-obj],[data-objs],[data-player],[data-stack]')
  if (!hit) return null
  const ids = [hit.getAttribute('data-obj'), hit.getAttribute('data-player'), hit.getAttribute('data-stack'), ...(hit.getAttribute('data-objs')?.split(' ') ?? [])]
  const id = ids.find((x): x is string => !!x && targets.has(x))
  if (!id) return null
  const face = hit.hasAttribute('data-player') ? (hit.querySelector('[data-life]') ?? hit) : (hit.querySelector('[data-card-face]') ?? hit)
  const r = face.getBoundingClientRect()
  return { id, rect: { left: r.left, top: r.top, width: r.width, height: r.height } }
}

/**
 * Zielwahl wie in Arena: Pfeil von der Quelle (Handkarte, Permanent, Stapelobjekt) zur Maus; ueber einem gueltigen
 * Ziel rastet er an dessen Kante ein und wird kraeftiger, das Ziel selbst wird hervorgehoben (store.aimId ->
 * Highlight 'aimed'). Zeichnet per Ref (rAF), ohne React-Render je Mausbewegung; aimId aendert sich nur beim Wechsel.
 */
function LiveArrow({ prompt }: { prompt: Prompt }) {
  const pathRef = useRef<SVGPathElement>(null)
  const sourceId = prompt.sourceId!
  const targetsKey = (prompt.targets ?? []).join(',')
  const chosenKey = (prompt.chosen ?? []).join(',')

  useEffect(() => {
    const path = pathRef.current
    if (!path) return
    const chosen = new Set(chosenKey ? chosenKey.split(',') : [])
    const targets = new Set((targetsKey ? targetsKey.split(',') : []).filter((id) => id !== sourceId && !chosen.has(id)))
    let raf = 0
    let last: PointerEvent | null = null
    const setAim = useGame.getState().setAim
    const hide = () => {
      path.style.opacity = '0'
      setAim(null)
    }
    const draw = () => {
      raf = 0
      const obj = last ? objRect(sourceId) : null
      const row = last && !obj ? stackRect(sourceId) : null
      const src = obj ?? row
      if (!last || !src) return hide()
      const mouse = { x: last.clientX, y: last.clientY }
      const snap = targetUnder(last.target instanceof Element ? last.target : null, targets)
      // Maus noch auf der Quelle: kein Pfeil
      if (!snap && mouse.x >= src.left && mouse.x <= src.left + src.width && mouse.y >= src.top && mouse.y <= src.top + src.height) return hide()
      setAim(snap?.id ?? null)
      const end = snap ? edgeToward(snap.rect, rectCenter(src), 5) : mouse
      const start = row ? stackLeft(row) : edgeToward(src, snap ? rectCenter(snap.rect) : mouse)
      path.setAttribute('d', curve(start, end))
      path.style.opacity = snap ? '1' : '0.75'
      path.style.strokeWidth = snap ? '3' : '2.25'
      path.style.filter = snap ? 'drop-shadow(0 0 4px var(--color-target))' : ''
    }
    const onMove = (e: PointerEvent) => {
      last = e
      if (!raf) raf = requestAnimationFrame(draw)
    }
    const onOut = (e: PointerEvent) => {
      if (!e.relatedTarget) {
        last = null
        hide()
      }
    }
    window.addEventListener('pointermove', onMove)
    document.addEventListener('pointerout', onOut)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerout', onOut)
      setAim(null)
    }
  }, [sourceId, targetsKey, chosenKey])

  return (
    <path
      ref={pathRef}
      fill="none"
      strokeWidth={2.25}
      strokeLinecap="round"
      strokeDasharray="7 5"
      markerEnd={`url(#${HEAD.target})`}
      style={{ stroke: 'var(--color-target)', opacity: 0 }}
      data-arrow="live"
    />
  )
}
