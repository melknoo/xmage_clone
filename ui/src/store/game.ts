import { create } from 'zustand'
import { wsUrl } from '../api/client'
import type { Activity, Answer, Card, GameOver, GameState, Hello, LogEntry, Prompt, RichSeg, ServerMessage, Tempo, UUID } from '../api/types'
import { sounds } from '../lib/sounds'
import { useNav } from './nav'

export interface Toast {
  id: number
  level: string
  rich: RichSeg[]
}

/** Aufgedeckte / angesehene Karten. XMage leert sie nach jedem Update, darum haelt der Store sie kurz fest. */
export interface Reveal {
  key: string
  name: string
  looked: boolean
  cards: Card[]
}

interface GameStore {
  gameId: UUID | null
  conn: 'idle' | 'connecting' | 'open' | 'closed'
  hello: Hello | null
  state: GameState | null
  prompt: Prompt | null
  answeredPromptId: number | null
  log: LogEntry[]
  thinking: UUID | null
  /** letzter Herzschlag der Engine */
  activity: Activity | null
  activityAt: number
  waitingFor: string | null
  toasts: Toast[]
  gameOver: GameOver | null
  hover: Card | null
  tempo: Tempo
  objects: Map<UUID, Card>
  reveals: Reveal[]

  connect: (gameId: UUID) => void
  disconnect: () => void
  answer: (a: Answer) => void
  action: (name: string, data?: string) => void
  setTempo: (t: Tempo) => void
  autoPay: () => void
  autoMana: boolean
  setAutoMana: (on: boolean) => void
  leave: () => void
  setHover: (c: Card | null) => void
  dismissToast: (id: number) => void
  dismissReveal: (key: string) => void
  reset: () => void
}

let socket: WebSocket | null = null
let pingTimer: number | undefined
let reconnectTimer: number | undefined
let toastSeq = 0
const LOG_MAX = 600
const REVEAL_MS = 12000
/** schon gezeigte Aufdeckungen (pro Spiel), damit sie nach dem Schliessen nicht erneut aufpoppen */
let seenReveals = new Set<string>()

function indexObjects(s: GameState): Map<UUID, Card> {
  const m = new Map<UUID, Card>()
  for (const p of s.players) {
    for (const c of p.battlefield) m.set(c.id, c)
    for (const c of p.graveyard) m.set(c.id, c)
    for (const c of p.exile) m.set(c.id, c)
    for (const c of p.command) if (c.card) m.set(c.id, c.card)
  }
  for (const c of s.hand) m.set(c.id, c)
  for (const c of s.stack) m.set(c.id, c)
  for (const p of s.players) if (p.topCard) m.set(p.topCard.id, p.topCard)
  for (const n of [...(s.revealed ?? []), ...(s.lookedAt ?? [])]) for (const c of n.cards) if (!m.has(c.id)) m.set(c.id, c)
  return m
}

/** Oberste Bibliothekskarte laeuft ueber den Bibliotheks-Knopf, nicht ueber die Einblendung. */
const TOP_CARD_LOOK = /^top card of/i

function newReveals(s: GameState): Reveal[] {
  const out: Reveal[] = []
  const add = (name: string, cards: Card[], looked: boolean) => {
    if (cards.length === 0 || (looked && TOP_CARD_LOOK.test(name))) return
    const key = `${looked ? 'L' : 'R'}|${name}|${cards.map((c) => c.id).join(',')}`
    if (seenReveals.has(key)) return
    seenReveals.add(key)
    out.push({ key, name, looked, cards })
  }
  for (const r of s.revealed ?? []) add(r.name, r.cards, false)
  for (const r of s.lookedAt ?? []) add(r.name, r.cards, true)
  return out
}

