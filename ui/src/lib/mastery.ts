/**
 * Deck-Meisterschaft. Spiegelt die Engine (Progression.masteryLevel/masteryNext): Stufe 1..10 ab
 * 0, 200, 500, 900, 1500, 2300, 3300, 4600, 6200, 8200 XP. Neue Engines liefern masteryLevel/masteryNext selbst
 * (StoredDeck, DeckStat) - diese Kopie ist der Fallback.
 */
export const MASTERY_THRESHOLDS = [0, 200, 500, 900, 1500, 2300, 3300, 4600, 6200, 8200] as const
export const MASTERY_MAX_LEVEL = MASTERY_THRESHOLDS.length

export interface Mastery {
  /** Stufe 1..10 */
  level: number
  /** XP gesamt */
  xp: number
  /** XP seit Beginn der Stufe */
  into: number
  /** XP-Spanne der Stufe (>= 1) */
  next: number
  /** absolute Schwelle der naechsten Stufe ("{xp} / {threshold} XP"); bei Hoechststufe die letzte Schwelle */
  threshold: number
  /** Fortschritt 0..1 (Hoechststufe: 1) */
  pct: number
  /** Hoechststufe erreicht */
  max: boolean
}

export function mastery(xp: number | null | undefined): Mastery {
  const v = Math.max(0, xp ?? 0)
  let level = 1
  for (let i = 0; i < MASTERY_THRESHOLDS.length; i++) if (v >= MASTERY_THRESHOLDS[i]) level = i + 1
  const max = level >= MASTERY_MAX_LEVEL
  const cur: number = MASTERY_THRESHOLDS[level - 1]
  const threshold: number = MASTERY_THRESHOLDS[level] ?? MASTERY_THRESHOLDS[MASTERY_THRESHOLDS.length - 1]
  const next = Math.max(1, threshold - cur)
  return { level, xp: v, into: v - cur, next, threshold, pct: max ? 1 : Math.min(1, (v - cur) / next), max }
}

/** Alte Signatur (DecksScreen/StatsScreen): Stufe, XP in der Stufe, Spanne. */
export function masteryLevel(xp: number): { level: number; into: number; next: number } {
  const m = mastery(xp)
  return { level: m.level, into: m.into, next: m.next }
}
