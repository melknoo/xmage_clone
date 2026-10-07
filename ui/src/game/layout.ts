import { useEffect, useMemo, useState, type CSSProperties } from 'react'

/**
 * Masse des Spielbretts nach dem Prototyp (MageLite Spielbrett.dc.html, Objekt L).
 * FULL = 1680x1000, COMPACT = 1280x760. Alle Zahlen in px.
 */
export interface BoardLayout {
  /** Kopfleiste */
  hdr: number
  /** Gegner-Pod: Hoehe, Kartenbreite, Avatar, Leben */
  oppH: number
  oppCardW: number
  avatar: number
  lifeOpp: number
  /** eigenes Feld */
  creatureW: number
  landW: number
  /** Hand */
  handW: number
  handH: number
  handGap: number
  /** Aktionsleiste */
  promptH: number
  /** Seitenleiste und Zoom-Karte */
  side: number
  zoomW: number
  /** Info-Spalte (eigener Spieler) */
  infoW: number
  lifeBig: number
  infoPad: string
  infoGap: number
  /** Innenabstaende */
  podPad: string
  bfPad: string
  rowGap: number
  myPad: string
  myGap: number
  /** Stapel, Mulligan, Friedhof-Raster */
  stackW: number
  mullW: number
  graveW: number
  /** Phasenleiste: horizontales Padding je Schritt */
  phPad: number
  /** Labels der Kopfleisten-Schalter (Text in normaler Schreibung, Versalien per CSS) */
  manaLbl: string
  passLbl: string
  /** Breiten der Brett-Dialoge */
  modal: {
    mulligan: number
    grave: number
    ability: number
    replace: number
    pause: number
    over: number
    default: number
  }
}

export const FULL: BoardLayout = {
  hdr: 50,
  oppH: 272,
  oppCardW: 50,
  avatar: 38,
  lifeOpp: 46,
  creatureW: 80,
  landW: 62,
  handW: 104,
  handH: 186,
  handGap: 10,
  promptH: 62,
  side: 336,
  zoomW: 250,
  infoW: 210,
  lifeBig: 84,
  infoPad: '18px 16px',
  infoGap: 14,
  podPad: '12px 14px 10px',
  bfPad: '12px 14px',
  rowGap: 12,
  myPad: '20px 24px',
  myGap: 12,
  stackW: 340,
  mullW: 120,
  graveW: 110,
  phPad: 9,
  manaLbl: 'Auto-Mana',
  passLbl: 'Auto-Passen',
  modal: { mulligan: 1000, grave: 720, ability: 520, replace: 600, pause: 600, over: 620, default: 560 },
}

export const COMPACT: BoardLayout = {
  hdr: 44,
  oppH: 200,
  oppCardW: 38,
  avatar: 30,
  lifeOpp: 36,
  creatureW: 60,
  landW: 46,
  handW: 78,
  handH: 132,
  handGap: 8,
  promptH: 54,
  side: 268,
  zoomW: 190,
  infoW: 168,
  lifeBig: 58,
  infoPad: '12px 12px',
  infoGap: 9,
  podPad: '9px 12px 7px',
  bfPad: '8px 12px',
  rowGap: 8,
  myPad: '12px 16px',
  myGap: 7,
  stackW: 300,
  mullW: 92,
  graveW: 86,
  phPad: 8,
  manaLbl: 'Mana',
  passLbl: 'Passen',
  modal: { mulligan: 780, grave: 560, ability: 520, replace: 600, pause: 600, over: 620, default: 560 },
}

/** Kompakt unter 1440 px Breite oder 900 px Hoehe */
export const COMPACT_MAX_W = 1440
export const COMPACT_MAX_H = 900
/** Tempo direkt in der Kopfleiste erst ab 1600 px */
export const INLINE_TEMPO_MIN_W = 1600

export type BoardLayoutState = BoardLayout & { compact: boolean; showInlineTempo: boolean }

function measure(): { w: number; h: number } {
  if (typeof window === 'undefined') return { w: 1680, h: 1000 }
  return { w: window.innerWidth, h: window.innerHeight }
}

function compute(compact: boolean, showInlineTempo: boolean): BoardLayoutState {
  return { ...(compact ? COMPACT : FULL), compact, showInlineTempo }
}

/** Aktuelles Brett-Layout; reagiert auf Fenstergroesse. */
export function useBoardLayout(): BoardLayoutState {
  const [size, setSize] = useState(measure)
  useEffect(() => {
    const onResize = () => {
      const next = measure()
      setSize((s) => (s.w === next.w && s.h === next.h ? s : next))
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  const compact = size.w < COMPACT_MAX_W || size.h < COMPACT_MAX_H
  const inline = size.w >= INLINE_TEMPO_MIN_W
  // nur bei Wechsel der Stufe ein neues Objekt (memo-freundlich)
  return useMemo(() => compute(compact, inline), [compact, inline])
}

/** CSS-Variablen fuer das GameScreen-Wurzelelement (BoardModal-Pille, Scope der Dialoge). */
export function boardCssVars(l: BoardLayout): CSSProperties {
  return {
    '--hdr-h': `${l.hdr}px`,
    '--prompt-h': `${l.promptH}px`,
    '--side-w': `${l.side}px`,
  } as CSSProperties
}
