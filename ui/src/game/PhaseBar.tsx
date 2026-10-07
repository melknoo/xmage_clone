import type { CSSProperties } from 'react'
import type { GameState } from '../api/types'
import type { BoardLayoutState } from './layout'

/** Die 11 Schritte der Leiste (Text normal geschrieben, Versalien per CSS) */
export const PHASE_STEPS = ['Enttappen', 'Versorgung', 'Ziehen', 'Main 1', 'Kampfbeginn', 'Angreifer', 'Blocker', 'Schaden', 'Kampfende', 'Main 2', 'Ende'] as const

/** XMage-Schritt -> Index in PHASE_STEPS (Erstschlag zaehlt als Schaden, Aufraeumen als Ende) */
const STEP_INDEX: Record<string, number> = {
  UNTAP: 0,
  UPKEEP: 1,
  DRAW: 2,
  PRECOMBAT_MAIN: 3,
  BEGIN_COMBAT: 4,
  DECLARE_ATTACKERS: 5,
  DECLARE_BLOCKERS: 6,
  FIRST_COMBAT_DAMAGE: 7,
  COMBAT_DAMAGE: 7,
  END_COMBAT: 8,
  POSTCOMBAT_MAIN: 9,
  END_TURN: 10,
  CLEANUP: 10,
}

/** Index des aktuellen Schritts, -1 ohne Schritt (Mulligan, Spielende) */
export function stepIndex(step: string | null | undefined): number {
  return step ? (STEP_INDEX[step] ?? -1) : -1
}

/** Anzeigename des Schritts ("Main 1", "Blocker"); leer ohne Schritt */
export function stepLabel(step: string | null | undefined): string {
  const i = stepIndex(step)
  return i >= 0 ? PHASE_STEPS[i] : ''
}

/** Kompakt: 5 Gruppen [Label, erster, letzter Schritt] */
const GROUPS: [string, number, number][] = [
  ['Anfang', 0, 2],
  ['Main 1', 3, 3],
  ['Kampf', 4, 8],
  ['Main 2', 9, 9],
  ['Ende', 10, 10],
]

const isCombat = (k: number) => k >= 4 && k <= 8

interface Cell {
  label: string
  color: string
  bg: string
  bar: boolean
  sep: string | null
}

function Step({ c, pad }: { c: Cell; pad: number }) {
  const style: CSSProperties = { padding: `0 ${pad}px`, color: c.color, background: c.bg, borderLeft: c.sep ? `1px solid ${c.sep}` : undefined }
  return (
    <div className="relative flex items-center whitespace-nowrap font-display text-[13px] font-semibold uppercase leading-none tracking-[.06em]" style={style} data-current={c.bar ? 'true' : undefined}>
      {c.label}
      {c.bar && <span className="absolute inset-x-0 bottom-0 h-[3px] bg-ember" aria-hidden />}
    </div>
  )
}

const CURRENT_BG = 'rgba(255,122,61,.08)'

/**
 * Phasenleiste in der Kopfleiste: volles Layout alle 11 Schritte, kompakt 5 Gruppen (aktive Gruppe mit Unterschritt,
 * z. B. "Kampf · Blocker"). Erledigt fg-5, aktuell Ember + 3-px-Unterstrich + Toenung, Kampf auf eigener Flaeche.
 * Kein Uebergang (harter Wechsel).
 */
export function PhaseBar({ state, layout }: { state: GameState; layout: BoardLayoutState }) {
  const cur = stepIndex(state.step)
  let cells: Cell[]
  if (!layout.compact) {
    cells = PHASE_STEPS.map((l, k) => ({
      label: l,
      color: k < cur ? 'var(--color-fg-5)' : k === cur ? 'var(--color-ember)' : isCombat(k) ? 'var(--color-phase-combat)' : 'var(--color-phase-next)',
      bg: k === cur ? CURRENT_BG : isCombat(k) ? 'var(--color-phase-combat-bg)' : 'transparent',
      bar: k === cur,
      sep: k === 4 || k === 9 ? 'var(--color-line-3)' : null,
    }))
  } else {
    cells = GROUPS.map(([l, a, b], k) => {
      const on = cur >= a && cur <= b
      return {
        label: on && a !== b ? `${l} · ${PHASE_STEPS[cur]}` : l,
        color: cur > b ? 'var(--color-fg-5)' : on ? 'var(--color-ember)' : 'var(--color-phase-next)',
        bg: on ? CURRENT_BG : k === 2 ? 'var(--color-phase-combat-bg)' : 'transparent',
        bar: on,
        sep: k > 0 ? 'var(--color-line-2)' : null,
      }
    })
  }
  return (
    <div className="flex min-w-0 items-stretch overflow-hidden" style={{ height: layout.hdr }} data-testid="phase-bar" aria-label="Phasen">
      {cells.map((c) => (
        <Step key={c.label} c={c} pad={layout.phPad} />
      ))}
    </div>
  )
}
