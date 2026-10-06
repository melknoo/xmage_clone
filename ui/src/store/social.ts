import { create } from 'zustand'
import { ApiError } from '../api/client'
import { socialApi, type Friend, type FriendRequest, type LobbyMember, type LobbyMsg, type SocialSnapshot, type TableInvite } from '../api/social'

const POLL_MS = 3000
/** Ohne Maus/Tastatur so lange -> "abwesend", Polling ruht (fly darf die Maschine schlafen legen). */
const AWAY_MS = 15 * 60_000
const KEEP = 100

interface SocialStore {
  running: boolean
  /** erster Poll da */
  loaded: boolean
  away: boolean
  chatIn: boolean
  /** hoechste bekannte Nachrichten-Nummer (Cursor fuer ?after=) */
  seq: number
  msgs: LobbyMsg[]
  members: LobbyMember[]
  friends: Friend[]
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  invites: TableInvite[]
  /** zuletzt gelesene Nachricht (Ungelesen-Zaehler am Nav) */
  readSeq: number
  start: () => void
  stop: () => void
  /** Abmelden: alles vergessen (naechster Nutzer startet leer) */
  reset: () => void
  refresh: () => Promise<void>
  markRead: () => void
  say: (text: string) => Promise<string | null>
  setIn: (inChat: boolean) => Promise<string | null>
  request: (who: { name?: string; userId?: number }) => Promise<string | null>
  accept: (userId: number) => Promise<string | null>
  remove: (userId: number) => Promise<string | null>
  invite: (tableId: string, userId: number) => Promise<string | null>
  decline: (inviteId: number) => Promise<void>
}

let timer: number | null = null
let lastInput = Date.now()
let listening = false
let inflight = false

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export const useSocial = create<SocialStore>((set, get) => {
  const apply = (s: SocialSnapshot, after: number) => {
    const cur = get()
    let msgs: LobbyMsg[]
    if (!s.chatIn) msgs = []
    else if (after === 0) msgs = s.msgs
    else msgs = [...cur.msgs, ...s.msgs].slice(-KEEP)
    set({
      loaded: true,
      chatIn: s.chatIn,
      seq: s.chatIn ? s.seq : 0,
      msgs,
      members: s.members,
      friends: s.friends,
      incoming: s.incoming,
      outgoing: s.outgoing,
      invites: s.invites,
      // erster Poll: alles bisherige gilt als gelesen
      readSeq: cur.loaded ? cur.readSeq : s.seq,
    })
  }

  const poll = async () => {
    if (inflight) return
    inflight = true
    try {
      let after = get().seq
      const s = await socialApi.poll(after)
      // Server neu gestartet (Verlauf nur im Speicher): Cursor zuruecksetzen
      if (s.seq < after) {
        after = 0
        apply(await socialApi.poll(0), 0)
        set({ readSeq: 0 })
      } else {
        apply(s, after)
      }
    } catch (e) {
      // Netz/Neustart: einfach weiter pollen; 401 zeigt ohnehin den Login
      if (e instanceof ApiError && e.status === 401) get().stop()
    } finally {
      inflight = false
    }
  }

  const schedule = () => {
    if (timer !== null) window.clearTimeout(timer)
    timer = window.setTimeout(tick, POLL_MS)
  }

  const tick = async () => {
    timer = null
    if (!get().running) return
    const away = Date.now() - lastInput > AWAY_MS
    if (away !== get().away) set({ away })
    if (!away && !document.hidden) await poll()
    if (get().running) schedule()
  }

  const onInput = () => {
    lastInput = Date.now()
    if (get().away && get().running) {
      set({ away: false })
      void poll()
    }
  }
  const onVisible = () => {
    if (!document.hidden && get().running) void poll()
  }

  return {
    running: false,
    loaded: false,
    away: false,
    chatIn: true,
    seq: 0,
    msgs: [],
    members: [],
    friends: [],
    incoming: [],
    outgoing: [],
    invites: [],
    readSeq: 0,

    start: () => {
      if (get().running) return
      set({ running: true })
      if (!listening) {
        listening = true
        for (const ev of ['pointerdown', 'keydown', 'mousemove', 'wheel']) window.addEventListener(ev, onInput, { passive: true })
        document.addEventListener('visibilitychange', onVisible)
      }
      lastInput = Date.now()
      void poll().then(schedule)
    },
    stop: () => {
      set({ running: false })
      if (timer !== null) window.clearTimeout(timer)
      timer = null
    },
    reset: () => {
      get().stop()
      set({ loaded: false, away: false, chatIn: true, seq: 0, msgs: [], members: [], friends: [], incoming: [], outgoing: [], invites: [], readSeq: 0 })
    },
    refresh: poll,
    markRead: () => set({ readSeq: get().seq }),

    say: async (text) => {
      try {
        await socialApi.say(text)
        await poll()
        set({ readSeq: get().seq })
        return null
      } catch (e) {
        return errText(e)
      }
    },
    setIn: async (inChat) => {
      try {
        await socialApi.setIn(inChat)
        set({ chatIn: inChat, msgs: [], seq: 0 })
        await poll()
        set({ readSeq: get().seq })
        return null
      } catch (e) {
        return errText(e)
      }
    },
    request: async (who) => {
      try {
        await socialApi.request(who)
        await poll()
        return null
      } catch (e) {
        return errText(e)
      }
    },
    accept: async (userId) => {
      try {
        await socialApi.accept(userId)
        await poll()
        return null
      } catch (e) {
        return errText(e)
      }
    },
    remove: async (userId) => {
      try {
        await socialApi.remove(userId)
        await poll()
        return null
      } catch (e) {
        return errText(e)
      }
    },
    invite: async (tableId, userId) => {
      try {
        await socialApi.invite(tableId, userId)
        return null
      } catch (e) {
        return errText(e)
      }
    },
    decline: async (inviteId) => {
      set({ invites: get().invites.filter((i) => i.id !== inviteId) })
      try {
        await socialApi.decline(inviteId)
      } catch {
        /* schon weg */
      }
    },
  }
})
