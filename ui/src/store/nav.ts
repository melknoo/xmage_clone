import { create } from 'zustand'
import type { DeckSpec, Tempo } from '../api/types'

/** 'play' = lokal Spiel-Setup, online Lobby; 'solo' = Spiel-Setup gegen Bots im Server-Modus; 'table' = Tisch */
export type Screen = 'home' | 'decks' | 'play' | 'solo' | 'table' | 'game' | 'stats' | 'admin' | 'account'

export interface LastSetup {
  deck: DeckSpec
  bots: DeckSpec[]
  tempo: Tempo
}

interface NavStore {
  screen: Screen
  lastSetup: LastSetup | null
  go: (s: Screen) => void
  setLastSetup: (s: LastSetup) => void
}

function loadSetup(): LastSetup | null {
  try {
    const raw = localStorage.getItem('magelite.lastSetup')
    return raw ? (JSON.parse(raw) as LastSetup) : null
  } catch {
    return null
  }
}

export const useNav = create<NavStore>((set) => ({
  screen: 'home',
  lastSetup: loadSetup(),
  go: (screen) => set({ screen }),
  setLastSetup: (s) => {
    try {
      localStorage.setItem('magelite.lastSetup', JSON.stringify(s))
    } catch {
      /* egal */
    }
    set({ lastSetup: s })
  },
}))
