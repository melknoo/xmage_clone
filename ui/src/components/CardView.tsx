import { memo, useState, type MouseEvent } from 'react'
import { cardImageUrl } from '../api/client'
import type { Card, Permanent } from '../api/types'
import { ManaCost, RulesText } from '../lib/mana'

export type Highlight = 'none' | 'playable' | 'mana' | 'target' | 'chosen' | 'attacking' | 'blocking'

const failed = new Set<string>()

const SIZES = {
  xs: 'w-[46px]',
  sm: 'w-[62px]',
  md: 'w-[84px]',
  lg: 'w-[112px]',
  xl: 'w-[150px]',
  zoom: 'w-[300px]',
} as const

const WIDTH_PX: Record<keyof typeof SIZES, number> = { xs: 46, sm: 62, md: 84, lg: 112, xl: 150, zoom: 300 }

export type CardSize = keyof typeof SIZES

const FRAME_COLORS: Record<string, string> = {
  W: 'from-[#f3ecd2] to-[#d9cfa8] text-ink-950',
  U: 'from-[#2a6fb8] to-[#174a82]',
  B: 'from-[#3a3340] to-[#1d1922]',
  R: 'from-[#c4452b] to-[#8a2716]',
  G: 'from-[#2f7d46] to-[#1b5230]',
  M: 'from-[#d6a842] to-[#9b7322] text-ink-950',
  C: 'from-[#8e95a3] to-[#5f6573]',
  L: 'from-[#8a7356] to-[#5c4a35]',
}

function frameKey(card: Card): string {
  if (card.types?.includes('LAND')) return 'L'
  const c = card.colors ?? ''
  if (c.length > 1) return 'M'
  if (c.length === 1) return c
  return 'C'
}

