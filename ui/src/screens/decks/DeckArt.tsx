import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'

/**
 * Commander-Art (art_crop) als Flaeche: bg-4 als Platzhalter, Bild lazy und "cover".
 * children liegen darueber (Chips, Haken, Verlauf). Hoehe/Breite/Radius setzt der Aufrufer.
 */
export function DeckArt({ src, className = '', style, children }: { src?: string | null; className?: string; style?: CSSProperties; children?: ReactNode }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [src])
  return (
    <div className={`relative overflow-hidden bg-bg-4 ${className}`} style={style}>
      {src && !failed && <img src={src} alt="" loading="lazy" draggable={false} className="absolute inset-0 h-full w-full object-cover" onError={() => setFailed(true)} />}
      {children}
    </div>
  )
}
