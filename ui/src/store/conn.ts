import { create } from 'zustand'
import { apiUrl, setConnectionListener } from '../api/client'

/** automatischer neuer Versuch alle 4 s */
export const RETRY_MS = 4000
/** einzelner Health-Versuch bricht nach 10 s ab */
const PROBE_TIMEOUT_MS = 10_000

/**
 * Verbindung zum Server (Meta-Screens; das Spiel hat seinen eigenen WS-Zustand in store/game.ts).
 * Gespeist ueber den Listener in api/client.ts: fetch-TypeError oder 502/503/504 -> offline, jede andere Antwort -> online.
 * Offline prueft der Store GET /api/health alle 4 s (pausiert bei verstecktem Tab, damit fly die Maschine schlafen
 * lassen kann). Erst aktiv, wenn App.tsx nach dem Boot `setEnabled(true)` ruft (vorher zeigt der Boot-Screen den Zustand).
 */
interface ConnStore {
  offline: boolean
  /** Zeitpunkt (ms) des naechsten automatischen Versuchs, null = keiner geplant (z. B. Tab versteckt) */
  retryAt: number | null
  /** Server-Version aus /api/health (Login-Fuss "v…") */
  version: string | null
  /** Beobachtung aktiv (nach dem Boot) */
  enabled: boolean
  /** sofort neu versuchen ("Jetzt versuchen") */
  retryNow: () => void
  setOffline: (b: boolean, retryAt?: number) => void
  setVersion: (v: string | null) => void
  setEnabled: (b: boolean) => void
}

let timer: number | null = null
let probing = false
let listening = false
/** nach der Rueckkehr (offline -> online) aufgerufen, z. B. Social sofort neu laden */
const recoverHandlers = new Set<() => void>()

/** Rueckruf, wenn die Verbindung wieder da ist; liefert die Abmeldung. */
export function onConnectionRecovered(fn: () => void): () => void {
  recoverHandlers.add(fn)
  return () => {
    recoverHandlers.delete(fn)
  }
}

function clearTimer() {
  if (timer !== null) window.clearTimeout(timer)
  timer = null
}

export const useConn = create<ConnStore>((set, get) => {
  const schedule = () => {
    clearTimer()
    if (!get().offline) return
    if (document.hidden) {
      set({ retryAt: null })
      return
    }
    set({ retryAt: Date.now() + RETRY_MS })
    timer = window.setTimeout(() => {
      timer = null
      void probe()
    }, RETRY_MS)
  }

  const probe = async () => {
    if (probing || !get().offline) return
    probing = true
    const ctl = new AbortController()
    const kill = window.setTimeout(() => ctl.abort(), PROBE_TIMEOUT_MS)
    let ok = false
    try {
      const res = await fetch(apiUrl('/api/health'), { signal: ctl.signal })
      ok = res.ok
      if (ok) {
        const body = (await res.json().catch(() => null)) as { version?: string } | null
        if (body?.version) set({ version: body.version })
      }
    } catch {
      ok = false
    } finally {
      window.clearTimeout(kill)
      probing = false
    }
    if (ok) markOnline()
    else schedule()
  }

  const markOnline = () => {
    if (!get().offline) return
    clearTimer()
    set({ offline: false, retryAt: null })
    for (const fn of recoverHandlers) {
      try {
        fn()
      } catch {
        /* egal */
      }
    }
  }

  const markOffline = () => {
    if (get().offline) return
    set({ offline: true })
    schedule()
  }

  const onVisible = () => {
    if (!get().offline) return
    if (document.hidden) {
      clearTimer()
      set({ retryAt: null })
    } else void probe()
  }

  setConnectionListener((ok) => {
    if (!get().enabled) return
    if (ok) markOnline()
    else markOffline()
  })

  return {
    offline: false,
    retryAt: null,
    version: null,
    enabled: false,
    retryNow: () => {
      clearTimer()
      set({ retryAt: null })
      void probe()
    },
    setOffline: (b) => (b ? markOffline() : markOnline()),
    setVersion: (v) => set({ version: v }),
    setEnabled: (b) => {
      if (b === get().enabled) return
      if (b && !listening) {
        listening = true
        document.addEventListener('visibilitychange', onVisible)
      }
      if (!b) {
        clearTimer()
        set({ enabled: false, offline: false, retryAt: null })
        return
      }
      set({ enabled: true })
    },
  }
})
