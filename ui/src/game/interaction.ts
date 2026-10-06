import { useMemo, type MouseEvent } from 'react'
import type { Highlight } from '../components/CardView'
import type { Card, GameState, Prompt, UUID } from '../api/types'
import { useGame } from '../store/game'

export type Mode = 'none' | 'priority' | 'attack' | 'block' | 'target' | 'mana' | 'dialog'

export interface Interaction {
  prompt: Prompt | null
  mode: Mode
  highlight: (id: UUID) => Highlight
  canClick: (id: UUID) => boolean
  /**
   * Klick auf ein Objekt. Im Angriffs-/Blockmodus markiert Shift+Klick die Kreatur (bzw. mit {@code group} den ganzen
   * Stapel gleicher Karten); sind Kreaturen markiert, waehlt ein Klick auf ein Ziel den Mehrfach-Angriff/-Block.
   */
  click: (id: UUID, e?: MouseEvent, group?: UUID[]) => void
  /** per Shift+Klick markierte Kreaturen (Mehrfach-Angriff/-Block) */
  marked: Set<UUID>
  /** Spieler ist waehlbares Ziel (z.B. Angriffsziel, Zauberziel) */
  playerTargetable: (id: UUID) => boolean
  /** Karten-Auswahl ueber Modal (Bibliothek, Friedhof, Exil, aufgedeckte Karten ...) */
  needsCardModal: boolean
  /** Karten fuer dieses Modal */
  modalCards: Card[]
}

/** Objekte, die direkt auf dem Tisch anklickbar sind (Spieler, Spielfeld, Hand, Stapel, Kommandozone). */
function onTable(s: GameState | null): Set<UUID> {
  const ids = new Set<UUID>()
  if (!s) return ids
  for (const p of s.players) {
    ids.add(p.id)
    for (const c of p.battlefield) ids.add(c.id)
    for (const c of p.command) if (c.kind !== 'commander-away') ids.add(c.id)
  }
  for (const c of s.hand) ids.add(c.id)
  for (const c of s.stack) ids.add(c.id)
  return ids
}

const DIALOG_KINDS = new Set(['CHOOSE_ABILITY', 'CHOOSE_MODE', 'CHOOSE_CHOICE', 'AMOUNT', 'MULTI_AMOUNT', 'CHOOSE_PILE', 'PICK_ABILITY'])

export function useInteraction(): Interaction {
  const prompt = useGame((s) => s.prompt)
  const answered = useGame((s) => s.answeredPromptId)
  const state = useGame((s) => s.state)
  const objects = useGame((s) => s.objects)
  const answer = useGame((s) => s.answer)
  const marked = useGame((s) => s.marked)
  const toggleMarks = useGame((s) => s.toggleMarks)
  const combatMany = useGame((s) => s.combatMany)
  const specialPay = useGame((s) => s.specialPay)

  return useMemo(() => {
    const active = prompt && answered !== prompt.id ? prompt : null
    const actions = new Set(state?.actions ?? [])
    const playable = new Set(Object.keys(state?.playable ?? {}))
    const chosen = new Set(active?.chosen ?? [])
    let clickable = new Set<UUID>()
    /** Mana-Prompt: per Klick einberufbare Kreaturen (ohne eigene Manafaehigkeit) */
    let special = new Set<UUID>()
    let mode: Mode = 'none'
    let needsCardModal = false
    let modalCards: Card[] = []

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
          {
            // Ziele abseits des Tisches (Friedhof, Exil, Bibliothek, aufgedeckt) brauchen das Auswahl-Modal
            const table = onTable(state)
            if (active.cards && active.cards.length > 0) {
              needsCardModal = active.cards.some((c) => !table.has(c.id))
              modalCards = active.cards
            } else {
              modalCards = (active.targets ?? []).filter((id) => !table.has(id)).flatMap((id) => objects.get(id) ?? [])
              needsCardModal = modalCards.length > 0
            }
          }
          break
        case 'PLAY_MANA':
        case 'PLAY_X_MANA':
          mode = 'mana'
          // Kreatur mit Manafaehigkeit: lieber Mana (gleicher Wert, Laender bleiben nutzbar)
          special = new Set((active.specialTargets ?? []).filter((id) => !playable.has(id)))
          clickable = new Set([...playable, ...special])
          break
        default:
          mode = DIALOG_KINDS.has(active.kind) ? 'dialog' : 'none'
      }
    }

    // Mehrfach-Angriff/-Block: markierbar sind waehlbare Kreaturen, die noch nicht angreifen/blocken
    const combatMode = mode === 'attack' || mode === 'block'
    const possible = new Set(mode === 'attack' ? active?.possibleAttackers ?? [] : mode === 'block' ? active?.possibleBlockers ?? [] : [])
    const markable = (id: UUID) => possible.has(id) && !attacking.has(id) && !blocking.has(id)
    const marks = combatMode ? marked : new Set<UUID>()
    // Ziele fuer die Markierten: Gegner und deren Planeswalker/Kaempfe (Angriff) bzw. angreifende Kreaturen (Block)
    const markTargets = new Set<UUID>()
    if (marks.size > 0 && state) {
      if (mode === 'attack') {
        for (const p of state.players) {
          if (p.me || p.lost) continue
          markTargets.add(p.id)
          for (const c of p.battlefield) if (c.types?.includes('PLANESWALKER') || c.types?.includes('BATTLE')) markTargets.add(c.id)
        }
      } else {
        attacking.forEach((a) => markTargets.add(a))
      }
    }

    const highlight = (id: UUID): Highlight => {
      if (marks.has(id)) return 'chosen'
      if (markTargets.has(id)) return 'target'
      if (attacking.has(id) && mode !== 'target') return 'attacking'
      if (blocking.has(id) && mode !== 'target') return 'blocking'
      if (!clickable.has(id)) return chosen.has(id) ? 'chosen' : 'none'
      switch (mode) {
        case 'priority':
          return actions.has(id) ? 'playable' : 'mana'
        case 'mana':
          return special.has(id) ? 'special' : 'playable'
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
      canClick: (id: UUID) => clickable.has(id) || markTargets.has(id),
      click: (id: UUID, e?: MouseEvent, group?: UUID[]) => {
        if (combatMode && e?.shiftKey) {
          const ids = (group ?? [id]).filter(markable)
          if (ids.length) toggleMarks(ids)
          return
        }
        if (markTargets.has(id)) {
          combatMany(id)
          return
        }
        if (special.has(id)) {
          specialPay(id)
          return
        }
        if (clickable.has(id)) answer({ uuid: id })
      },
      playerTargetable: (id: UUID) => ((mode === 'target' || mode === 'attack') && clickable.has(id)) || markTargets.has(id),
      needsCardModal,
      modalCards,
      marked: marks,
    }
  }, [prompt, answered, state, objects, answer, marked, toggleMarks, combatMany, specialPay])
}