/** Text-Rahmen, falls kein Bild verfuegbar (oder noch ladend). */
function TextFrame({ card, size }: { card: Card; size: CardSize }) {
  const small = size === 'xs' || size === 'sm'
  const tiny = size === 'xs'
  return (
    <div className={`absolute inset-0 flex flex-col rounded-[6%] bg-linear-to-b ${FRAME_COLORS[frameKey(card)]} p-[5%] text-ink-100`}>
      <div className="flex items-start justify-between gap-1">
        <span className={`font-semibold leading-tight ${tiny ? 'text-[7px]' : small ? 'text-[8px]' : size === 'zoom' ? 'text-base' : 'text-[10px]'}`}>
          {card.name}
        </span>
        {!tiny && <ManaCost cost={card.manaCost} size="sm" />}
      </div>
      {!small && card.typeLine && (
        <div className={`mt-1 border-t border-black/20 pt-0.5 opacity-90 ${size === 'zoom' ? 'text-sm' : 'text-[8px]'}`}>{card.typeLine}</div>
      )}
      {(size === 'lg' || size === 'xl' || size === 'zoom') && card.rules && (
        <div className={`mt-1 overflow-hidden rounded bg-black/20 p-1 leading-snug ${size === 'zoom' ? 'text-[13px]' : 'text-[8px]'}`}>
          {card.rules.slice(0, size === 'zoom' ? 12 : 4).map((r, i) => (
            <div key={i} className="mb-0.5">
              <RulesText text={r} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export interface CardViewProps {
  card: Card | Permanent
  size?: CardSize
  highlight?: Highlight
  onClick?: (e: MouseEvent) => void
  onHover?: (c: Card | null) => void
  showBack?: boolean
  dim?: boolean
  className?: string
  count?: number
  /** getappt-Zustand ignorieren (z.B. grosse Vorschau) */
  upright?: boolean
}

export const CardView = memo(function CardView({ card, size = 'md', highlight = 'none', onClick, onHover, showBack, dim, className = '', count, upright }: CardViewProps) {
  const perm = card as Permanent
  const url = card.faceDown ? null : cardImageUrl(card, { back: showBack || card.transformed, size: size === 'zoom' || size === 'xl' ? 'normal' : 'normal' })
  const [loaded, setLoaded] = useState(false)
  const [broken, setBroken] = useState(url ? failed.has(url) : true)
  const tapped = perm.tapped && !upright
  const glow =
    highlight === 'playable'
      ? 'glow-playable'
      : highlight === 'mana'
        ? 'glow-mana'
        : highlight === 'target'
          ? 'glow-target'
          : highlight === 'chosen'
            ? 'glow-chosen'
            : highlight === 'attacking' || perm.attacking
              ? 'glow-attacking'
              : highlight === 'blocking' || perm.blocking
                ? 'glow-blocking'
                : 'card-shadow'
  const clickable = !!onClick && highlight !== 'none'
  const small = size === 'xs' || size === 'sm'

  // Getappt: Querformat-Feld, Karte darin um 90° gedreht (keine Luecken im Layout)
  const w = WIDTH_PX[size]
  const h = Math.round((w * 88) / 63)
  const inner = (
    <div
      data-obj={tapped ? undefined : card.id}
      className={`relative shrink-0 ${SIZES[size]} aspect-[63/88] transition-transform duration-200 ${tapped ? 'rotate-90' : ''} ${tapped ? '' : className}`}
      style={tapped ? { position: 'absolute', left: (h - w) / 2, top: (w - h) / 2 } : undefined}
      onMouseEnter={tapped ? undefined : () => onHover?.(card)}
      onMouseLeave={tapped ? undefined : () => onHover?.(null)}
      onClick={tapped ? undefined : onClick}
    >
      <div
        className={`absolute inset-0 overflow-hidden rounded-[6%] bg-ink-800 ${glow} ${clickable ? 'cursor-pointer hover:-translate-y-0.5' : ''} ${dim ? 'opacity-55 saturate-50' : ''} transition-all`}
      >
        {card.faceDown ? (
          <div className="absolute inset-0 rounded-[6%] bg-linear-to-br from-[#4b2e1e] via-[#2a1a10] to-[#4b2e1e] ring-2 ring-inset ring-[#6b4a2e]">
            <div className="absolute inset-[18%] rounded-full bg-linear-to-br from-amber-700/40 to-amber-900/40 blur-[1px]" />
          </div>
        ) : (
          <>
            {(!loaded || broken) && <TextFrame card={card} size={size} />}
            {url && !broken && (
              <img
                src={url}
                alt={card.name}
                draggable={false}
                loading="lazy"
                className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-300 ${loaded ? 'opacity-100' : 'opacity-0'}`}
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

      {/* Overlays */}
      {count !== undefined && count > 1 && (
        <div className="absolute -right-1.5 -top-1.5 z-10 rounded-full bg-ink-950 px-1.5 text-[11px] font-bold text-gold-300 ring-1 ring-gold-400/60">×{count}</div>
      )}
      {perm.counters && perm.counters.length > 0 && (
        <div className={`absolute left-0.5 top-[30%] z-10 flex flex-col gap-0.5 ${tapped ? '-rotate-90' : ''}`}>
          {perm.counters.slice(0, 3).map((c) => (
            <span key={c.name} className="rounded bg-ink-950/90 px-1 text-[9px] font-bold leading-tight text-arcane-400 ring-1 ring-arcane-400/40" title={c.name}>
              {c.count} {small ? '' : shortCounter(c.name)}
            </span>
          ))}
        </div>
      )}
      {(perm.power !== undefined || perm.loyalty || perm.defense) && !card.faceDown && (
        <div className={`absolute bottom-0.5 right-0.5 z-10 rounded bg-ink-950/90 px-1 font-bold leading-tight ring-1 ${small ? 'text-[9px]' : 'text-[11px]'} ${perm.damage ? 'text-blood-400 ring-blood-400/50' : 'text-ink-100 ring-white/20'} ${tapped ? '-rotate-90' : ''}`}>
          {perm.loyalty ?? perm.defense ?? `${perm.power}/${perm.toughness}`}
        </div>
      )}
      {perm.damage ? (
        <div className={`absolute bottom-0.5 left-0.5 z-10 rounded bg-blood-500 px-1 text-[9px] font-bold text-white ${tapped ? '-rotate-90' : ''}`}>-{perm.damage}</div>
      ) : null}
      {perm.sick && perm.types?.includes('CREATURE') && !perm.attacking && (
        <div className="absolute right-0.5 top-0.5 z-10 text-[10px] opacity-80" title="Einsatzverzögerung">💤</div>
      )}
    </div>
  )
  if (!tapped) return inner
  return (
    <div
      data-obj={card.id}
      className={`relative shrink-0 ${className}`}
      style={{ width: h, height: w }}
      onMouseEnter={() => onHover?.(card)}
      onMouseLeave={() => onHover?.(null)}
      onClick={onClick}
    >
      {inner}
    </div>
  )
})

function shortCounter(name: string): string {
  const n = name.toLowerCase()
  if (n === 'p1p1' || n.includes('+1/+1')) return '+1'
  if (n === 'm1m1' || n.includes('-1/-1')) return '-1'
  if (n.startsWith('loyalty')) return '♦'
  return name.slice(0, 4)
}
