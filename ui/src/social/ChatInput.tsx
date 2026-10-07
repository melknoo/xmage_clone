import { useState } from 'react'
import { Icon } from '../lib/icons'

export interface ChatInputProps {
  /** Text senden; ein zurueckgegebener Fehlertext laesst den Text im Feld stehen */
  onSend: (text: string) => void | string | null | Promise<void | string | null>
  disabled?: boolean
  placeholder: string
  /** Standard 300 */
  maxLength?: number
}

/**
 * Eingabezeile 40 hoch (bg-0, Kontur line-3, Fokus Ember), Senden-Icon in Ember.
 * Enter sendet, Esc nimmt den Fokus weg, hoechstens 300 Zeichen. disabled: Opazitaet .5 (z. B. ohne Verbindung).
 */
export function ChatInput({ onSend, disabled, placeholder, maxLength = 300 }: ChatInputProps) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    const t = text.trim()
    if (!t || disabled || busy) return
    setText('')
    setBusy(true)
    try {
      const err = await onSend(t)
      if (typeof err === 'string' && err) setText(t)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="field" style={{ paddingRight: 6, ...(disabled ? { opacity: 0.5 } : null) }} data-disabled={disabled ? 'true' : undefined}>
      <input
        className="text-[14px]"
        placeholder={placeholder}
        maxLength={maxLength}
        value={text}
        disabled={disabled}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            void submit()
          } else if (e.key === 'Escape') {
            e.preventDefault()
            e.currentTarget.blur()
          }
        }}
      />
      <button
        type="button"
        className="flex h-[30px] w-[30px] flex-none items-center justify-center rounded-xs text-ember transition-colors duration-1 hover:bg-bg-4 hover:text-ember-hover disabled:opacity-40"
        title="Senden"
        aria-label="Senden"
        disabled={disabled}
        onClick={() => void submit()}
      >
        <Icon name="send" size={16} />
      </button>
    </div>
  )
}
