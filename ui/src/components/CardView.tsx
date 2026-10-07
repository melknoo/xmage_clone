import { memo, useState, type CSSProperties, type MouseEvent } from 'react'
import { cardImageUrl } from '../api/client'
import type { Card, Permanent } from '../api/types'
import { Icon, type IconName } from '../lib/icons'
import { ManaCost, RulesText } from '../lib/mana'

/**
 * Karte nach prototypes/BoardCard.dc.html (Variante c) und README "Kartenzustände".
 * 'special' (Convoke-Bezahlung) wird wie die Mana-Quelle dargestellt.
 */
export type Highlight = 'none' | 'playable' | 'mana' | 'target' | 'chosen' | 'attacking' | 'blocking' | 'special'

/** Groessenstufe (Alias aus der Zeit vor `width`): xs 38 · sm 50 · md 80 · lg 104 · xl 120 · zoom 250 px */
export type CardSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'zoom'

/** px-Breite je Groessenstufe (Werte aus game/layout.ts, volle Groesse) */
export const CARD_SIZE_PX: Record<CardSize, number> = { xs: 38, sm: 50, md: 80, lg: 104, xl: 120, zoom: 250 }

/** Ton eines Karten-Etiketts (Kampf/Ziel, ersetzt die Pfeile) */
export type CardLabelTone = 'attack' | 'block' | 'target' | 'chosen' | 'ember'
/** Etikett unter der Karte, z. B. {text: '→ Kotori', tone: 'attack'}; Text normal geschrieben, Versalien per CSS */
export interface CardLabel {
  text: string
  tone: CardLabelTone
}

/** own: mittig unter der Karte (12 px) · opponent: links (11 px, 2-px-Freistellung in Brettfarbe) */
export type CardLabelVariant = 'own' | 'opponent'

const failed = new Set<string>()

/** Kartenhoehe zur Breite (63 x 88) */
export const cardHeight = (w: number) => Math.round((w * 88) / 63)

/* ---------------------------------------------------------------------------------------------
 * Rahmen-Zustaende und Rang (niedrigster Rang gewinnt)
 * ------------------------------------------------------------------------------------------- */

type Frame = 'none' | 'chosen' | 'target' | 'targetStatic' | 'attack' | 'block' | 'play' | 'mana'

const RANK: Record<Frame, number> = { chosen: 1, target: 2, targetStatic: 2, attack: 3, block: 3, play: 4, mana: 5, none: 9 }

const FRAME_OF: Record<Highlight, Frame> = {
  none: 'none',
  playable: 'play',
  mana: 'mana',
  special: 'mana',
  target: 'target',
  chosen: 'chosen',
  attacking: 'attack',
  blocking: 'block',
}

const RING = (color: string) => `0 0 0 2px ${color}, 0 0 0 4px var(--color-bg-1)`

const FRAME_SHADOW: Record<Frame, string> = {
  none: 'var(--shadow-card)',
  play: RING('var(--color-ember)'),
  mana: '0 0 0 1px color-mix(in oklab, var(--color-ember) 55%, transparent)',
  // die laufende Strichkontur (SVG) bildet den Ring, hier nur der Abstand in Brettfarbe
  target: '0 0 0 4px var(--color-bg-1)',
  targetStatic: RING('var(--color-target)'),
  chosen: RING('var(--color-chosen)'),
  attack: RING('var(--color-attack)'),
  block: RING('var(--color-block)'),
}

/** Abzeichen oben mittig: Klasse (ausgeschrieben fuer Tailwind) + Icon */
const BADGE: Partial<Record<Frame, { cls: string; icon: IconName; title: string }>> = {
  chosen: { cls: 'bg-chosen', icon: 'chosen', title: 'Gewählt' },
  target: { cls: 'bg-target', icon: 'target', title: 'Mögliches Ziel' },
  targetStatic: { cls: 'bg-target', icon: 'target', title: 'Ziel' },
  attack: { cls: 'bg-attack', icon: 'attacker', title: 'Greift an' },
  block: { cls: 'bg-block', icon: 'blocker', title: 'Blockt' },
}

