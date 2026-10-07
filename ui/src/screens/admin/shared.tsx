import { useEffect, useState, type ReactNode } from 'react'
import type { PresenceStatus } from '../../api/admin'
import { relDay } from '../../lib/format'
import { pushToast } from '../../store/ui'

const MONTHS = ['Jan.', 'Feb.', 'März', 'Apr.', 'Mai', 'Juni', 'Juli', 'Aug.', 'Sep.', 'Okt.', 'Nov.', 'Dez.']

/**
 * Klartext-Codes dieser Sitzung (erzeugt oder rotiert). Der Server speichert nur den Hash, deshalb laesst sich
 * spaeter nur kopieren, was in dieser Sitzung erzeugt wurde. Lebt bis zum Neuladen.
 */
export const sessionCodes = new Map<number, string>()

export function inviteLink(code: string): string {
  return `${window.location.origin}${window.location.pathname}#invite=${code}`
}

export async function copyInviteLink(code: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(inviteLink(code))
    pushToast({ kind: 'success', text: 'Link kopiert' })
  } catch {
    pushToast({ kind: 'error', text: 'Kopieren nicht möglich – bitte markieren und kopieren.' })
  }
}

/** "heute" | "gestern" | "12. Sep." (Vorjahr mit Jahreszahl) */
export function created(ts: number, now = Date.now()): string {
  const d = new Date(ts)
  const n = new Date(now)
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((start(n) - start(d)) / 86_400_000)
  if (days <= 0) return 'heute'
  if (days === 1) return 'gestern'
  return `${d.getDate()}. ${MONTHS[d.getMonth()]}${d.getFullYear() !== n.getFullYear() ? ` ${d.getFullYear()}` : ''}`
}

/** "gerade eben" | "vor 12 min" | sonst relDay ("heute, 21:40", "Mo, 20:15", "12.09."); null "nie" */
export function ago(ts: number | null | undefined, now = Date.now()): string {
  if (!ts) return 'nie'
  const min = Math.floor((now - ts) / 60_000)
  if (min < 2) return 'gerade eben'
  if (min < 60) return `vor ${min} min`
  return relDay(ts, now)
}

/** Laufzeit "3 h 12 min" | "12 min" | "40 s" */
export function span(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s} s`
  const min = Math.floor(s / 60)
  if (min < 60) return `${min} min`
  const h = Math.floor(min / 60)
  if (h < 48) return `${h} h ${min % 60} min`
  return `${Math.floor(h / 24)} d ${h % 24} h`
}

export function mb(bytes: number): string {
  return `${Math.round(bytes / 1_048_576)} MB`
}

export const STATUS_LABEL: Record<PresenceStatus, string> = {
  online: 'Online',
  table: 'Am Tisch',
  game: 'Im Spiel',
  offline: 'Offline',
}

export function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** Abschnittskopf im Admin-Bereich: Label + optional rechts etwas */
export function AdminSection({ title, right, children, testId }: { title: string; right?: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <section className="flex min-w-0 flex-col gap-2.5" data-testid={testId}>
      <div className="flex items-baseline gap-3 border-b border-line-3 pb-2">
        <span className="label">{title}</span>
        <span className="flex-1" />
        {right}
      </div>
      {children}
    </section>
  )
}

/** Icon-Knoepfe der Listen: Kontur line-3, fg-2 (Prototyp) */
export const ICON_CLASS = '!text-fg-2 hover:!text-fg-1'
export const iconStyle = { boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }

const WIDE_QUERY = '(min-width: 1440px)'

/** Breite >= 1440 (board-Breakpoint) */
export function useWide(): boolean {
  const [wide, setWide] = useState(() => typeof window === 'undefined' || window.matchMedia(WIDE_QUERY).matches)
  useEffect(() => {
    const mq = window.matchMedia(WIDE_QUERY)
    const on = () => setWide(mq.matches)
    on()
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return wide
}
