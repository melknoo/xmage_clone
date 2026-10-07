// Meta-Formate (deutsch). Nullregel: Anzahlen zeigen 0; Quoten, Mittelwerte und XP zeigen "–" (DASH).
import { DASH, missingDash, zeroDash } from '../components/ui/Table'

export { DASH, missingDash, zeroDash }

const WEEKDAY = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa']
const pad2 = (n: number) => String(n).padStart(2, '0')

/** Uhrzeit "21:40" */
export function clockTime(ts: number): string {
  const d = new Date(ts)
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** Relativer Tag: "heute, 21:40" | "gestern, 22:10" | "Mo, 20:15" (bis 6 Tage) | "12.09." */
export function relDay(ts: number, now: number = Date.now()): string {
  const d = new Date(ts)
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((start(new Date(now)) - start(d)) / 86_400_000)
  if (days <= 0) return `heute, ${clockTime(ts)}`
  if (days === 1) return `gestern, ${clockTime(ts)}`
  if (days <= 6) return `${WEEKDAY[d.getDay()]}, ${clockTime(ts)}`
  return `${pad2(d.getDate())}.${pad2(d.getMonth() + 1)}.`
}

/** Stunden "h:mm" (Gesamtspielzeit); 0/fehlend "–" */
export function hours(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms <= 0) return DASH
  const min = Math.round(ms / 60_000)
  return `${Math.floor(min / 60)}:${pad2(min % 60)}`
}

/** Spieldauer: "42 min" unter einer Stunde, sonst "1:31" (h:mm); 0/fehlend "–" */
export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms <= 0) return DASH
  const min = Math.round(ms / 60_000)
  return min < 60 ? `${min} min` : hours(ms)
}

/** Minuten/Sekunden "m:ss" (Spielende, Countdown) */
export function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(s / 60)}:${pad2(s % 60)}`
}

/** Dezimalzahl mit Komma ("2,3"); fehlend "–" */
export function dec(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return DASH
  return v.toFixed(digits).replace('.', ',')
}

/** XP-Gewinn "+190 XP" (negativ mit U+2212); 0/fehlend "–" */
export function xp(n: number | null | undefined): string {
  return zeroDash(n, (v) => `${v > 0 ? '+' : '−'}${Math.abs(v)} XP`)
}

/** Quote "42 %" aus Anteil und Gesamt; Gesamt 0/fehlend "–" */
export function pct(part: number | null | undefined, total: number | null | undefined): string {
  if (!total || part === null || part === undefined) return DASH
  return `${Math.round((part / total) * 100)} %`
}

/** Anzahl: immer die Zahl (auch 0); nur fehlend "–" */
export function count(n: number | null | undefined): string {
  return missingDash(n)
}

/** Platzfarbe als CSS-Farbe (style): 1 Gelb (target), 2 fg-2, sonst fg-3 */
export function placeColor(place: number | null | undefined): string {
  if (place === 1) return 'var(--color-target)'
  if (place === 2) return 'var(--color-fg-2)'
  return 'var(--color-fg-3)'
}
