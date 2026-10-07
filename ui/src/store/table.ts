import { create } from 'zustand'
import { ApiError } from '../api/client'
import { tablesApi, type Table } from '../api/tables'
import { useNav } from './nav'
import { pushToast } from './ui'

export const KICKED_TEXT = 'Der Gastgeber hat dich vom Tisch entfernt'

interface TableStore {
  /** Tisch, an dem ich sitze (fuer "Zurueck zum Tisch" nach dem Spiel und beim Neuladen) */
  tableId: string | null
  /**
   * Zaehler, der bei jedem setTableId steigt. Abfragen merken ihn vor dem Start (`useTable.getState().stamp`) und
   * geben ihn an observe/observeList mit: Antworten von vor einem Tischwechsel werden so verworfen.
   */
  stamp: number
  setTableId: (id: string | null) => void
  /**
   * Frischer Stand eines Tisches (Tisch-Poll, Antwort einer Aktion). Sitze ich am gemerkten Tisch nicht mehr, hat mich
   * der Gastgeber entfernt (LOBBY): Tisch vergessen, Toast, zur Lobby. Liefert true, wenn ich dort nicht mehr sitze.
   */
  observe: (t: Table, stamp?: number) => boolean
  /** Lobby-Liste: gemerkten Tisch abgleichen (inkl. Entfernt-Erkennung) und den eigenen Tisch uebernehmen. */
  observeList: (list: Table[], stamp?: number) => void
  /** Sitze ich noch am gemerkten Tisch? (z. B. wenn der Social-Poll keinen eigenen Tisch mehr meldet) */
  verify: () => Promise<void>
}

function load(): string | null {
  try {
    return localStorage.getItem('magelite.tableId')
  } catch {
    return null
  }
}

export const useTable = create<TableStore>((set, get) => {
  /** zentral: vom Gastgeber entfernt */
  const kicked = () => {
    get().setTableId(null)
    pushToast({ kind: 'info', icon: 'kick', text: KICKED_TEXT })
    useNav.getState().go('play')
  }

  return {
    tableId: load(),
    stamp: 0,
    setTableId: (id) => {
      try {
        if (id) localStorage.setItem('magelite.tableId', id)
        else localStorage.removeItem('magelite.tableId')
      } catch {
        /* egal */
      }
      set({ tableId: id, stamp: get().stamp + 1 })
    },

    observe: (t, stamp) => {
      const { tableId } = get()
      if (stamp !== undefined && stamp !== get().stamp) return false
      if (!tableId || t.id !== tableId || t.mySeat !== null) return false
      if (t.state === 'LOBBY') {
        kicked()
      } else {
        // laufendes Spiel ohne meinen Platz: Tisch still vergessen
        get().setTableId(null)
        if (useNav.getState().screen === 'table') useNav.getState().go('play')
      }
      return true
    },

    observeList: (list, stamp) => {
      if (stamp !== undefined && stamp !== get().stamp) return
      const { tableId } = get()
      const mine = list.find((t) => t.mySeat !== null) ?? null
      if (tableId && !mine) {
        const cur = list.find((t) => t.id === tableId)
        if (cur && cur.mySeat === null && cur.state === 'LOBBY') {
          kicked()
          return
        }
      }
      const next = mine ? mine.id : null
      if (next !== tableId) get().setTableId(next)
    },

    verify: async () => {
      const { tableId, stamp } = get()
      if (!tableId) return
      try {
        const t = await tablesApi.get(tableId)
        get().observe(t, stamp)
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 404 || stamp !== get().stamp) return
        // Tisch gibt es nicht mehr (Gastgeber hat geschlossen)
        get().setTableId(null)
        if (useNav.getState().screen === 'table') {
          pushToast({ kind: 'info', text: 'Der Tisch wurde geschlossen.' })
          useNav.getState().go('play')
        }
      }
    },
  }
})
