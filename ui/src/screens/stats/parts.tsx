import { useState, type CSSProperties, type ReactNode } from 'react'
import { cardImageUrl } from '../../api/client'

/** Abschnittskopf der Statistik: Barlow 13 .14em fg-3 (Versalien per CSS) */
export function SectionLabel({ children, className = '', style }: { children: ReactNode; className?: string; style?: CSSProperties }) {
  return (
    <span className={`label ${className}`} style={{ letterSpacing: '.14em', ...style }}>
      {children}
    </span>
  )
}

/** Commander-Kunst (art_crop) ueber den Namen (/img/named); bei Partnern der erste Name. */
export function commanderArtUrl(commander: string | null | undefined): string | null {
  const first = (commander ?? '').split(' & ')[0].trim()
  return first ? cardImageUrl({ name: first }, { size: 'art_crop' }) : null
}

/** Kunst eines Decks: eigene Decks ueber Set/Nummer, sonst (vorgefertigt) ueber den Commander-Namen. */
export function deckArtUrl(d: { commander?: string | null; commanderSet?: string | null; commanderNum?: string | null }): string | null {
  if (d.commanderSet && d.commanderNum) return cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' })
  return commanderArtUrl(d.commander)
}

/**
 * Kunst-Kachel (bg-4 als Platzhalter), Bild lazy geladen. Faellt bei Ladefehler auf die leere Kachel zurueck.
 */
export function ArtThumb({ src, width, height, radius, title }: { src: string | null; width: number; height: number; radius: number; title?: string }) {
  const [broken, setBroken] = useState<string | null>(null)
  const show = !!src && broken !== src
  return (
    <span className="block flex-none overflow-hidden bg-bg-4" style={{ width, height, borderRadius: radius }} title={title}>
      {show && (
        <img
          src={src!}
          alt=""
          loading="lazy"
          decoding="async"
          draggable={false}
          className="block h-full w-full object-cover"
          onError={() => setBroken(src)}
        />
      )}
    </span>
  )
}
