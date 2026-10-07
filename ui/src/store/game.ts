import { create } from 'zustand'
import { wsUrl } from '../api/client'
import { useAuth } from './auth'
import { anchorOf, objCenter, objRect, sourceCenter, stackAnchor, zoneAnchor, zoneKey, type Point, type Rect } from '../game/overlayGeometry'
import { fxSlow, fxTiming } from '../lib/motion'
import { isSpectateClose, SPECTATE_CLOSE_TEXT } from '../api/types'
import type { Activity, Answer, Card, GameOver, GameState, Hello, LogEntry, Prompt, ReplacementMode, RichSeg, SeatConn, ServerMessage, Tempo, UUID, FxEvent, ChatEntry } from '../api/types'
import { sounds } from '../lib/sounds'
import { useNav } from './nav'
import { pushToast as pushUiToast } from './ui'

/** Spielverlauf-Filter: Routine ausblenden ('important') oder alles zeigen */
export type LogFilter = 'important' | 'all'

/** Zonen-Ansicht (Friedhof / Exil / oberste Bibliothekskarte) eines Spielers */
export interface ZoneView {
  playerId: UUID
  tab: 'gy' | 'ex' | 'lib'
}

export interface ConnectOptions {
  /** als Zuschauer verbinden (?spectate=1); bleibt ueber Reconnects erhalten */
  spectate?: boolean
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
  gameOver: GameOver | null
  hover: Card | null
  tempo: Tempo
  objects: Map<UUID, Card>
  reveals: Reveal[]
  /** Mehrfach-Angriff/-Block: per Shift+Klick markierte eigene Kreaturen */
  marked: Set<UUID>
  /** ich habe aufgegeben (Spiel laeuft ggf. fuer die anderen weiter) */
  conceded: boolean
  /** Pausemenue offen */
  menuOpen: boolean
  setMenuOpen: (open: boolean) => void
  /** Verbindungszustand der Mitspieler (nur bei mehreren Menschen), nach playerId */
  seatConn: Record<UUID, SeatConn>
  /** ab dieser Trennungsdauer (ms) darf man einen Mitspieler aufgeben lassen (von der Engine) */
  kickAfterMs: number
  /** einen laenger getrennten Mitspieler aufgeben lassen */
  kick: (playerId: UUID) => void
  /** laufende Mini-Animationen (Geisterkarten, schwebende Zahlen) */
  fx: FxItem[]
  /** Ereignisleiste: die letzten Ereignisse (~5 s) */
  recent: FxItem[]
  /** Brett-FX: frisch aufs Spielfeld gekommene Permanents (id -> ms), solange die Eintritts-Animation laeuft */
  entered: Record<UUID, number>
  /** Brett-FX: frisch erklaerte Angreifer (id -> ms), solange der Angriffsstoss laeuft */
  lunging: Record<UUID, number>
  /** Animationen an/aus (Leiste bleibt) */
  fxEnabled: boolean
  setFxEnabled: (on: boolean) => void
  /** Chat zwischen den Menschen am Tisch */
  chat: ChatEntry[]
  unreadChat: number
  /** Chat-Tab sichtbar (dann kein Ungelesen-Zaehler/Toast) */
  chatOpen: boolean
  setChatOpen: (open: boolean) => void
  sendChat: (text: string) => void
  /** Zuschauernamen (WS "seats") */
  spectators: string[]
  /** Zonen-Ansicht offen (ZoneViewer) */
  viewer: ZoneView | null
  setViewer: (v: ZoneView | null) => void
  /** Stapelobjekt unter der Maus (Ziel-Etiketten); null = alle */
  stackFocus: UUID | null
  setStackFocus: (id: UUID | null) => void
  /** Spielverlauf-Filter (localStorage 'magelite.logFilter') */
  logFilter: LogFilter
  setLogFilter: (f: LogFilter) => void
  /** Ton aus (lib/sounds, localStorage 'magelite.mute') */
  muted: boolean
  setMuted: (m: boolean) => void
  /** Zuschauer-Verbindung: keine Eingaben, keine Toene, kein Ungelesen-Zaehler */
  spectator: boolean

