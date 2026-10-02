import { useMemo } from 'react'
import type { Highlight } from '../components/CardView'
import type { Prompt, UUID } from '../api/types'
import { useGame } from '../store/game'

export type Mode = 'none' | 'priority' | 'attack' | 'block' | 'target' | 'mana' | 'dialog'

export interface Interaction {
  prompt: Prompt | null
  mode: Mode
  highlight: (id: UUID) => Highlight
  canClick: (id: UUID) => boolean
  click: (id: UUID) => void
  /** Spieler ist waehlbares Ziel (z.B. Angriffsziel, Zauberziel) */
  playerTargetable: (id: UUID) => boolean
  /** Karten-Auswahl ueber Modal (Bibliothek, aufgedeckte Karten ...) */
  needsCardModal: boolean
}

const DIALOG_KINDS = new Set(['CHOOSE_ABILITY', 'CHOOSE_MODE', 'CHOOSE_CHOICE', 'AMOUNT', 'MULTI_AMOUNT', 'CHOOSE_PILE', 'PICK_ABILITY'])

export function useInteraction(): Interaction {
  const prompt = useGame((s) => s.prompt)
  const answered = useGame((s) => s.answeredPromptId)
  const state = useGame((s) => s.state)
  const objects = useGame((s) => s.objects)
  const answer = useGame((s) => s.answer)

  return useMemo(() => {
    const active = prompt && answered !== prompt.id ? prompt : null
    const actions = new Set(state?.actions ?? [])
    const playable = new Set(Object.keys(state?.playable ?? {}))
    const chosen = new Set(active?.chosen ?? [])
    let clickable = new Set<UUID>()
    let mode: Mode = 'none'
    let needsCardModal = false

    const attacking = new Set<UUID>()
    const blocking = new Set<UUID>()
    for (const g of state?.combat ?? []) {
      g.attackers.forEach((a) => attacking.add(a))
      g.blockers.forEach((b) => blocking.add(b))
    }

    if (active) {
      switch (active.kind) {
        case 'SELECT':
          if (active.mode === 'attackers') {
            mode = 'attack'
            clickable = new Set([...(active.possibleAttackers ?? []), ...attacking])
          } else if (active.mode === 'blockers') {
            mode = 'block'
            clickable = new Set([...(active.possibleBlockers ?? []), ...blocking])
          } else {
            mode = 'priority'
            clickable = playable
          }
          break
        case 'PICK_TARGET':
          mode = 'target'
          clickable = new Set(active.targets ?? [])
          if (active.cards && active.cards.length > 0) {
            needsCardModal = active.cards.some((c) => !objects.has(c.id))
          }
          break
        case 'PLAY_MANA':
        case 'PLAY_X_MANA':
          mode = 'mana'
          clickable = playable
          break
        default:
          mode = DIALOG_KINDS.has(active.kind) ? 'dialog' : 'none'
      }
    }

    const highlight = (id: UUID): Highlight => {
      if (attacking.has(id) && mode !== 'target') return 'attacking'
      if (blocking.has(id) && mode !== 'target') return 'blocking'
      if (!clickable.has(id)) return chosen.has(id) ? 'chosen' : 'none'
      switch (mode) {
        case 'priority':
          return actions.has(id) ? 'playable' : 'mana'
        case 'mana':
          return 'playable'
        case 'target':
          return chosen.has(id) ? 'chosen' : 'target'
        case 'attack':
        case 'block':
          return 'playable'
        default:
          return 'none'
      }
    }

    return {
      prompt: active,
      mode,
      highlight,
      canClick: (id: UUID) => clickable.has(id),
      click: (id: UUID) => {
        if (clickable.has(id)) answer({ uuid: id })
      },
      playerTargetable: (id: UUID) => (mode === 'target' || mode === 'attack') && clickable.has(id),
      needsCardModal,
    }
  }, [prompt, answered, state, objects, answer])
}
