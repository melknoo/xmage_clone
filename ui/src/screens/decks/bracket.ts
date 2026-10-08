// Commander-Brackets (WotC 1-5): Anzeige von Vorschlag (Engine: BracketAnalyzer) und manueller Wahl.
import type { BracketReason, StoredDeck } from '../../api/decks'

export const BRACKET_NAME: Record<number, string> = {
  1: 'Exhibition',
  2: 'Core',
  3: 'Upgraded',
  4: 'Optimized',
  5: 'cEDH',
}

const REASON_LABEL: Record<BracketReason['kind'], string> = {
  gameChanger: 'Game Changer',
  combo: '2-Karten-Combo',
  mld: 'Massen-Landzerstörung',
  extraTurn: 'Extra-Züge',
  tutor: 'Tutoren',
}

/** gueltige Bracket eines Decks: manuell, sonst Vorschlag; auto = nur Vorschlag */
export function deckBracket(d: Pick<StoredDeck, 'bracket' | 'bracketAuto'>): { value: number; auto: boolean } | null {
  if (d.bracket) return { value: d.bracket, auto: false }
  if (d.bracketAuto) return { value: d.bracketAuto, auto: true }
  return null
}

/** "Game Changer: Rhystic Study, Cyclonic Rift" je Grund */
export function reasonLines(reasons: BracketReason[] | undefined): string[] {
  return (reasons ?? []).map((r) => `${REASON_LABEL[r.kind] ?? r.kind} (${r.cards.length}): ${r.cards.join(', ')}`)
}

/** Tooltip fuer den Bracket-Chip */
export function bracketTitle(d: Pick<StoredDeck, 'bracket' | 'bracketAuto' | 'bracketInfo'>): string {
  const b = deckBracket(d)
  if (!b) return 'Noch keine Bracket'
  const head = `Bracket ${b.value} · ${BRACKET_NAME[b.value]}${b.auto ? ' (Vorschlag)' : ''}`
  const auto = !b.auto && d.bracketAuto && d.bracketAuto !== b.value ? `Vorschlag wäre ${d.bracketAuto}` : null
  const lines = reasonLines(d.bracketInfo)
  return [head, auto, ...(lines.length ? lines : b.auto ? ['Keine Game Changer, Combos, Landzerstörung oder Extra-Züge'] : [])].filter(Boolean).join('\n')
}
