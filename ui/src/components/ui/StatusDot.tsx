import type { CSSProperties } from 'react'

/**
 * Status-Punkt 8x8, das einzige runde Element.
 * online gefuellt gruen · table/game Ring fg-2 (1.5 px) · offline Ring fg-5 · pending (gesendete Anfrage) Ring fg-4 ·
 * used (Code angemeldet) gefuellt gruen · unused Ring fg-3
 */
export type Status = 'online' | 'table' | 'game' | 'offline' | 'pending' | 'used' | 'unused'

const STYLE: Record<Status, CSSProperties> = {
  online: { background: 'var(--color-chosen)' },
  used: { background: 'var(--color-chosen)' },
  table: { boxShadow: 'inset 0 0 0 1.5px var(--color-fg-2)' },
  game: { boxShadow: 'inset 0 0 0 1.5px var(--color-fg-2)' },
  offline: { boxShadow: 'inset 0 0 0 1px var(--color-fg-5)' },
  pending: { boxShadow: 'inset 0 0 0 1px var(--color-fg-4)' },
  unused: { boxShadow: 'inset 0 0 0 1px var(--color-fg-3)' },
}

const LABEL: Record<Status, string> = {
  online: 'Online',
  table: 'Am Tisch',
  game: 'Im Spiel',
  offline: 'Offline',
  pending: 'Anfrage gesendet',
  used: 'Angemeldet',
  unused: 'Unbenutzt',
}

export function StatusDot({ status, title, className = '' }: { status: Status; title?: string; className?: string }) {
  return (
    <span
      className={`inline-block h-2 w-2 shrink-0 rounded-full ${className}`}
      style={STYLE[status]}
      title={title ?? LABEL[status]}
      aria-label={title ?? LABEL[status]}
      role="img"
      data-status={status}
    />
  )
}
