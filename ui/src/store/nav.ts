import { create } from 'zustand'
import type { DeckSpec, Tempo } from '../api/types'

/** 'play' = lokal Spiel-Setup, online Lobby; 'solo' = Spiel-Setup gegen Bots im Server-Modus; 'table' = Tisch */
export type Screen = 'home' | 'decks' | 'play' | 'solo' | 'table' | 'game' | 'stats' | 'admin' | 'account'

/** Einmaliger Auftrag an den Ziel-Screen (z. B. Held -> "Verlauf" oeffnet Statistik im Verlauf-Tab). */
export interface NavIntent {
  statsTab?: 'overview' | 'history'
  decksOverlay?: 'import'
}

export interface LastSetup {
  deck: DeckSpec
  bots: DeckSpec[]
  tempo: Tempo
}

interface NavStore {
  screen: Screen
  /** offener Auftrag fuer den naechsten Screen; der Screen holt ihn mit consumeIntent() ab */
  intent: NavIntent | null
  lastSetup: LastSetup | null
  go: (s: Screen, intent?: NavIntent) => void
  /** liefert den offenen Auftrag einmal und loescht ihn */
  consumeIntent: () => NavIntent | null
  setLastSetup: (s: LastSetup) => void
}

/** Deck-ids gehoeren einer Bibliothek: lokal und jedes Online-Konto haben eigene ids -> Setup je Konto merken */
let setupKey = 'local'

const storageKey = (k: string) => (k === 'local' ? 'magelite.lastSetup' : `magelite.lastSetup.${k}`)

function loadSetup(): LastSetup | null {
  try {
    const raw = localStorage.getItem(storageKey(setupKey))
    return raw ? (JSON.parse(raw) as LastSetup) : null
  } catch {
    return null
  }
}

/** Konto gewechselt (Desktop-App: angemeldet/offline/anderes Konto): gemerktes Setup dieses Kontos laden */
export function setSetupAccount(key: string) {
  if (key === setupKey) return
  setupKey = key
  useNav.setState({ lastSetup: loadSetup() })
}

export const useNav = create<NavStore>((set, get) => ({
  screen: 'home',
  intent: null,
  lastSetup: loadSetup(),
  go: (screen, intent) => set({ screen, intent: intent ?? null }),
  consumeIntent: () => {
    const i = get().intent
    if (i) set({ intent: null })
    return i
  },
  setLastSetup: (s) => {
    try {
      localStorage.setItem(storageKey(setupKey), JSON.stringify(s))
    } catch {
      /* egal */
    }
    set({ lastSetup: s })
  },
}))
