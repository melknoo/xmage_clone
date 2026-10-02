import { useMemo } from 'react'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

export interface PromptButton {
  label: string
  run: () => void
  kind: 'primary' | 'ghost' | 'danger' | 'arcane'
  hotkey?: string
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
  const stackSize = useGame((s) => s.state?.stack.length ?? 0)
  const p = inter.prompt

  return useMemo(() => {
    if (!p) return []
    const out: PromptButton[] = []
    switch (p.kind) {
      case 'ASK':
        if (p.mulligan) break
        out.push({ label: p.leftBtn ?? 'Ja', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
        out.push({ label: p.rightBtn ?? 'Nein', run: () => answer({ bool: false }), kind: 'ghost', hotkey: 'Esc' })
        break
      case 'SELECT':
        if (inter.mode === 'attack') {
          out.push({ label: 'Angriff bestätigen', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
          if (p.specialBtn) out.push({ label: 'Alle angreifen', run: () => answer({ str: 'special' }), kind: 'danger' })
        } else if (inter.mode === 'block') {
          out.push({ label: 'Blocker bestätigen', run: () => answer({ bool: true }), kind: 'primary', hotkey: 'Space' })
        } else {
          out.push({ label: stackSize > 0 ? 'Auflösen lassen' : 'Weiter', run: () => answer({ bool: false }), kind: 'primary', hotkey: 'Space' })
          if (p.specialBtn) out.push({ label: p.specialBtn, run: () => answer({ str: 'special' }), kind: 'arcane' })
        }
        break
      case 'PICK_TARGET':
        if (p.rightBtn || !p.required) {
          out.push({ label: translate(p.rightBtn) ?? (p.chosen?.length ? 'Fertig' : 'Abbrechen'), run: () => answer({ bool: false }), kind: p.chosen?.length ? 'primary' : 'ghost', hotkey: 'Space' })
        }
        break
      case 'PLAY_MANA':
        out.push({ label: 'Automatisch bezahlen', run: autoPay, kind: 'arcane', hotkey: 'Space' })
        if (p.specialBtn) out.push({ label: p.specialBtn, run: () => answer({ str: 'special' }), kind: 'arcane' })
        out.push({ label: 'Abbrechen', run: () => answer({ bool: false }), kind: 'ghost', hotkey: 'Esc' })
        break
      case 'PLAY_X_MANA':
        out.push({ label: 'Fertig', run: () => answer({ bool: false }), kind: 'primary', hotkey: 'Space' })
        break
    }
    return out
  }, [p, inter.mode, answer, autoPay, stackSize])
}

function translate(s?: string): string | undefined {
  if (!s) return undefined
  const map: Record<string, string> = { Done: 'Fertig', Cancel: 'Abbrechen', OK: 'OK', Yes: 'Ja', No: 'Nein' }
  return map[s] ?? s
}