const LABEL_BG: Record<CardLabelTone, string> = {
  attack: 'bg-attack',
  block: 'bg-block',
  target: 'bg-target',
  chosen: 'bg-chosen',
  ember: 'bg-ember',
}

/* ---------------------------------------------------------------------------------------------
 * Overlay-Chips (am Wrapper verankert, bleiben beim Tappen aufrecht)
 * ------------------------------------------------------------------------------------------- */

const CHIP = 'chip-count pointer-events-none absolute z-[2] leading-none'

const CHIP_INVERTED: CSSProperties = { background: 'var(--color-fg-1)', color: 'var(--color-ember-ink)', boxShadow: 'none' }
const CHIP_DAMAGE: CSSProperties = { left: -4, top: -4, background: 'var(--color-attack)', color: 'var(--color-ember-ink)', boxShadow: 'none' }
const CHIP_SICK: CSSProperties = { right: -4, top: -4, padding: 3, color: 'var(--color-fg-3)' }

function counterText(name: string, count: number, short: boolean): string {
  const n = name.toLowerCase()
  if (n === 'p1p1' || n === '+1/+1') return `+${count}/+${count}`
  if (n === 'm1m1' || n === '-1/-1') return `−${count}/−${count}`
  return short ? String(count) : `${count} ${name}`
}

/* ---------------------------------------------------------------------------------------------
 * Rahmen ohne Bild
 * ------------------------------------------------------------------------------------------- */

const FALLBACK_BG = 'linear-gradient(160deg,#2b2723,#141210)'
const FALLBACK_NAME = '#d9d3c7'

