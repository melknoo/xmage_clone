import { useMemo } from 'react'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

export interface PromptButton {
  label: string
  run: () => void
  kind: 'primary' | 'ghost' | 'danger' | 'arcane'
  hotkey?: string
  /** Zwei-Klick-Bestaetigung: Beschriftung nach dem ersten Klick (z.B. "Wirklich alle?") */
  confirm?: string
  title?: string
}

/** Pregame-Frage zu einer Starthand-Aktion (Gemstone Caverns, Leylines, Chancellors): eigener Dialog, keine Leisten-Knoepfe */
export const OPENING_HAND_RE = /^Put .+ onto? the battlefield\?$/i
export function isOpeningHandAsk(p: { kind: string; messageText?: string } | null | undefined, step: string | undefined | null): boolean {
  return !!p && p.kind === 'ASK' && !step && OPENING_HAND_RE.test(p.messageText ?? '')
}

/** Ziel von "Weiter" im eigenen Zug (Engine: PromptDto.nextStop) */
export const NEXT_STOP_LABEL: Record<string, string> = {
  main1: 'Zu Main 1',
  combat: 'Zum Kampf',
  main2: 'Zu Main 2',
  end: 'Zug beenden',
}

/** Laufendes F-Tasten-Passen (PlayerDto.skips) als Text */
export const SKIP_LABEL: Record<string, string> = {
  myTurn: 'Passe bis zu deinem Zug',
  nextTurn: 'Passe bis zum nächsten Zug',
  endOfTurn: 'Passe bis Zugende',
  nextMain: 'Passe bis zur nächsten Hauptphase',
  stackResolved: 'Passe, bis der Stapel leer ist',
  endStepBeforeMyTurn: 'Passe bis zur Endphase vor deinem Zug',
}

export interface SkipButton {
  label: string
  action: string
  hotkey: string
  title: string
}

export const SKIPS: SkipButton[] = [
  { label: 'Stapel auflösen', action: 'PASS_PRIORITY_UNTIL_STACK_RESOLVED', hotkey: 'F10', title: 'Passen, bis der Stapel leer ist' },
  { label: 'Bis Zugende', action: 'PASS_PRIORITY_UNTIL_TURN_END_STEP', hotkey: 'F5', title: 'Passen bis zum Ende dieses Zuges' },
  { label: 'Nächster Zug', action: 'PASS_PRIORITY_UNTIL_NEXT_TURN', hotkey: 'F4', title: 'Passen bis zum nächsten Zug' },
  { label: 'Bis zu meinem Zug', action: 'PASS_PRIORITY_UNTIL_MY_NEXT_TURN', hotkey: 'F9', title: 'Passen bis zu deinem nächsten Zug' },
]

export const HOTKEY_ACTIONS: Record<string, string> = {
  F3: 'PASS_PRIORITY_CANCEL_ALL_ACTIONS',
  F4: 'PASS_PRIORITY_UNTIL_NEXT_TURN',
  F5: 'PASS_PRIORITY_UNTIL_TURN_END_STEP',
  F6: 'PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK',
  F7: 'PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE',
  F9: 'PASS_PRIORITY_UNTIL_MY_NEXT_TURN',
  F10: 'PASS_PRIORITY_UNTIL_STACK_RESOLVED',
  F11: 'PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN',
}

/** Buttons fuer den offenen Prompt (Prompt-Leiste + Tastatur). */
export function usePromptButtons(inter: Interaction): PromptButton[] {
  const answer = useGame((s) => s.answer)
  const autoPay = useGame((s) => s.autoPay)
  const combatReset = useGame((s) => s.combatReset)
  const stackSize = useGame((s) => s.state?.stack.length ?? 0)
  const step = useGame((s) => s.state?.step)
  const attacking = useGame((s) => (s.state?.combat ?? []).reduce((n, g) => n + g.attackers.length, 0))
  const p = inter.prompt

  return useMemo(() => {
    if (!p) return []
    const out: PromptButton[] = []
    switch (p.kind) {
      case 'ASK':
        if (p.mulligan || isOpeningHandAsk(p, step)) break
        out.push({ label: p.leftBtn ?? 'Ja', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
        out.push({ label: p.rightBtn ?? 'Nein', run: () => answer({ bool: false }), kind: 'ghost', hotkey: 'Esc' })
        break
      case 'SELECT':
        if (inter.mode === 'attack') {
          out.push({ label: 'Angriff bestätigen', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
          if (p.specialBtn) out.push({ label: 'Alle angreifen', run: () => answer({ str: 'special' }), kind: 'danger', confirm: 'Wirklich alle?' })
          if (attacking > 0) out.push({ label: 'Angriff zurücksetzen', run: combatReset, kind: 'ghost', title: 'Alle Angreifer wieder zurücknehmen' })
        } else if (inter.mode === 'block') {
          out.push({ label: 'Blocker bestätigen', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
        } else {
          out.push({
            label: stackSize > 0 ? 'Auflösen lassen' : (p.nextStop && NEXT_STOP_LABEL[p.nextStop]) || 'Weiter',
            run: () => answer({ bool: false }),
            kind: 'primary',
            hotkey: 'Space',
          })
          if (p.specialBtn) out.push({ label: p.specialBtn, run: () => answer({ str: 'special' }), kind: 'arcane' })
        }
        break
      case 'PICK_TARGET':
        if (p.defenderPick) {
          // Verteidiger-Wahl nach "Alle angreifen": Abbrechen = Angriff komplett zuruecknehmen (nicht bool:false -
          // bei Pflicht-Zielen fragt XMage sonst endlos neu)
          out.push({ label: 'Abbrechen – kein Angriff', run: combatReset, kind: 'ghost', hotkey: 'Esc' })
        } else if (p.rightBtn || !p.required) {
          out.push({ label: translate(p.rightBtn) ?? (p.chosen?.length ? 'Fertig' : 'Abbrechen'), run: () => answer({ bool: false }), kind: p.chosen?.length ? 'primary' : 'ghost', hotkey: 'Space' })
        }
        break
      case 'PLAY_MANA':
        out.push(
          p.specialBtn
            ? { label: 'Länder automatisch', run: autoPay, kind: 'arcane', hotkey: 'Space', title: `Nur Manaquellen automatisch tappen – Rest per ${p.specialBtn}` }
            : { label: 'Automatisch bezahlen', run: autoPay, kind: 'arcane', hotkey: 'Space' },
        )
        if (p.specialBtn) out.push({ label: p.specialBtn, run: () => answer({ str: 'special' }), kind: 'arcane' })
        out.push({ label: 'Abbrechen', run: () => answer({ bool: false }), kind: 'ghost', hotkey: 'Esc' })
        break
      case 'PLAY_X_MANA':
        out.push({ label: 'Fertig', run: () => answer({ bool: false }), kind: 'primary', hotkey: 'Space' })
        break
    }
    return out
  }, [p, inter.mode, answer, autoPay, stackSize, step, attacking, combatReset])
}

function translate(s?: string): string | undefined {
  if (!s) return undefined
  const map: Record<string, string> = { Done: 'Fertig', Cancel: 'Abbrechen', OK: 'OK', Yes: 'Ja', No: 'Nein' }
  return map[s] ?? s
}
