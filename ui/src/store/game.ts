import { create } from 'zustand'
import { wsUrl } from '../api/client'
import type { Answer, Card, GameOver, GameState, Hello, LogEntry, Prompt, RichSeg, ServerMessage, Tempo, UUID } from '../api/types'
import { sounds } from '../lib/sounds'

export interface Toast {
  id: number
  level: string
  rich: RichSeg[]
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
  waitingFor: string | null
  toasts: Toast[]
  gameOver: GameOver | null
  hover: Card | null
  tempo: Tempo
  objects: Map<UUID, Card>

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
  reset: () => void
}

let socket: WebSocket | null = null
let pingTimer: number | undefined
let reconnectTimer: number | undefined
let toastSeq = 0
const LOG_MAX = 600

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
  return m
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
        set({ hello: msg, tempo: (msg.tempo as Tempo) ?? 'NORMAL' })
        break
      case 'state': {
        const prev = get().state
        if (prev && msg.turn !== prev.turn && msg.activePlayerId === msg.myPlayerId) sounds.play('turn')
        set({ state: msg, objects: indexObjects(msg) })
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
      case 'status':
        set({ thinking: msg.thinking ?? null, waitingFor: msg.waitingFor ?? null })
        break
      case 'toast':
        pushToast(msg.level, msg.rich)
        break
      case 'gameOver':
        set({ gameOver: msg, prompt: null, thinking: null })
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
    ws.onclose = () => {
      if (socket !== ws) return
      set({ conn: 'closed' })
      window.clearInterval(pingTimer)
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
    waitingFor: null,
    toasts: [],
    gameOver: null,
    hover: null,
    tempo: 'NORMAL',
    objects: new Map(),

    connect: (gameId) => {
      get().disconnect()
      set({ gameId, hello: null, state: null, prompt: null, log: [], gameOver: null, toasts: [], thinking: null })
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
    reset: () => {
      get().disconnect()
      set({ gameId: null, hello: null, state: null, prompt: null, log: [], gameOver: null, toasts: [], thinking: null })
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
