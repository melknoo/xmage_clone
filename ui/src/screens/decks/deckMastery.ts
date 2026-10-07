import type { StoredDeck } from '../../api/decks'
import { mastery, MASTERY_MAX_LEVEL } from '../../lib/mastery'

export interface DeckMasteryView {
  level: number
  xp: number
  /** absolute Schwelle der naechsten Stufe */
  threshold: number
  /** 0..1 */
  pct: number
  max: boolean
  /** "414 / 500 XP" bzw. bei Hoechststufe "8200 XP" */
  text: string
}

/** Meisterschaft eines eigenen Decks: echte Kurve, Engine-Werte (masteryLevel/masteryNext) bevorzugt. */
export function deckMastery(d: Pick<StoredDeck, 'masteryXp' | 'masteryLevel' | 'masteryNext'>): DeckMasteryView {
  const m = mastery(d.masteryXp)
  const level = d.masteryLevel ?? m.level
  const max = m.max || level >= MASTERY_MAX_LEVEL
  const threshold = d.masteryNext && d.masteryNext > 0 ? d.masteryNext : m.threshold
  return { level, xp: m.xp, threshold, pct: max ? 1 : m.pct, max, text: max ? `${m.xp} XP` : `${m.xp} / ${threshold} XP` }
}
