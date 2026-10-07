import { useEffect, useMemo, useState } from 'react'
import { clock } from '../lib/format'
import { useSocial } from '../store/social'

/** Gueltigkeit einer Tisch-Einladung (Engine: ts + 10 min) */
export const INVITE_TTL_MS = 10 * 60_000

export interface Countdown {
  /** Restzeit "m:ss" ("0:00" wenn abgelaufen) */
  text: string
  /** Rest / Gesamt, 0..1 (Zeitbalken) */
  ratio: number
  /** Restzeit in ms (>= 0) */
  remainingMs: number
  expired: boolean
}

/**
 * Countdown bis expiresAt (Serverzeit, ms). Uhrversatz: mit serverNow (Serverzeit eines Polls, gerade empfangen)
 * daraus berechnet, sonst der Versatz des letzten Social-Polls (useSocial().clockOffset). Tickt jede Sekunde.
 * totalMs = volle Laufzeit fuer ratio.
 */
export function useCountdown(expiresAt: number | null | undefined, serverNow?: number | null, totalMs: number = INVITE_TTL_MS): Countdown {
  const storeOffset = useSocial((s) => s.clockOffset)
  // Versatz einmal je neuem serverNow bestimmen (Server minus Client)
  const ownOffset = useMemo(() => (serverNow ? serverNow - Date.now() : null), [serverNow])
  const offset = ownOffset ?? storeOffset
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!expiresAt) return
    setNow(Date.now())
    const iv = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(iv)
  }, [expiresAt])
  if (!expiresAt) return { text: clock(0), ratio: 0, remainingMs: 0, expired: true }
  const remainingMs = Math.max(0, expiresAt - (now + offset))
  return { text: clock(remainingMs), ratio: Math.min(1, remainingMs / Math.max(1, totalMs)), remainingMs, expired: remainingMs <= 0 }
}