  connect: (gameId: UUID, opts?: ConnectOptions) => void
  disconnect: () => void
  /** Zuschauen beenden: Verbindung trennen, zurueck zur Lobby */
  stopSpectating: () => void
  answer: (a: Answer) => void
  action: (name: string, data?: string) => void
  setTempo: (t: Tempo) => void
  autoPay: () => void
  autoMana: boolean
  setAutoMana: (on: boolean) => void
  autoPass: boolean
  setAutoPass: (on: boolean) => void
  leave: () => void
  setHover: (c: Card | null) => void
  dismissReveal: (key: string) => void
  /** markiert alle ids bzw. hebt die Markierung auf, wenn schon alle markiert sind */
  toggleMarks: (ids: UUID[]) => void
  clearMarks: () => void
  /** alle Markierten greifen target an (Spieler/Planeswalker) bzw. blocken den Angreifer target */
  combatMany: (target: UUID) => void
  /** "Angriff zuruecksetzen": alle eigenen Angreifer wieder zuruecknehmen (vor dem Bestaetigen) */
  combatReset: () => void
  /** CHOOSE_ABILITY: Faehigkeit N-mal aktivieren (Engine haelt dazwischen die Prioritaet) */
  repeat: (abilityId: UUID, times: number) => void
  /** PLAY_MANA: Kreatur einberufen (Convoke); Aktionswahl, Ziel und Farbe beantwortet die Engine */
  specialPay: (permId: UUID) => void
  /**
   * Ersatzeffekt-Wahl: accept = Effekt key anwenden (Folge-Frage beantwortet die Engine), decline = alle optionalen
   * ablehnen, acceptGroup = alle Quellen der Gruppe key (= ReplGroup.rule, nur bei uniform) anwenden
   */
  replacement: (mode: ReplacementMode, key?: string, always?: boolean) => void
  /** "Fuer dieses Spiel merken" der abgelehnten Ersatzeffekte zuruecknehmen */
  resetReplDeclines: () => void
  reset: () => void
}

/** Ereignis mit Bildschirmpositionen (beim Empfang erfasst - der DOM zeigt dann noch den Zustand davor) */
export interface FxItem extends FxEvent {
  key: number
  at: number
  /** Start-/Zielposition auf dem Bildschirm (from/to sind die XMage-Zonen) */
  src?: Point
  dst?: Point
  /** Schaden: Position der Quelle - der Treffer-Funke fliegt von hier nach src */
  hit?: Point
  /** Kartenflaeche: Schadensziel (roter Blitz) bzw. sterbende Karte (Zerbersten, Geisterkarte in Kartengroesse) */
  rect?: Rect
  /** ms bis zum Einschlag: Schaden = Flugzeit des Funkens, Tod = Rest eines noch fliegenden Funkens auf diese Karte */
  delay?: number
}
let fxSeq = 0
/** Lebensdauer eines FX-Stapels: laengste Kette = Funke (240) + Schadenszahl (1200) */
const FX_MS = 1500
const FX_MAX = 40
/** Funke erst ab diesem Abstand Quelle -> Ziel (sonst nur Einschlag) */
const HIT_MIN_PX = 12
const RECENT_MS = 5000
const RECENT_MAX = 6
const CHAT_MAX = 200
/** Ereignisse, die in der Leiste stehen (Zonenwechsel, Spieler-Schaden, groessere Lebensaenderungen) */
const STRIP_KINDS = new Set(['died', 'tokenDied', 'exiled', 'bounced', 'tucked', 'discarded', 'milled', 'countered', 'command'])

function reducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

let socket: WebSocket | null = null
let pingTimer: number | undefined
let reconnectTimer: number | undefined
/** Ankunft des aktuellen Prompts: ein "Weiter" in den ersten ms gilt als doppelter Tastendruck (Main 2 ueberspringen) */
let promptAt = 0
const PASS_GUARD_MS = 250
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

/** erster State nach "hello" (Verbinden/Reconnect) ist ein Komplettstand - kein Betreten/Angriff animieren */
let afterHello = true

/** Brett-Diff aller Spieler: neue Permanent-ids (Betreten) und neu erklaerte Angreifer (Angriffsstoss) */
function boardDiff(prev: GameState, next: GameState): { entered: UUID[]; attackers: UUID[] } {
  const before = new Set<UUID>()
  for (const p of prev.players) for (const c of p.battlefield) before.add(c.id)
  const entered: UUID[] = []
  for (const p of next.players) for (const c of p.battlefield) if (!before.has(c.id)) entered.push(c.id)
  const wasAttacking = new Set((prev.combat ?? []).flatMap((g) => g.attackers))
  const attackers = (next.combat ?? []).flatMap((g) => g.attackers).filter((id) => !wasAttacking.has(id))
  return { entered, attackers }
}

