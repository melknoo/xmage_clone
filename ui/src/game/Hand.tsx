import { memo, useLayoutEffect, useRef, useState } from 'react'
import type { Card } from '../api/types'
import { CardView } from '../components/CardView'
import { Icon } from '../lib/icons'
import { me as meOf, useGame } from '../store/game'
import { useUi } from '../store/ui'
import type { Interaction } from './interaction'
import type { BoardLayoutState } from './layout'

const NO_CARDS: Card[] = []
/** Innenabstand links/rechts der Reihe */
const SIDE_PAD = 14
/** linker Rand des Labels (left-[14px]) */
const LABEL_LEFT = 14
/** Unterkante des Labels (top-3 + Zeilenhoehe) */
const LABEL_BOTTOM = 30
const LIFT_PLAYABLE = 6
const LIFT_HOVER = 16

export interface HandProps {
  inter: Interaction
  onHover: (c: Card | null) => void
  /** handW, handH, handGap */
  layout: BoardLayoutState
}

/** Groesse des Containers (fuer Ueberlappung, wenn die Karten nicht nebeneinander passen) */
function useSize() {
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const read = () => setSize((o) => (o.w === el.clientWidth && o.h === el.clientHeight ? o : { w: el.clientWidth, h: el.clientHeight }))
    read()
    const ro = new ResizeObserver(read)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, size] as const
}

/** Abstand zwischen Karten: Standard gap, bei Platzmangel negativ (Ueberlappung) */
function spacing(n: number, cardW: number, gap: number, usable: number): number {
  if (n < 2 || usable <= 0) return gap
  if (n * cardW + (n - 1) * gap <= usable) return gap
  return Math.max((usable - cardW) / (n - 1), 12) - cardW
}

/**
 * Hand-Reihe (Hoehe, Linie und bg-board liefert die GameScreen-Zeile; hier h-full): Karten in einer Reihe ohne Faecher, spielbar 6 px angehoben, Hover 16 px.
 * Label "Hand N · K spielbar". Waehrend der Mulligan-Frage liegen die Karten im Dialog ("Starthand im Dialog").
 * Zuschauer: "Hand N · verdeckt" mit neutralen Platzhaltern. data-zone=hand + data-owner sind FX-Anker.
 */
export const Hand = memo(function Hand({ inter, onHover, layout }: HandProps) {
  const cards = useGame((s) => s.state?.hand ?? NO_CARDS)
  const owner = useGame((s) => meOf(s.state))
  const spectator = useGame((s) => s.spectator)
  const minimized = useUi((s) => s.dialogMinimized)
  const [hovered, setHovered] = useState<string | null>(null)
  const [ref, { w: width, h: height }] = useSize()
  const labelRef = useRef<HTMLSpanElement>(null)
  const [labelW, setLabelW] = useState(0)

  const mulligan = !spectator && !!inter.prompt?.mulligan && !minimized
  const hiddenN = spectator ? (owner?.handCount ?? 0) : 0
  const shown = spectator || mulligan ? NO_CARDS : cards
  const playable = shown.filter((c) => inter.highlight(c.id) === 'playable').length
  const n = spectator ? hiddenN : shown.length
  const cardH = Math.round((layout.handW * 88) / 63)

  let label: string
  if (spectator) label = `Hand ${hiddenN} · verdeckt`
  else if (mulligan) label = 'Starthand im Dialog'
  else label = `Hand ${cards.length}${playable > 0 ? ` · ${playable} spielbar` : ''}`

  useLayoutEffect(() => {
    setLabelW(labelRef.current?.offsetWidth ?? 0)
  }, [label])

  // Zentriert, solange die Reihe das Label links nicht beruehrt (unter dem Label genug Hoehe oder links frei);
  // sonst rueckt sie rechts neben das Label
  const rowW = n * layout.handW + Math.max(0, n - 1) * layout.handGap
  const labelRight = LABEL_LEFT + labelW + 12
  const belowLabel = height - 8 - cardH - LIFT_PLAYABLE >= LABEL_BOTTOM
  const padLeft = belowLabel || (width - rowW) / 2 >= labelRight ? SIDE_PAD : labelRight
  const step = spacing(n, layout.handW, layout.handGap, width - padLeft - SIDE_PAD)

  return (
    <div
      ref={ref}
      data-testid="hand"
      data-zone="hand"
      data-owner={owner?.id}
      className="relative flex h-full min-h-0 w-full items-end justify-center"
      style={{ paddingBottom: 8, paddingLeft: padLeft, paddingRight: SIDE_PAD }}
    >
      <span ref={labelRef} className="label pointer-events-none absolute left-[14px] top-3 z-[30] flex items-center gap-1.5">
        <Icon name="hand" size={14} />
        {label}
      </span>
      {spectator
        ? Array.from({ length: hiddenN }, (_, i) => (
            <div
              key={i}
              className="relative shrink-0 rounded-[4.5%/3.2%] bg-bg-3 shadow-[inset_0_0_0_1px_var(--color-line-3)]"
              style={{ width: layout.handW, height: cardH, marginLeft: i === 0 ? 0 : step, zIndex: i }}
              aria-hidden
            >
              <div className="absolute inset-[8%] rounded-xs shadow-[inset_0_0_0_1px_var(--color-line-2)]" />
            </div>
          ))
        : shown.map((c, i) => {
            const h = inter.highlight(c.id)
            const lift = hovered === c.id ? LIFT_HOVER : h !== 'none' ? LIFT_PLAYABLE : 0
            return (
              <div
                key={c.id}
                className="relative shrink-0 transition-transform duration-2 ease-out"
                style={{ marginLeft: i === 0 ? 0 : step, transform: `translateY(${-lift}px)`, zIndex: hovered === c.id ? 20 : i }}
                onMouseEnter={() => setHovered(c.id)}
                onMouseLeave={() => setHovered((x) => (x === c.id ? null : x))}
              >
                <CardView card={c} width={layout.handW} lift={false} highlight={h} onHover={onHover} onClick={() => inter.click(c.id)} />
              </div>
            )
          })}
    </div>
  )
})
