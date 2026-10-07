import { useEffect } from 'react'
import { useSocial } from '../store/social'

/**
 * Solange ein Screen mit Lobby-Chat offen ist (Start, Lobby), gilt der Chat als gelesen; das Badge an der Nav zaehlt
 * nur ausserhalb dieser Screens.
 */
export function useMarkLobbyRead(): void {
  const seq = useSocial((s) => s.seq)
  const chatIn = useSocial((s) => s.chatIn)
  const markRead = useSocial((s) => s.markRead)
  useEffect(() => {
    markRead()
  }, [seq, chatIn, markRead])
}