export const useGame = create<GameStore>((set, get) => {
  function send(msg: { t: string; [k: string]: unknown }) {
    // Zuschauer senden nur Pings; alle Eingaben sind wirkungslos
    if (get().spectator && msg.t !== 'ping') return
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(msg))
    }
  }

  /** Toene nur fuer Spieler, nie fuer Zuschauer */
  function play(name: Parameters<typeof sounds.play>[0]) {
    if (!get().spectator) sounds.play(name)
  }

  /**
   * Brett-FX vormerken: liefert die neue Tabelle (ids -> at) fuer set() und traegt die ids nach der CSS-Animation
   * wieder aus (sonst liefe sie beim naechsten Neuaufbau der Gruppe, z. B. Tappen, noch einmal).
   */
  function pulse(field: 'entered' | 'lunging', ids: UUID[], at: number, ms: number): Record<UUID, number> {
    // Reste verpasster Timer (z. B. Tab im Hintergrund) gleich mit aufraeumen
    const next = Object.fromEntries(Object.entries(get()[field]).filter(([, t]) => at - t < 2000))
    for (const id of ids) next[id] = at
    window.setTimeout(() => {
      const cur = get()[field]
      const left = Object.entries(cur).filter(([, t]) => t !== at)
      if (left.length === Object.keys(cur).length) return
      const v = Object.fromEntries(left)
      set(field === 'entered' ? { entered: v } : { lunging: v })
    }, ms + 60)
    return next
  }

  function handle(msg: ServerMessage) {
    switch (msg.t) {
      case 'hello':
        // nach einem Reconnect schickt die Engine Verlauf, State, offenen Prompt und ggf. "seat" erneut
        afterHello = true
        set({ hello: msg, tempo: (msg.tempo as Tempo) ?? 'NORMAL', log: [], prompt: null, answeredPromptId: null, conceded: false, fx: [], recent: [], entered: {}, lunging: {}, chat: [], unreadChat: 0 })
        break
      case 'state': {
        const prev = get().state
        // veraltete States (Reconnect-Rennen) verwerfen
        if (prev && msg.seq < prev.seq) break
        if (prev && msg.turn !== prev.turn && msg.myPlayerId && msg.activePlayerId === msg.myPlayerId) play('turn')
        const fresh = newReveals(msg)
        // Betreten/Angriff im selben set wie der State, sonst blitzt die neue Karte einen Frame ohne Animation auf
        const board = prev && !afterHello && get().fxEnabled && !reducedMotion() ? boardDiff(prev, msg) : null
        afterHello = false
        const now = Date.now()
        const t = fxTiming(get().tempo)
        set({
          state: msg,
          objects: indexObjects(msg),
          ...(fresh.length ? { reveals: [...get().reveals, ...fresh].slice(-4) } : {}),
          ...(board?.entered.length ? { entered: pulse('entered', board.entered, now, t.enter) } : {}),
          ...(board?.attackers.length ? { lunging: pulse('lunging', board.attackers, now, t.lunge) } : {}),
        })
        for (const r of fresh) window.setTimeout(() => get().dismissReveal(r.key), REVEAL_MS)
        break
      }
      case 'prompt': {
        // Markierungen gelten nur im Angriffs-/Blockmodus und nur fuer noch waehlbare Kreaturen
        const keep = msg.kind === 'SELECT' && msg.mode === 'attackers' ? msg.possibleAttackers : msg.kind === 'SELECT' && msg.mode === 'blockers' ? msg.possibleBlockers : undefined
        const marked = get().marked
        const nextMarked = marked.size && keep ? new Set([...marked].filter((id) => keep.includes(id))) : marked.size ? new Set<UUID>() : marked
        promptAt = Date.now()
        set({ prompt: msg, answeredPromptId: null, thinking: null, waitingFor: null, marked: nextMarked })
        if (msg.kind !== 'SELECT' || msg.mode !== 'priority') play('prompt')
        break
      }
      case 'promptClosed':
        if (get().prompt?.id === msg.id) set({ prompt: null })
        break
      case 'log': {
        const log = get().log.concat(msg.entries)
        set({ log: log.length > LOG_MAX ? log.slice(log.length - LOG_MAX) : log })
        break
      }
      case 'events':
        onEvents(msg.items)
        break
      case 'chat': {
        const chat = get().chat.concat(msg.entries).slice(-CHAT_MAX)
        const live = msg.entries.length === 1 && msg.entries[0].playerId !== get().hello?.myPlayerId
        const unseen = live && !get().chatOpen && !get().spectator
        set({ chat, ...(unseen ? { unreadChat: get().unreadChat + 1 } : {}) })
        if (unseen) {
          const e = msg.entries[0]
          pushToast('chat', [{ text: `${e.name}: ${e.text}` }])
          play('prompt')
        }
        break
      }
      case 'activity':
        set({ activity: msg, activityAt: Date.now() })
        break
      case 'status':
        set({ thinking: msg.thinking ?? null, waitingFor: msg.waitingFor ?? null })
        break
      case 'seat':
        set({ conceded: msg.conceded })
        break
      case 'seats': {
        const seatConn: Record<UUID, SeatConn> = {}
        for (const s of msg.seats) seatConn[s.playerId] = s
        set({ seatConn, kickAfterMs: msg.kickAfterMs || 60000, spectators: msg.spectators ?? [] })
        break
      }
      case 'toast':
        pushToast(msg.level, msg.rich)
        break
      case 'gameOver':
        set({ gameOver: msg, prompt: null, thinking: null, activity: null })
        play(msg.placements.find((p) => p.playerId === get().hello?.myPlayerId)?.place === 1 ? 'win' : 'lose')
        break
      case 'error':
        pushToast('error', [{ text: msg.message }])
        break
    }
  }

  /**
   * Positionen sofort erfassen: der passende State kommt erst danach, der DOM zeigt noch die alte Lage.
   * Schaden: zusaetzlich Quelle (Funke) und Kartenflaeche des Ziels; Tod: Kartenflaeche und ggf. Wartezeit auf einen
   * noch fliegenden Funken auf dieselbe Karte (gleicher oder frueherer Stapel).
   */
  function onEvents(items: FxEvent[]) {
    const now = Date.now()
    const animate = get().fxEnabled && !reducedMotion()
    const t = fxTiming(get().tempo)
    const fxNew: FxItem[] = []
    const recentNew: FxItem[] = []
    for (const e of items) {
      const item: FxItem = { ...e, key: ++fxSeq, at: now }
      if (animate) {
        if (e.kind === 'damage' || e.kind === 'life' || e.kind === 'counter') {
          item.src = (e.objectId ? objCenter(e.objectId) : null) ?? (e.playerId ? anchorOf(e.playerId) : null) ?? undefined
          if (e.kind === 'damage' && item.src) {
            if (e.objectId) item.rect = objRect(e.objectId) ?? undefined
            const hit = e.sourceId && e.sourceId !== e.objectId ? sourceCenter(e.sourceId) : null
            if (hit && Math.hypot(hit.x - item.src.x, hit.y - item.src.y) > HIT_MIN_PX) {
              item.hit = hit
              item.delay = t.hit
            }
          }
        } else if (e.kind === 'countered' || e.from === 'STACK') {
          item.src = stackAnchor(e.objectId) ?? undefined
          item.dst = zoneAnchor(e.ownerId ?? e.playerId, zoneKey(e.to) ?? 'graveyard') ?? undefined
        } else {
          item.src = (e.objectId ? objCenter(e.objectId) : null) ?? zoneAnchor(e.ownerId ?? e.playerId, zoneKey(e.from)) ?? undefined
          item.dst = e.kind === 'tokenDied' ? item.src : (zoneAnchor(e.ownerId ?? e.playerId, zoneKey(e.to)) ?? undefined)
          if ((e.kind === 'died' || e.kind === 'tokenDied') && e.objectId) {
            item.rect = objRect(e.objectId) ?? undefined
            const wait = [...fxNew, ...get().fx].reduce((w, i) => (i.hit && i.objectId === e.objectId ? Math.max(w, i.at + (i.delay ?? 0) - now) : w), 0)
            if (wait > 0) item.delay = wait
          }
        }
        if (item.src) fxNew.push(item)
      }
      const strip = STRIP_KINDS.has(e.kind) || (e.kind === 'damage' && !e.objectId && (e.amount ?? 0) > 0) || (e.kind === 'life' && Math.abs(e.amount ?? 0) >= 3)
      if (strip && !(e.hidden && !e.name)) recentNew.push(item)
    }
    if (fxNew.length) {
      set({ fx: [...get().fx, ...fxNew].slice(-FX_MAX) })
      window.setTimeout(() => {
        const keys = new Set(fxNew.map((i) => i.key))
        set({ fx: get().fx.filter((i) => !keys.has(i.key)) })
      }, FX_MS * fxSlow())
    }
    if (recentNew.length) {
      set({ recent: [...get().recent, ...recentNew].slice(-RECENT_MAX) })
      window.setTimeout(() => {
        const keys = new Set(recentNew.map((i) => i.key))
        set({ recent: get().recent.filter((i) => !keys.has(i.key)) })
      }, RECENT_MS)
    }
  }

  /** Spiel-Toasts laufen ueber das gemeinsame Toast-System (store/ui.ts): Engine-Level info/error, Chat als info mit Icon */
  function pushToast(level: string, rich: RichSeg[]) {
    if (level === 'chat') pushUiToast({ kind: 'info', icon: 'chat', rich })
    else pushUiToast({ kind: level === 'error' ? 'error' : level === 'success' ? 'success' : 'info', rich })
  }

  function open(gameId: UUID) {
    window.clearTimeout(reconnectTimer)
    set({ conn: 'connecting' })
    const ws = new WebSocket(wsUrl(`/ws/game/${gameId}${get().spectator ? '?spectate=1' : ''}`))
    socket = ws
    ws.onopen = () => {
      if (socket !== ws) return
      set({ conn: 'open' })
      send({ t: 'settings', autoPay: get().autoMana, autoPass: get().autoPass })
      window.clearInterval(pingTimer)
      pingTimer = window.setInterval(() => send({ t: 'ping' }), 20000)
    }
    ws.onmessage = (ev) => {
      // Nachzuegler einer geschlossenen Verbindung (altes Spiel) ignorieren
      if (socket !== ws) return
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
      if (get().spectator && isSpectateClose(ev.code)) {
        // Zuschauen: diese Codes sind endgueltig (kein Reconnect) -> Toast, zurueck zur Lobby
        socket = null
        get().reset()
        useNav.getState().go('play')
        pushUiToast({ kind: 'info', icon: 'spectate', text: SPECTATE_CLOSE_TEXT[ev.code] })
        return
      }
      if (ev.code === 4404 || ev.code === 4401 || ev.code === 4403) {
        // 4404: Spiel existiert nicht mehr (Engine neu gestartet); 4401: Anmeldung ungueltig; 4403: fremdes Spiel
        socket = null
        set({ gameId: null, hello: null, state: null, prompt: null, gameOver: null, thinking: null, spectator: false, viewer: null })
        useNav.getState().go('home')
        if (ev.code === 4401) {
          useAuth.getState().markLoggedOut()
        } else {
          pushToast('info', [{ text: ev.code === 4403 ? 'Dieses Spiel gehört einem anderen Spieler.' : 'Das Spiel ist nicht mehr vorhanden (Engine wurde neu gestartet).' }])
        }
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
    gameOver: null,
    hover: null,
    tempo: 'NORMAL',
    objects: new Map(),
    reveals: [],
    marked: new Set(),
    conceded: false,
    menuOpen: false,
    setMenuOpen: (open) => set({ menuOpen: open }),
    seatConn: {},
    kickAfterMs: 60000,
    kick: (playerId) => send({ t: 'kick', playerId }),
    chat: [],
    unreadChat: 0,
    chatOpen: false,
    setChatOpen: (open) => set({ chatOpen: open, ...(open ? { unreadChat: 0 } : {}) }),
    sendChat: (text) => {
      const t = text.trim()
      if (t) send({ t: 'chat', text: t.slice(0, 300) })
    },
    spectators: [],
    viewer: null,
    setViewer: (v) => set({ viewer: v }),
    stackFocus: null,
    setStackFocus: (id) => {
      if (get().stackFocus !== id) set({ stackFocus: id })
    },
    logFilter: loadLogFilter(),
    setLogFilter: (f) => {
      try {
        localStorage.setItem('magelite.logFilter', f)
      } catch {
        /* egal */
      }
      set({ logFilter: f })
    },
    muted: sounds.isMuted(),
    setMuted: (m) => {
      sounds.setMuted(m)
      set({ muted: m })
    },
    spectator: false,
    fx: [],
    recent: [],
    entered: {},
    lunging: {},
    fxEnabled: loadBool('magelite.fx', true),
    setFxEnabled: (on) => {
      saveBool('magelite.fx', on)
      set({ fxEnabled: on, ...(on ? {} : { fx: [], entered: {}, lunging: {} }) })
    },

    connect: (gameId, opts) => {
      get().disconnect()
      seenReveals = new Set()
      afterHello = true
      set({ gameId, spectator: !!opts?.spectate, hello: null, state: null, prompt: null, answeredPromptId: null, log: [], gameOver: null, thinking: null, activity: null, waitingFor: null, hover: null, reveals: [], objects: new Map(), marked: new Set(), conceded: false, menuOpen: false, seatConn: {}, spectators: [], viewer: null, stackFocus: null, fx: [], recent: [], entered: {}, lunging: {}, chat: [], unreadChat: 0 })
      open(gameId)
    },
    stopSpectating: () => {
      get().reset()
      useNav.getState().go('play')
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
      // Passen direkt nach einem neuen Prioritaets-Prompt: wohl noch der Tastendruck fuer den vorigen -> ignorieren
      if (p.kind === 'SELECT' && p.mode === 'priority' && 'bool' in a && a.bool === false && Date.now() - promptAt < PASS_GUARD_MS) return
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
    autoPass: loadBool('magelite.autoPass', true),
    setAutoPass: (on) => {
      saveBool('magelite.autoPass', on)
      set({ autoPass: on })
      send({ t: 'settings', autoPass: on })
    },
    leave: () => {
      // optimistisch; die Engine bestaetigt mit {t:"seat", conceded:true}
      set({ conceded: true })
      send({ t: 'leave' })
    },
    setHover: (c) => set({ hover: c }),
    dismissReveal: (key) => set({ reveals: get().reveals.filter((r) => r.key !== key) }),
    toggleMarks: (ids) => {
      const marked = new Set(get().marked)
      const all = ids.every((id) => marked.has(id))
      for (const id of ids) {
        if (all) marked.delete(id)
        else marked.add(id)
      }
      set({ marked })
    },
    clearMarks: () => {
      if (get().marked.size) set({ marked: new Set() })
    },
    combatMany: (target) => {
      const p = get().prompt
      const ids = [...get().marked]
      if (!p || ids.length === 0 || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id, marked: new Set() })
      send({ t: 'combat', ids, target })
    },
    repeat: (abilityId, times) => {
      const p = get().prompt
      if (!p || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id })
      send({ t: 'repeat', id: p.id, uuid: abilityId, times })
    },
    specialPay: (permId) => {
      const p = get().prompt
      if (!p || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id })
      send({ t: 'specialPay', id: p.id, uuid: permId })
    },
    combatReset: () => {
      const p = get().prompt
      if (!p || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id, marked: new Set() })
      send({ t: 'combatReset' })
    },
    replacement: (mode, key, always) => {
      const p = get().prompt
      if (!p || get().answeredPromptId === p.id) return
      set({ answeredPromptId: p.id })
      send({ t: 'replacement', mode, key, always: !!always })
    },
    resetReplDeclines: () => {
      const s = get().state
      if (s?.replDeclines) set({ state: { ...s, replDeclines: undefined } })
      send({ t: 'replReset' })
    },
    reset: () => {
      get().disconnect()
      set({ gameId: null, spectator: false, hello: null, state: null, prompt: null, answeredPromptId: null, log: [], gameOver: null, thinking: null, activity: null, hover: null, reveals: [], marked: new Set(), conceded: false, menuOpen: false, spectators: [], viewer: null, stackFocus: null, fx: [], recent: [], entered: {}, lunging: {}, chat: [], unreadChat: 0 })
    },
  }
})

function loadLogFilter(): LogFilter {
  try {
    return localStorage.getItem('magelite.logFilter') === 'all' ? 'all' : 'important'
  } catch {
    return 'important'
  }
}

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
