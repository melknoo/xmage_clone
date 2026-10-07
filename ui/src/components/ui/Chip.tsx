import type { CSSProperties, ReactNode } from 'react'
import { Icon, type IconName } from '../../lib/icons'

export type ChipTone = 'turn' | 'outline' | 'attack' | 'block' | 'target' | 'chosen' | 'ember' | 'neutral' | 'count' | 'onArt' | 'filter'
/** md 5px 8px 13 · hdr 5px 7px 13 · sm 4px 6px 12 · xs 3px 5px 12 (ohne Angabe: Massangabe der Variante) */
export type ChipSize = 'md' | 'hdr' | 'sm' | 'xs'

export interface ChipProps {
  tone: ChipTone
  /** Zustandsfarben (attack/block/target/chosen) gefuellt statt Kontur */
  fill?: boolean
  icon?: IconName
  /** Filterchip mit X (z. B. "Dredge 5 · abgelehnt") */
  onRemove?: () => void
  /** Titel des X-Knopfs */
  removeLabel?: string
  size?: ChipSize
  children?: ReactNode
  className?: string
  title?: string
  testId?: string
  style?: CSSProperties
}

const SIZE_STYLE: Record<ChipSize, CSSProperties> = {
  md: { padding: '5px 8px', fontSize: 13 },
  hdr: { padding: '5px 7px', fontSize: 13 },
  sm: { padding: '4px 6px', fontSize: 12 },
  xs: { padding: '3px 5px', fontSize: 12, letterSpacing: '.06em' },
}

// Klassennamen ausgeschrieben, sonst findet Tailwind sie nicht
const OUTLINE_CLASS: Record<ChipTone, string> = {
  turn: 'chip-turn',
  outline: 'chip-outline',
  attack: 'chip-attack',
  block: 'chip-block',
  target: 'chip-target',
  chosen: 'chip-chosen',
  ember: 'chip-ember',
  neutral: 'chip-neutral',
  count: 'chip-count',
  onArt: 'chip-on-art',
  filter: 'chip-filter',
}
const FILL_CLASS: Partial<Record<ChipTone, string>> = {
  attack: 'chip-fill-attack',
  block: 'chip-fill-block',
  target: 'chip-fill-target',
  chosen: 'chip-fill-chosen',
  ember: 'chip-turn',
}

function toneClass(tone: ChipTone, fill: boolean): string {
  return (fill && FILL_CLASS[tone]) || OUTLINE_CLASS[tone]
}

/** Chip (Barlow 600, Versalien per CSS, r2). Text normal schreiben. */
export function Chip({ tone, fill = false, icon, onRemove, removeLabel = 'Entfernen', size, children, className = '', title, testId, style }: ChipProps) {
  const sizeStyle = size ? SIZE_STYLE[size] : undefined
  const iconSize = size === 'sm' || size === 'xs' || tone === 'onArt' || tone === 'count' ? 12 : 13
  return (
    <span className={`${toneClass(tone, fill)} whitespace-nowrap ${className}`} style={sizeStyle || style ? { ...sizeStyle, ...style } : undefined} title={title} data-testid={testId}>
      {icon && <Icon name={icon} size={iconSize} className={icon === 'thinking' ? 'animate-think' : undefined} />}
      {children}
      {onRemove && (
        <button
          type="button"
          className="flex rounded-xs p-0.5 text-fg-3 transition-colors duration-1 hover:text-fg-1"
          title={removeLabel}
          aria-label={removeLabel}
          onClick={(e) => {
            e.stopPropagation()
            onRemove()
          }}
        >
          <Icon name="close" size={12} />
        </button>
      )}
    </span>
  )
}
