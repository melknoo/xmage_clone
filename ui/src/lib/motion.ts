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

/** Karte fliegt zwischen Zonen: 420 ms, bei Blitz 210 ms */
export function cardFlight(tempo?: Tempo | null): Transition {
  return { duration: tempo === 'BLITZ' ? 0.21 : DUR.d4, ease: EASE_IN_OUT }
}
