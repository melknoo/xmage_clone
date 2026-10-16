import { create } from 'zustand'
import { api } from '../api/client'
import { idlePaused } from '../lib/activity'
import { useAuth } from './auth'
import { pushToast } from './ui'

/** GET /api/host/link (nur lokale Engine): Host-Link-Stand plus Konto/Tisch auf dem Server */
export interface HostLinkStatus {
  enabled: boolean
  connected: boolean
  server: string | null
  error?: string | null
  games: { gameId: string; tableName: string | null; humans: number; bots: number; turn: number }[]
  userName?: string | null
  table?: { id: string; name: string; state: 'LOBBY' | 'RUNNING'; humans: number; host: boolean; hosting: string; locked: boolean } | null
}

const POLL_MS = 5000

interface HostLinkStore {
  st: HostLinkStatus | null
  running: boolean
  busy: boolean
  start: () => void
  stop: () => void
  refresh: () => Promise<void>
  /** eigenen Online-Tisch schliessen/verlassen (Toast bei Erfolg/Fehler) */
  closeTable: () => Promise<void>
  /** Online abmelden: Electron loescht Server-Session + Cookie und trennt den Link */
  logout: () => Promise<void>
}

let timer: number | null = null

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/**
 * Online-Stand der lokalen Desktop-App (Konto-Button in der NavRail). Die lokale Engine haengt sich nach der Anmeldung
 * auf dem Server an (Host-Link, gesteuert von Electron ueber das Cookie ml_sess) und meldet Konto-Name und offenen
 * Online-Tisch. Poll alle 5 s, ruht bei verstecktem Fenster oder 15 min ohne Eingabe - die Engine fragt dafuer den
 * Server, und das zaehlt dort als Aktivitaet (sonst hielte eine offene App die fly-Maschine wach).
 */
export const useHostLink = create<HostLinkStore>((set, get) => {
  const tick = async () => {
    timer = null
    if (!get().running) return
    if (!idlePaused()) await get().refresh()
    if (get().running) timer = window.setTimeout(() => void tick(), POLL_MS)
  }

  return {
    st: null,
    running: false,
    busy: false,
    start: () => {
      if (get().running) return
      set({ running: true })
      void tick()
    },
    stop: () => {
      set({ running: false })
      if (timer != null) window.clearTimeout(timer)
      timer = null
    },
    refresh: async () => {
      try {
        set({ st: await api.get<HostLinkStatus>('/api/host/link') })
      } catch {
        set({ st: null })
      }
    },
    closeTable: async () => {
      set({ busy: true })
      try {
        set({ st: await api.post<HostLinkStatus>('/api/host/table/leave') })
        pushToast({ kind: 'success', text: 'Online-Tisch geschlossen' })
      } catch (e) {
        pushToast({ kind: 'error', text: errText(e) })
      } finally {
        set({ busy: false })
      }
    },
    logout: async () => {
      const fn = window.magelite?.logoutOnline
      if (!fn) return
      set({ busy: true })
      try {
        await fn()
        pushToast({ kind: 'success', text: 'Online abgemeldet' })
      } catch (e) {
        pushToast({ kind: 'error', text: errText(e) })
      } finally {
        set({ busy: false })
        await get().refresh()
        // Konto-Modus: abgemeldet -> Gate (Engine kennt keine Session mehr)
        await useAuth.getState().refreshAccount()
      }
    },
  }
})