export const useGame = create<GameStore>((set, get) => {
  function send(msg: unknown) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(msg))
    }
  }

  function handle(msg: ServerMessage) {
    switch (msg.t) {
      case 'hello':
        // nach einem Reconnect schickt die Engine Verlauf, State und offenen Prompt erneut
        set({ hello: msg, tempo: (msg.tempo as Tempo) ?? 'NORMAL', log: [], prompt: null, answeredPromptId: null })
        break
      case 'state': {
        const prev = get().state
        // veraltete States (Reconnect-Rennen) verwerfen
        if (prev && msg.seq < prev.seq) break
        if (prev && msg.turn !== prev.turn && msg.activePlayerId === msg.myPlayerId) sounds.play('turn')
        const fresh = newReveals(msg)
        set({ state: msg, objects: indexObjects(msg), ...(fresh.length ? { reveals: [...get().reveals, ...fresh].slice(-4) } : {}) })
        for (const r of fresh) window.setTimeout(() => get().dismissReveal(r.key), REVEAL_MS)
        break
      }
      case 'prompt':
        set({ prompt: msg, answeredPromptId: null, thinking: null, waitingFor: null })
        if (msg.kind !== 'SELECT' || msg.mode !== 'priority') sounds.play('prompt')
        break
      case 'promptClosed':
        if (get().prompt?.id === msg.id) set({ prompt: null })
        break
      case 'log': {
        const log = get().log.concat(msg.entries)
        set({ log: log.length > LOG_MAX ? log.slice(log.length - LOG_MAX) : log })
        break
      }
      case 'activity':
        set({ activity: msg, activityAt: Date.now() })
        break
      case 'status':
        set({ thinking: msg.thinking ?? null, waitingFor: msg.waitingFor ?? null })
        break
      case 'toast':
        pushToast(msg.level, msg.rich)
        break
      case 'gameOver':
        set({ gameOver: msg, prompt: null, thinking: null, activity: null })
        sounds.play(msg.placements.find((p) => p.human)?.place === 1 ? 'win' : 'lose')
        break
      case 'error':
        pushToast('error', [{ text: msg.message }])
        break
    }
  }

  function pushToast(level: string, rich: RichSeg[]) {
    const id = ++toastSeq
    set({ toasts: [...get().toasts.slice(-3), { id, level, rich }] })
    window.setTimeout(() => get().dismissToast(id), level === 'error' ? 9000 : 5000)
  }

  function open(gameId: UUID) {
    window.clearTimeout(reconnectTimer)
    set({ conn: 'connecting' })
    const ws = new WebSocket(wsUrl(`/ws/game/${gameId}`))
    socket = ws
    ws.onopen = () => {
      set({ conn: 'open' })
      send({ t: 'settings', autoPay: get().autoMana })
      window.clearInterval(pingTimer)
      pingTimer = window.setInterval(() => send({ t: 'ping' }), 20000)
    }
    ws.onmessage = (ev) => {
      try {
        handle(JSON.parse(ev.data) as ServerMessage)
      } catch (e) {
        console.error('Nachricht fehlerhaft', e)
      }
    }
    ws.onclose = (ev) => {
      if (socket !== ws) return
      set({ conn: 'closed' })
      window.clearInterval(pingTimer)
      if (ev.code === 4404) {
        // Spiel existiert nicht mehr (z.B. Engine neu gestartet) -> zurueck ins Menue
        socket = null
        set({ gameId: null, hello: null, state: null, prompt: null, gameOver: null, thinking: null })
        useNav.getState().go('home')
        pushToast('info', [{ text: 'Das Spiel ist nicht mehr vorhanden (Engine wurde neu gestartet).' }])
        return
      }
      if (get().gameId === gameId && !get().gameOver) {
        reconnectTimer = window.setTimeout(() => open(gameId), 1500)
      }
    }
  }

  return {
    gameId: null,
    conn: 'idle',
    hello: null,
    state: null,
    prompt: null,
    answeredPromptId: null,
    log: [],
    thinking: null,
    activity: null,
    activityAt: 0,
    waitingFor: null,
    toasts: [],
    gameOver: null,
    hover: null,
    tempo: 'NORMAL',
    objects: new Map(),
    reveals: [],

    connect: (gameId) => {
      get().disconnect()
      seenReveals = new Set()
      set({ gameId, hello: null, state: null, prompt: null, answeredPromptId: null, log: [], gameOver: null, toasts: [], thinking: null, activity: null, waitingFor: null, hover: null, reveals: [], objects: new Map() })
      open(gameId)
    },
    disconnect: () => {
      window.clearTimeout(reconnectTimer)
      window.clearInterval(pingTimer)
      const ws = socket
      socket = null
      ws?.close()
      set({ conn: 'idle' })
    },
    answer: (a) => {
      const p = get().prompt
      if (!p || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id })
      send({ t: 'respond', id: p.id, ...a })
    },
    action: (name, data) => send({ t: 'action', action: name, data }),
    setTempo: (t) => {
      set({ tempo: t })
      send({ t: 'tempo', preset: t })
    },
    autoPay: () => {
      const p = get().prompt
      if (p) set({ answeredPromptId: p.id })
      send({ t: 'autoPay' })
    },
    autoMana: loadBool('magelite.autoMana', true),
    setAutoMana: (on) => {
      saveBool('magelite.autoMana', on)
      set({ autoMana: on })
      send({ t: 'settings', autoPay: on })
    },
    leave: () => send({ t: 'leave' }),
    setHover: (c) => set({ hover: c }),
    dismissToast: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
    dismissReveal: (key) => set({ reveals: get().reveals.filter((r) => r.key !== key) }),
    reset: () => {
      get().disconnect()
      set({ gameId: null, hello: null, state: null, prompt: null, answeredPromptId: null, log: [], gameOver: null, toasts: [], thinking: null, activity: null, hover: null, reveals: [] })
    },
  }
})

function loadBool(key: string, def: boolean): boolean {
  try {
    const v = localStorage.getItem(key)
    return v === null ? def : v === '1'
  } catch {
    return def
  }
}

function saveBool(key: string, v: boolean) {
  try {
    localStorage.setItem(key, v ? '1' : '0')
  } catch {
    /* egal */
  }
}

/** Hilfsfunktionen fuer Komponenten */
export function me(s: GameState | null) {
  return s?.players.find((p) => p.me) ?? null
}

export function opponents(s: GameState | null) {
  return s?.players.filter((p) => !p.me) ?? []
}
