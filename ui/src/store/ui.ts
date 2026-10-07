import { create } from 'zustand'
import type { RichSeg } from '../api/types'
import type { IconName } from '../lib/icons'

/**
 * App-weiter UI-Zustand: Toasts (ein System fuer Meta-Screens und Spiel) und der Minimiert-Zustand des
 * Brett-Dialogs (PromptBar zeigt dann "Dialog offen").
 */

export type ToastKind = 'info' | 'success' | 'error'

export interface UiToast {
  id: number
  kind: ToastKind
  /** schlichter Text (eigene deutsche Meldungen) */
  text?: string
  /** Rich-Segmente aus der Engine (Originalsprache, Objekt-Links) */
  rich?: RichSeg[]
  /** ersetzt das Zustands-Icon (z. B. 'chat') */
  icon?: IconName
}

export type ToastInput = Omit<UiToast, 'id'>

/** hoechstens so viele gleichzeitig; der aelteste faellt weg */
export const MAX_TOASTS = 3
/** Info/Erfolg verschwinden nach 4 s; Fehler bleiben bis zum Klick */
export const TOAST_MS = 4000

interface UiState {
  toasts: UiToast[]
  /** liefert die id */
  pushToast: (t: ToastInput) => number
  dismissToast: (id: number) => void
  clearToasts: () => void
  dialogMinimized: boolean
  setDialogMinimized: (b: boolean) => void
}

let seq = 0
const timers = new Map<number, number>()

function clearTimer(id: number) {
  const h = timers.get(id)
  if (h !== undefined) {
    window.clearTimeout(h)
    timers.delete(id)
  }
}

export const useUi = create<UiState>((set, get) => ({
  toasts: [],
  pushToast: (t) => {
    const id = ++seq
    const all = [...get().toasts, { ...t, id }]
    const dropped = all.slice(0, Math.max(0, all.length - MAX_TOASTS))
    dropped.forEach((d) => clearTimer(d.id))
    set({ toasts: all.slice(-MAX_TOASTS) })
    if (t.kind !== 'error') {
      timers.set(
        id,
        window.setTimeout(() => get().dismissToast(id), TOAST_MS),
      )
    }
    return id
  },
  dismissToast: (id) => {
    clearTimer(id)
    if (get().toasts.some((t) => t.id === id)) set({ toasts: get().toasts.filter((t) => t.id !== id) })
  },
  clearToasts: () => {
    get().toasts.forEach((t) => clearTimer(t.id))
    set({ toasts: [] })
  },
  dialogMinimized: false,
  setDialogMinimized: (b) => {
    if (get().dialogMinimized !== b) set({ dialogMinimized: b })
  },
}))

/** Kurzform ausserhalb von React (z. B. aus anderen Stores) */
export const pushToast = (t: ToastInput) => useUi.getState().pushToast(t)
export const dismissToast = (id: number) => useUi.getState().dismissToast(id)