/** Text-Rahmen, falls kein Bild verfuegbar (oder noch ladend). Inhalt waechst mit der Breite. */
function TextFrame({ card, w }: { card: Card; w: number }) {
  if (w < 70) {
    return (
      <div className="absolute inset-0 overflow-hidden p-[5px] font-semibold" style={{ background: FALLBACK_BG, color: FALLBACK_NAME, fontSize: 9, lineHeight: 1.2 }}>
        {card.name}
      </div>
    )
  }
  const big = w >= 180
  const mid = w >= 100
  return (
    <div
      className="absolute inset-0 flex flex-col overflow-hidden"
      style={{ background: FALLBACK_BG, color: FALLBACK_NAME, padding: big ? 12 : 5, gap: big ? 8 : 4 }}
    >
      <div className={mid ? 'flex items-start justify-between gap-1' : 'flex flex-col gap-1'}>
        <span className="min-w-0 font-semibold" style={{ fontSize: big ? 15 : mid ? 10.5 : 9, lineHeight: 1.2 }}>
          {card.name}
        </span>
        {card.manaCost && (
          <span className="shrink-0 leading-none" style={{ fontSize: big ? 14 : 9 }}>
            <ManaCost cost={card.manaCost} size="md" flat />
          </span>
        )}
      </div>
      {mid && card.typeLine && (
        <div className="border-t border-line-3 pt-1 text-fg-3" style={{ fontSize: big ? 12.5 : 9, lineHeight: 1.3 }}>
          {card.typeLine}
        </div>
      )}
      {big && card.rules && card.rules.length > 0 && (
        <div className="min-h-0 overflow-hidden text-fg-2" style={{ fontSize: 12.5, lineHeight: 1.45 }}>
          {card.rules.slice(0, 12).map((r, i) => (
            <div key={i} className="mb-1">
              <RulesText text={r} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------------------------------------
 * CardView
 * ------------------------------------------------------------------------------------------- */

export interface CardViewProps {
  card: Card | Permanent
  /** Groessenstufe (Alias, bleibt gueltig); width hat Vorrang */
  size?: CardSize
  /** Kartenbreite in px (game/layout.ts); ueberschreibt size */
  width?: number
  /** Kampf-/Ziel-Etikett unter der Karte; Ton 'target' ohne passenden Rahmen setzt einen festen Ziel-Rahmen (Stapelziel) */
  label?: CardLabel | null
  /** Position des Etiketts: own (Standard) mittig, opponent links und kleiner */
  labelVariant?: CardLabelVariant
  highlight?: Highlight
  onClick?: (e: MouseEvent) => void
  onHover?: (c: Card | null) => void
  showBack?: boolean
  /** abgedunkelt (Deckkraft .45), z. B. Commander nicht in der Zone, nicht waehlbare Dialogkarte */
  dim?: boolean
  /** Klassen fuer den aeusseren Wrapper */
  className?: string
  /** Anzahl gleicher Karten (Chip ×N unten links) */
  count?: number
  /** getappt-Zustand ignorieren (z.B. grosse Vorschau) */
  upright?: boolean
  /** false: kein data-obj (Duplikat, z.B. Commander ausserhalb der Kommandozone) - FX suchen das Original */
  anchor?: boolean
  /** Hover hebt anklickbare Karten um 4 px; false z. B. in der Hand (die hebt selbst) */
  lift?: boolean
}

export const CardView = memo(function CardView({
  card,
  size = 'md',
  width,
  label,
  labelVariant = 'own',
  highlight = 'none',
  onClick,
  onHover,
  showBack,
  dim,
  className = '',
  count,
  upright,
  anchor = true,
  lift = true,
}: CardViewProps) {
  const w = width ?? CARD_SIZE_PX[size] ?? CARD_SIZE_PX.md
  const h = cardHeight(w)
  const perm = card as Permanent
  const isPerm = perm.row !== undefined
  const faceDown = !!card.faceDown

  const url = faceDown ? null : cardImageUrl(card, { back: showBack || card.transformed, size: 'normal' })
  const [loaded, setLoaded] = useState(false)
  const [broken, setBroken] = useState(url ? failed.has(url) : true)
  // anderes Bild (Transformieren, Rueckseite, andere Karte im selben Platz): Ladezustand zuruecksetzen
  const [shownUrl, setShownUrl] = useState(url)
  if (url !== shownUrl) {
    setShownUrl(url)
    setLoaded(false)
    setBroken(url ? failed.has(url) : true)
  }

  const tapped = !!perm.tapped && !upright

  // Rahmen: niedrigster Rang aus Interaktion, Kampfstatus und Stapelziel-Etikett
  let frame: Frame = FRAME_OF[highlight] ?? 'none'
  const consider = (f: Frame) => {
    if (RANK[f] < RANK[frame]) frame = f
  }
  if (perm.attacking) consider('attack')
  if (perm.blocking) consider('block')
  if (label?.tone === 'target') consider('targetStatic')
  const badge = BADGE[frame]

  const clickable = !!onClick && highlight !== 'none'
  const objId = anchor ? card.id : undefined

  // Overlay-Inhalte
  const showPt = isPerm && !faceDown && (perm.power !== undefined || !!perm.loyalty || !!perm.defense)
  const ptText = perm.loyalty ?? perm.defense ?? `${perm.power}/${perm.toughness}`
  const counters = !faceDown
    ? (perm.counters ?? []).filter((c) => {
        const n = c.name.toLowerCase()
        // Loyalitaet/Verteidigung stehen schon im P/T-Chip
        return !((n === 'loyalty' && perm.loyalty) || (n === 'defense' && perm.defense))
      })
    : []
  const sick = isPerm && !!perm.sick && !!perm.types?.includes('CREATURE') && !perm.attacking
  const damage = isPerm ? perm.damage ?? 0 : 0

  const radius = '4.5% / 3.2%'
  const rx = w * 0.045 + 1
  const ry = h * 0.032 + 1

  return (
    <div
      data-obj={objId}
      className={`group/card relative shrink-0 ${clickable ? 'cursor-pointer' : ''} ${className}`}
      style={{ width: tapped ? h : w, height: h }}
      onMouseEnter={onHover ? () => onHover(card) : undefined}
      onMouseLeave={onHover ? () => onHover(null) : undefined}
      onClick={onClick}
    >
      <div
        className={`absolute inset-0 transition-transform duration-2 ease-out ${clickable && lift ? 'group-hover/card:-translate-y-1' : ''}`}
        style={dim ? { opacity: 0.45 } : undefined}
      >
        {/* Karte (dreht sich beim Tappen) */}
        <div
          className="absolute"
          style={{
            left: tapped ? (h - w) / 2 : 0,
            top: 0,
            width: w,
            height: h,
            transform: tapped ? 'rotate(90deg)' : undefined,
            transition: 'transform var(--duration-2) var(--ease-in-out)',
          }}
        >
          <div
            className="absolute inset-0 overflow-hidden"
            style={{
              borderRadius: radius,
              boxShadow: FRAME_SHADOW[frame],
              background: faceDown ? 'var(--color-bg-4)' : FALLBACK_BG,
              transition: `box-shadow ${frame === 'chosen' ? 'var(--duration-1)' : 'var(--duration-2)'} var(--ease-out)`,
            }}
          >
            {faceDown ? (
              <div className="absolute inset-0" style={{ borderRadius: radius, boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }} />
            ) : (
              <>
                {(!loaded || broken) && <TextFrame card={card} w={w} />}
                {url && !broken && (
                  <img
                    src={url}
                    alt={card.name}
                    draggable={false}
                    loading="lazy"
                    className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-3 ${loaded ? 'opacity-100' : 'opacity-0'}`}
                    onLoad={() => setLoaded(true)}
                    onError={() => {
                      failed.add(url)
                      setBroken(true)
                    }}
                  />
                )}
              </>
            )}
          </div>
          {frame === 'target' && (
            <svg className="pointer-events-none absolute overflow-visible" style={{ left: -2, top: -2 }} width={w + 4} height={h + 4} aria-hidden>
              <rect
                x={1}
                y={1}
                width={w + 2}
                height={h + 2}
                rx={rx}
                ry={ry}
                fill="none"
                stroke="var(--color-target)"
                strokeWidth={2}
                strokeDasharray="8 4"
                className="animate-target"
              />
            </svg>
          )}
        </div>

        {/* Abzeichen */}
        {badge && (
          <div
            className={`pointer-events-none absolute left-1/2 top-[-9px] z-[3] flex h-5 w-5 -translate-x-1/2 items-center justify-center rounded-xs text-badge-ink shadow-badge ${badge.cls}`}
            title={badge.title}
            data-card-badge={frame === 'targetStatic' ? 'target' : frame}
          >
            <Icon name={badge.icon} size={12} strokeWidth={2.4} />
          </div>
        )}

        {/* Overlay-Chips */}
        {count !== undefined && count > 1 && (
          <div className={CHIP} style={{ left: -4, bottom: -4 }}>
            ×{count}
          </div>
        )}
        {showPt && (
          <div className={CHIP} style={perm.ptModified ? { right: -4, bottom: -4, ...CHIP_INVERTED } : { right: -4, bottom: -4 }} data-pt-modified={perm.ptModified ? '' : undefined}>
            {ptText}
          </div>
        )}
        {damage > 0 && (
          <div className={CHIP} style={CHIP_DAMAGE} title={`${damage} Schaden`}>
            {'−'}
            {damage}
          </div>
        )}
        {sick && (
          <div className={CHIP} style={CHIP_SICK} title="Einsatzverzögerung">
            <Icon name="sick" size={12} strokeWidth={2} />
          </div>
        )}
        {counters.length > 0 && (
          <div
            className="pointer-events-none absolute z-[2] flex flex-col items-start gap-0.5"
            style={{ left: -4, top: '50%', transform: counters.length > 1 ? 'translateY(-50%)' : undefined }}
          >
            {counters.slice(0, 3).map((c) => (
              <span key={c.name} className="chip-count leading-none" style={{ color: 'var(--color-chosen)' }} title={`${c.count} × ${c.name}`}>
                {counterText(c.name, c.count, w < 60)}
              </span>
            ))}
          </div>
        )}

        {/* Etikett (Kampf/Ziel) */}
        {label && (
          <div
            className={`pointer-events-none absolute z-[5] whitespace-nowrap rounded-xs font-display font-semibold uppercase leading-none tracking-[.06em] text-ember-ink ${LABEL_BG[label.tone]}`}
            style={
              labelVariant === 'opponent'
                ? { left: -4, bottom: -10, padding: '3px 5px', fontSize: 11, boxShadow: '0 0 0 2px var(--color-bg-board)' }
                : { left: '50%', bottom: -10, transform: 'translateX(-50%)', padding: '3px 6px', fontSize: 12 }
            }
            data-card-label={label.tone}
          >
            {label.text}
          </div>
        )}
      </div>
    </div>
  )
})
