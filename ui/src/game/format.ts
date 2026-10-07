// Brett-Formate: Namen, Platzfarben, Kunst, deutsche Typbezeichnungen.
import { cardImageUrl } from '../api/client'
import type { Card, CommandObject, GameState, PlayerState, UUID } from '../api/types'

/** Platzfarben in Zugreihenfolge ab mir (state.players[0] = ich bzw. Blickwinkel): seat-1..4 */
export const SEAT_COLORS = ['#ff7a3d', '#8fb3c9', '#a99bc4', '#c9a38f'] as const
/** Farbe fuer unbekannte Spieler (fg-tab) */
export const SEAT_COLOR_UNKNOWN = '#7d786f'

/** Platzfarbe eines Spielers (Index in state.players, die Engine sortiert in Zugfolge ab mir). */
export function seatColor(playerId: UUID | null | undefined, state: Pick<GameState, 'players'> | null | undefined): string {
  const i = playerId && state ? state.players.findIndex((p) => p.id === playerId) : -1
  return i >= 0 ? SEAT_COLORS[i % SEAT_COLORS.length] : SEAT_COLOR_UNKNOWN
}

/** Kurzname fuer Etiketten/Chips: bis zum Komma bzw. vor " of the "/" of " ("Kotori, Pilot Prodigy" -> "Kotori"). */
export function shortName(name: string | null | undefined): string {
  if (!name) return ''
  if (name.startsWith('Ob Nixilis')) return 'Ob Nixilis'
  let n = name.split(',')[0]
  const of = n.search(/ of (the )?/)
  if (of > 0) n = n.slice(0, of)
  return n.trim()
}

/** Objekt der Kommandozone als Karte (Zoom, CardView). */
export function commandCard(o: CommandObject): Card {
  return o.card ?? { id: o.id, name: o.name, set: o.set, num: o.num, rules: o.rules, image: o.image, imageNum: o.imageNum }
}

/** Commander (auch "commander-away") eines Spielers als Karte, sonst null. */
export function commanderCard(p: PlayerState): Card | null {
  const c = p.command.find((o) => o.kind === 'commander' || o.kind === 'commander-away')
  return c ? commandCard(c) : null
}

/** Deutsche Namen fuer Spielermarken (Gift, Energie ...); unbekannte bleiben im Original. */
export function counterName(name: string): string {
  const n = name.toLowerCase()
  if (n.includes('poison')) return 'Gift'
  if (n.includes('energy')) return 'Energie'
  if (n.includes('experience')) return 'Erfahrung'
  if (n.includes('rad')) return 'Strahlung'
  if (n.includes('ticket')) return 'Tickets'
  return name
}

/** Commander-Kunst (art_crop) eines Spielers, sonst null. */
export function commanderArt(p: PlayerState): string | null {
  const c = p.command.find((o) => o.kind === 'commander' || o.kind === 'commander-away')
  if (!c?.set || !c.num) return null
  return cardImageUrl({ set: c.set, num: c.num }, { size: 'art_crop' })
}

const TYPE_LABEL: [string, string][] = [
  ['CREATURE', 'Kreatur'],
  ['PLANESWALKER', 'Planeswalker'],
  ['BATTLE', 'Schlacht'],
  ['INSTANT', 'Spontanzauber'],
  ['SORCERY', 'Hexerei'],
  ['ARTIFACT', 'Artefakt'],
  ['ENCHANTMENT', 'Verzauberung'],
  ['LAND', 'Land'],
]

const TRIGGER_TEXT = /^(when|whenever|at)\b/i

/**
 * Deutsche Art eines Objekts fuer Stapel/Zoom: Faehigkeiten "Auslöser" bzw. "Fähigkeit" (abilityType, Fallback
 * Regeltext "When/Whenever/At ..."), sonst der wichtigste Kartentyp ("Kreatur", "Spontanzauber" ...). Leer, wenn unbekannt.
 */
export function typeLabel(c: Pick<Card, 'kind' | 'types' | 'abilityType' | 'rules'>): string {
  if (c.kind === 'ability') {
    if (c.abilityType === 'triggered') return 'Auslöser'
    if (!c.abilityType && TRIGGER_TEXT.test(c.rules?.[0] ?? '')) return 'Auslöser'
    return 'Fähigkeit'
  }
  const types = c.types ?? []
  for (const [t, label] of TYPE_LABEL) if (types.includes(t)) return label
  return ''
}

/** Minuszeichen U+2212 statt Bindestrich ("−3") */
export function signed(n: number): string {
  return n < 0 ? `−${Math.abs(n)}` : n > 0 ? `+${n}` : '0'
}
