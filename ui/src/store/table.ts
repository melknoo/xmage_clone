import { create } from 'zustand'

interface TableStore {
  /** Tisch, an dem ich sitze (fuer "Zurueck zum Tisch" nach dem Spiel und beim Neuladen) */
  tableId: string | null
  setTableId: (id: string | null) => void
}

function load(): string | null {
  try {
    return localStorage.getItem('magelite.tableId')
  } catch {
    return null
  }
}

export const useTable = create<TableStore>((set) => ({
  tableId: load(),
  setTableId: (id) => {
    try {
      if (id) localStorage.setItem('magelite.tableId', id)
      else localStorage.removeItem('magelite.tableId')
    } catch {
      /* egal */
    }
    set({ tableId: id })
  },
}))
