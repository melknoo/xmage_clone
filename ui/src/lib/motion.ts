import type { Transition } from 'motion/react'
import type { Tempo } from '../api/types'

/**
 * Motion-Tokens (README "Motion"), Sekunden fuer motion/react.
 * CSS-Gegenstuecke: --duration-1..4 / --duration-xp und Tailwind duration-1..4 (index.css).
 * Nicht animiert: Umbruch des Spielfelds, Phasenwechsel, neue Verlaufszeilen. Kein Pulsieren.
 */
export const DUR = { d1: 0.09, d2: 0.16, d3: 0.24, d4: 0.42, xp: 1.2 } as const

/** Erscheinen */
export const EASE_OUT = [0.2, 0.8, 0.2, 1] as const
/** Ortswechsel */
export const EASE_IN_OUT = [0.6, 0, 0.2, 1] as const
/** Geschoss: beschleunigt bis zum Einschlag (Treffer-Funke) */
export const EASE_IN = [0.5, 0, 0.9, 0.5] as const

/** FX-Ebene: ohne Ueberschwingen */
export const SPRING_FX = { type: 'spring', stiffness: 520, damping: 44 } as const satisfies Transition

/** Modal, Toast, Pille, Popover: Opazitaet + 8 px, 240 ms */
export const enter = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: 8 },
  transition: { duration: DUR.d3, ease: EASE_OUT },
} as const

/** Screenwechsel: nur Ueberblendung, hoechstens 120 ms */
export const screenFade = {
  initial: { opacity: 0 },
  animate: { opacity: 1 },
  exit: { opacity: 0 },
  transition: { duration: 0.12, ease: EASE_OUT },
} as const

/**
 * Zeitlupe fuer Screenshot-Aufnahmen der Brett-FX: nur im Vite-Dev-Modus, per `window.__mlFxSlow = 6` (Faktor).
 * In der gebauten App immer 1.
 */
export function fxSlow(): number {
  if (!import.meta.env.DEV) return 1
  const k = Number((globalThis as { __mlFxSlow?: unknown }).__mlFxSlow)
  return Number.isFinite(k) && k > 0 ? k : 1
}

/** Karte fliegt zwischen Zonen: 420 ms, bei Blitz 210 ms */
export function cardFlight(tempo?: Tempo | null): Transition {
  return { duration: (tempo === 'BLITZ' ? 0.21 : DUR.d4) * fxSlow(), ease: EASE_IN_OUT }
}

/** Dauern der Brett-FX in ms (FxLayer, Battlefield, Store) */
export interface FxTiming {
  /** Treffer-Funke fliegt Quelle -> Ziel */
  hit: number
  /** Einschlag: Ring + roter Blitz */
  impact: number
  /** Tod: Blitz + Splitter */
  death: number
  /** Karte betritt das Spielfeld (CSS fx-enter) */
  enter: number
  /** Angriffsstoss hin und zurueck (CSS fx-lunge) */
  lunge: number
}

/** Brett-FX: bei Blitz etwa 60 % der normalen Dauer (wie cardFlight) */
export function fxTiming(tempo?: Tempo | null): FxTiming {
  const t = tempo === 'BLITZ' ? { hit: 140, impact: 160, death: 220, enter: 240, lunge: 180 } : { hit: 240, impact: 220, death: 320, enter: 360, lunge: 280 }
  const k = fxSlow()
  return k === 1 ? t : { hit: t.hit * k, impact: t.impact * k, death: t.death * k, enter: t.enter * k, lunge: t.lunge * k }
}
