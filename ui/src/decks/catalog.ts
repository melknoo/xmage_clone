// Deck-Katalog: eigene Decks (/api/decks) und mitgelieferte (/api/samples) mit kleinem Cache.
// Jeder Screen, der useDeckCatalog() nutzt, zeigt sofort den Cache und laedt beim Mounten neu (stale-while-revalidate).
import { useEffect } from 'react'
import { create } from 'zustand'
import { api, cardImageUrl } from '../api/client'
import type { StoredDeck } from '../api/decks'
import type { DeckSpec, SampleDeck } from '../api/types'
import { mastery, type Mastery } from '../lib/mastery'

/** Anzeigedaten einer Deck-Angabe */
export interface DeckInfo {
  kind: 'user' | 'sample' | 'random'
  name: string
  /** Commander mit " & " (bisheriger Name; = commander) */
  sub: string
  commander: string
  /** Farbidentitaet ("WUB"; "" bei Zufall) */
  colors: string
  /** Commander-Kunst (art_crop) */
  art: string | null
  /** Herkunft: "Textliste" | "Archidekt" | "Moxfield" | "Vorgefertigt" | "" (Zufall) */
  src: string
  /** Zusatzzeile: "Eigenes Deck · Stufe N" | "Commander 2014 · C14" | "Jede Partie neu gemischt" */
  setLine: string
  /** nur eigene Decks */
  mastery: Mastery | null
  /** nur eigene Decks: legal? */
  valid?: boolean
}

const SOURCE: Record<string, string> = { text: 'Textliste', archidekt: 'Archidekt', moxfield: 'Moxfield' }

const artOf = (set?: string, num?: string) => (set && num ? cardImageUrl({ set, num }, { size: 'art_crop' }) : null)

/** Anzeigedaten zu einer Deck-Angabe; null, wenn das Deck (noch) nicht im Katalog ist. */
export function describeDeck(spec: DeckSpec | null | undefined, decks: StoredDeck[], samples: SampleDeck[]): DeckInfo | null {
  if (!spec) return null
  if (spec.type === 'random') {
    return { kind: 'random', name: 'Zufälliges Deck', sub: 'aus den mitgelieferten Commander-Decks', commander: '', colors: '', art: null, src: '', setLine: 'Jede Partie neu gemischt', mastery: null }
  }
  if (spec.type === 'user') {
    const d = decks.find((x) => x.id === spec.id)
    if (!d) return null
    const m = mastery(d.masteryXp)
    const commander = d.commanders.join(' & ')
    return {
      kind: 'user',
      name: d.name,
      sub: commander,
      commander,
      colors: d.colors,
      art: artOf(d.commanderSet, d.commanderNum),
      src: SOURCE[d.source] ?? d.source,
      setLine: `Eigenes Deck · Stufe ${d.masteryLevel ?? m.level}`,
      mastery: m,
      valid: d.valid,
    }
  }
  const s = samples.find((x) => x.id === spec.id)
  if (!s) return null
  const commander = s.commanders.join(' & ')
  return {
    kind: 'sample',
    name: s.name,
    sub: commander,
    commander,
    colors: s.colors,
    art: artOf(s.commanderSet, s.commanderNum),
    src: 'Vorgefertigt',
    setLine: [s.group, s.commanderSet?.toUpperCase()].filter(Boolean).join(' · '),
    mastery: null,
  }
}

interface CatalogStore {
  decks: StoredDeck[]
  samples: SampleDeck[]
  /** mindestens einmal geladen */
  loaded: boolean
  /** neu laden (eigene Decks immer, mitgelieferte nur beim ersten Mal oder mit all) */
  refresh: (all?: boolean) => Promise<void>
  /** nach Import/Loeschen/Abmelden: Cache verwerfen und neu laden */
  invalidate: () => void
  /** Abmelden: alles vergessen */
  reset: () => void
}

export const useCatalogStore = create<CatalogStore>((set, get) => ({
  decks: [],
  samples: [],
  loaded: false,
  refresh: async (all) => {
    const needSamples = all || get().samples.length === 0
    const [decks, samples] = await Promise.all([
      api.get<StoredDeck[]>('/api/decks').catch(() => [] as StoredDeck[]),
      needSamples ? api.get<SampleDeck[]>('/api/samples').catch(() => [] as SampleDeck[]) : Promise.resolve(get().samples),
    ])
    set({ decks, samples, loaded: true })
  },
  invalidate: () => {
    set({ loaded: false })
    void get().refresh()
  },
  reset: () => set({ decks: [], samples: [], loaded: false }),
}))

/** Cache verwerfen (ausserhalb von React, z. B. nach dem Speichern eines Decks) */
export const invalidateDeckCatalog = () => useCatalogStore.getState().invalidate()

/** Eigene und mitgelieferte Decks; `describe` macht aus einer Deck-Angabe Anzeigedaten. Laedt beim Mounten neu. */
export function useDeckCatalog() {
  const decks = useCatalogStore((s) => s.decks)
  const samples = useCatalogStore((s) => s.samples)
  const loaded = useCatalogStore((s) => s.loaded)
  useEffect(() => {
    void useCatalogStore.getState().refresh()
  }, [])
  const describe = (spec: DeckSpec | null | undefined): DeckInfo | null => describeDeck(spec, decks, samples)
  return { decks, samples, loaded, describe }
}
