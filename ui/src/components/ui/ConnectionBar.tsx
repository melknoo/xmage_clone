import { Icon } from '../../lib/icons'

/**
 * Verbindungsverlust: 36-px-Leiste ueber Nav und Inhalt (nie im Spiel). bg #2a1619, Linie Karmin 40 %.
 */
export function ConnectionBar({ retryInSec, onRetry, text }: { retryInSec: number; onRetry: () => void; text?: string }) {
  const secs = Math.max(0, Math.ceil(retryInSec))
  return (
    <div
      className="flex h-9 shrink-0 items-center justify-center gap-2.5 border-b border-attack/40 bg-danger-bg px-4 text-[13.5px] text-fg-1"
      role="alert"
      data-testid="conn-lost-bar"
    >
      <Icon name="disconnected" size={15} className="text-attack" />
      <span className="min-w-0 truncate">{text ?? `Keine Verbindung zum Server. Neuer Versuch in ${secs} s.`}</span>
      <button
        type="button"
        className="shrink-0 font-display text-[13px] font-semibold uppercase leading-none tracking-[.08em] text-attack transition-colors duration-1 hover:text-fg-1"
        onClick={onRetry}
      >
        Jetzt versuchen
      </button>
    </div>
  )
}
