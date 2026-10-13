import { useCallback, useRef } from 'react'
import type { Card, UUID } from '../api/types'
import { useGame } from '../store/game'
import type { Interaction } from './interaction'

/**
 * Hover-Handler fuer Hand und Kommandozone: reicht den Hover (Zoom) durch und fragt fuer wirkbare Nicht-Land-Karten bei
 * Prioritaet die Auto-Bezahlen-Vorschau an (welche Laender wuerden getappt). Verlassen beendet die Vorschau.
 */
export function usePayPreviewHover(inter: Interaction, onHover: (c: Card | null) => void): (c: Card | null) => void {
  const request = useGame((s) => s.requestPayPreview)
  const clear = useGame((s) => s.clearPayPreview)
  const spectator = useGame((s) => s.spectator)
  const last = useRef<UUID | null>(null)
  return useCallback(
    (c: Card | null) => {
      onHover(c)
      if (c) {
        if (!spectator && inter.mode === 'priority' && inter.highlightBase(c.id) === 'playable' && !c.types?.includes('LAND')) {
          last.current = c.id
          request(c.id)
        }
      } else if (last.current) {
        clear(last.current)
        last.current = null
      }
    },
    [inter, onHover, request, clear, spectator],
  )
}
