import { useMemo } from 'react'
import { Icon } from '../lib/icons'
import { ChatInput } from '../social/ChatInput'
import { ChatMessages, type ChatMessage } from '../social/ChatMessages'
import { useGame } from '../store/game'

/**
 * Chat zwischen den Menschen am Tisch (Tab "Chat" ab 2 Menschen bzw. fuer Zuschauer).
 * Gleiche Darstellung wie Lobby-/Tisch-Chat (social/ChatMessages + ChatInput). Zuschauer lesen nur mit.
 */
export function ChatPanel() {
  const chat = useGame((s) => s.chat)
  const myId = useGame((s) => s.hello?.myPlayerId)
  const spectator = useGame((s) => s.spectator)
  const sendChat = useGame((s) => s.sendChat)

  const msgs = useMemo<ChatMessage[]>(() => chat.map((c, i) => ({ id: `${c.ts}-${i}`, ts: c.ts, authorId: c.playerId, name: c.name, text: c.text })), [chat])

  return (
    <div className="flex h-full flex-col gap-3">
      <ChatMessages
        msgs={msgs}
        meId={spectator ? null : myId}
        className="flex-1"
        empty={<div className="text-[12.5px] text-fg-4">{spectator ? 'Noch keine Nachrichten am Tisch.' : 'Noch keine Nachrichten. Enter sendet.'}</div>}
      />
      {spectator ? (
        <div className="flex h-10 shrink-0 items-center gap-2 text-[12.5px] text-fg-4" data-testid="chat-readonly">
          <Icon name="spectate" size={14} />
          Zuschauer können nicht schreiben
        </div>
      ) : (
        <div className="shrink-0">
          <ChatInput placeholder="Nachricht an den Tisch" maxLength={300} onSend={(t) => sendChat(t)} />
        </div>
      )}
    </div>
  )
}
