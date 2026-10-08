// Reihenfolge eigener Decks: erst neue/nie sortierte (zuletzt geaendert zuerst), dann die eigene Reihenfolge (Drag & Drop).
import type { StoredDeck } from '../../api/decks'

export function compareDecks(a: StoredDeck, b: StoredDeck): number {
  const ao = a.sortOrder ?? null
  const bo = b.sortOrder ?? null
  if (ao === null && bo !== null) return -1
  if (ao !== null && bo === null) return 1
  if (ao !== null && bo !== null && ao !== bo) return ao - bo
  return b.updatedAt - a.updatedAt
}

export const sortDecks = (decks: StoredDeck[]) => [...decks].sort(compareDecks)
