import { create } from 'zustand'
import type { DeckSpec, Tempo } from '../api/types'

export type Screen = 'home' | 'decks' | 'play' | 'game' | 'stats'

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
