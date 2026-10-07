import { useEffect, useRef, useState, type ReactNode } from 'react'
import { clockTime } from '../lib/format'

/** Eine Chatzeile (Lobby-, Tisch- oder Spiel-Chat auf ein Format gebracht) */
export interface ChatMessage {
  /** stabiler Schluessel (seq, ts+Index ...) */
  id: string | number
  ts: number
  /** userId (Lobby/Tisch) bzw. playerId (Spiel) */
  authorId: string | number
  name: string
  text: string
  /** Systemzeile: ganzer Text, ohne Namen */
  sys?: boolean
}

export interface ChatMessagesProps {
  msgs: ChatMessage[]
  /** eigene id: eigener Name in Ember */
  meId: string | number | null | undefined
  /** Inhalt ohne Nachrichten */
  empty?: ReactNode
  /** Klick auf einen fremden Namen (Lobby: Mitglieder-Menue) */
  onName?: (m: ChatMessage) => void
  /** Zeile oberhalb der ersten Nachricht, scrollt mit (Lobby: Verlaufshinweis) */
  head?: ReactNode
  className?: string
}

/**
 * Nachrichtenliste, unten ausgerichtet, mit Scroll-Haften (unter 40 px vom Ende bleibt sie unten).
 * Nachricht: Name 13/600 (eigener Ember, sonst fg-1) + Zeit Mono 10.5 fg-4, darunter Text 13.5/1.45 fg-2.
 * Systemzeile: nur Zeit und Text in fg-4.
 */
export function ChatMessages({ msgs, meId, empty, onName, head, className = '' }: ChatMessagesProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [stick, setStick] = useState(true)
  useEffect(() => {
    if (stick && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [msgs, stick])
  return (
    <div
      ref={ref}
      className={`flex min-h-0 flex-col gap-2.5 overflow-y-auto scrollbar-thin ${className}`}
      onScroll={(e) => {
        const el = e.currentTarget
        setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
      }}
    >
      <div className="mt-auto" />
      {head}
      {msgs.length === 0 && empty}
      {msgs.map((m) => {
        const own = !m.sys && meId !== null && meId !== undefined && m.authorId === meId
        const clickable = !!onName && !own && !m.sys
        return (
          <div key={m.id} className="flex flex-col gap-[3px]" data-sys={m.sys ? 'true' : undefined}>
            <div className="flex items-baseline gap-2">
              {!m.sys &&
                (clickable ? (
                  <button type="button" className="text-[13px] font-semibold leading-[1.2] text-fg-1 hover:underline" onClick={() => onName?.(m)}>
                    {m.name}
                  </button>
                ) : (
                  <span className={`text-[13px] font-semibold leading-[1.2] ${own ? 'text-ember' : 'text-fg-1'}`}>{m.name}</span>
                ))}
              <span className="font-mono text-[10.5px] font-medium leading-none text-fg-4">{clockTime(m.ts)}</span>
            </div>
            <span className={`text-[13.5px] leading-[1.45] [overflow-wrap:anywhere] ${m.sys ? 'text-fg-4' : 'text-fg-2'}`}>{m.text}</span>
          </div>
        )
      })}
    </div>
  )
}
