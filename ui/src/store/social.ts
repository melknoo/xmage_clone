import { create } from 'zustand'
import { ApiError } from '../api/client'
import {
  socialApi,
  type Friend,
  type FriendRequest,
  type LobbyMember,
  type LobbyMsg,
  type MyTable,
  type SentInvite,
  type SocialSnapshot,
  type TableInvite,
} from '../api/social'
import { useAuth } from './auth'
import { pushToast } from './ui'

const POLL_MS = 3000
/** Ohne Maus/Tastatur so lange -> "abwesend", Polling ruht (fly darf die Maschine schlafen legen). */
const AWAY_MS = 15 * 60_000
const KEEP = 100
/** Gueltigkeit einer Tisch-Einladung (Engine: ts + 10 min), Rueckfall ohne expiresAt */
const INVITE_MS = 10 * 60_000

/** Ergebnis einer Freundschaftsanfrage: state 'friend' = es gab schon eine Gegenanfrage, ihr seid jetzt befreundet */
export interface RequestResult {
  error: string | null
  state?: 'outgoing' | 'friend'
}

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
  /** Einladungen an mich */
  invites: TableInvite[]
  /** meine offenen Einladungen (vom Server; nach invite() sofort lokal ergaenzt) */
  sent: SentInvite[]
  /** Tisch, an dem ich sitze (null = keiner) */
  myTable: MyTable | null
  /** sichtbare Online-Mitglieder; null = Engine liefert es nicht */
  online: number | null
  /** Anzahl Tische; null = Engine liefert es nicht */
  tables: number | null
  /** Serverzeit minus Clientzeit (ms) beim letzten Poll, fuer Countdowns */
  clockOffset: number
  /** zuletzt gelesene Nachricht (Ungelesen-Zaehler am Nav) */
  readSeq: number
  /** ungelesene Lobby-Nachrichten (seq > readSeq, nicht eigene; 0 ausserhalb des Chats) */
  unreadCount: number
  /** eingehende Freundschaftsanfragen (Badge am Tab "Freunde") */
  incomingCount: number
  start: () => void
  stop: () => void
  /** Abmelden: alles vergessen (naechster Nutzer startet leer) */
  reset: () => void
  refresh: () => Promise<void>
  /** alles bis seq gilt als gelesen; HomeServer ruft es, solange der Held-Screen offen ist */
  markRead: () => void
  say: (text: string) => Promise<string | null>
  /** Chat betreten/verlassen; Erfolg zeigt einen Toast, Fehler kommt zurueck */
  setIn: (inChat: boolean) => Promise<string | null>
  /** Anfrage per Name oder id; Erfolg zeigt einen Toast ("Anfrage an X gesendet" bzw. "X hatte dich schon angefragt …") */
  request: (who: { name?: string; userId?: number }) => Promise<RequestResult>
  /** Anfrage annehmen; Erfolg zeigt "X ist jetzt dein Freund" */
  accept: (userId: number) => Promise<string | null>
  /** Freund entfernen, Anfrage ablehnen oder zurueckziehen (ohne Toast) */
  remove: (userId: number) => Promise<string | null>
  /** Freund an den Tisch einladen; Erfolg ergaenzt sent und zeigt "X eingeladen · gilt 10 Minuten" */
  invite: (tableId: string, userId: number) => Promise<string | null>
  decline: (inviteId: number) => Promise<void>
}

let timer: number | null = null
let lastInput = Date.now()

/**
 * Sollen Polls (Tisch, Lobby, Admin) gerade ruhen? Bei verstecktem Tab oder nach 15 min ohne Eingabe - sonst haelt
 * ein vergessener Tab die fly-Maschine wach. Wie das Social-Polling selbst.
 */
export function pollPaused(): boolean {
  return document.hidden || Date.now() - lastInput > AWAY_MS
}
let listening = false
let inflight = false

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function countUnread(msgs: LobbyMsg[], readSeq: number, chatIn: boolean): number {
  if (!chatIn) return 0
  const meId = useAuth.getState().me?.id
  let n = 0
  // Systemzeilen (beigetreten/verlassen) zaehlen nicht als ungelesen
  for (const m of msgs) if (m.seq > readSeq && !m.sys && m.userId !== meId) n++
  return n
}

const EMPTY = {
  loaded: false,
  away: false,
  chatIn: true,
  seq: 0,
  msgs: [] as LobbyMsg[],
  members: [] as LobbyMember[],
  friends: [] as Friend[],
  incoming: [] as FriendRequest[],
  outgoing: [] as FriendRequest[],
  invites: [] as TableInvite[],
  sent: [] as SentInvite[],
  myTable: null as MyTable | null,
  online: null as number | null,
  tables: null as number | null,
  clockOffset: 0,
  readSeq: 0,
  unreadCount: 0,
  incomingCount: 0,
}

export const useSocial = create<SocialStore>((set, get) => {
  /** set + abgeleitete Zaehler neu berechnen */
  const put = (patch: Partial<SocialStore>) => {
    set(patch)
    const s = get()
    const unreadCount = countUnread(s.msgs, s.readSeq, s.chatIn)
    const incomingCount = s.incoming.length
    if (unreadCount !== s.unreadCount || incomingCount !== s.incomingCount) set({ unreadCount, incomingCount })
  }

  const nameOf = (userId: number): string | undefined => {
    const s = get()
    return (
      s.friends.find((f) => f.id === userId)?.name ??
      s.incoming.find((r) => r.userId === userId)?.name ??
      s.outgoing.find((r) => r.userId === userId)?.name ??
      s.members.find((m) => m.id === userId)?.name
    )
  }

  const apply = (s: SocialSnapshot, after: number) => {
    const cur = get()
    let msgs: LobbyMsg[]
    if (!s.chatIn) msgs = []
    else if (after === 0) msgs = s.msgs
    else msgs = [...cur.msgs, ...s.msgs].slice(-KEEP)
    const clockOffset = typeof s.now === 'number' ? s.now - Date.now() : cur.clockOffset
    // aeltere Engine ohne sent: lokal gemerkte Einladungen behalten, bis sie ablaufen
    const sent = s.sent ?? cur.sent.filter((i) => i.expiresAt > Date.now() + clockOffset)
    put({
      loaded: true,
      chatIn: s.chatIn,
      seq: s.chatIn ? s.seq : 0,
      msgs,
      members: s.members,
      friends: s.friends,
      incoming: s.incoming,
      outgoing: s.outgoing,
      invites: s.invites,
      sent,
      myTable: s.myTable ?? null,
      online: typeof s.online === 'number' ? s.online : null,
      tables: typeof s.tables === 'number' ? s.tables : null,
      clockOffset,
      // erster Poll: alles bisherige gilt als gelesen
      readSeq: cur.loaded ? cur.readSeq : s.seq,
    })
  }

  const poll = async () => {
    if (inflight) return
    inflight = true
    try {
      const after = get().seq
      const s = await socialApi.poll(after)
      // Server neu gestartet (Verlauf nur im Speicher): Cursor zuruecksetzen
      if (s.seq < after) {
        apply(await socialApi.poll(0), 0)
        put({ readSeq: 0 })
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
    ...EMPTY,

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
      set({ ...EMPTY })
    },
    refresh: poll,
    markRead: () => {
      if (get().readSeq !== get().seq || get().unreadCount !== 0) put({ readSeq: get().seq })
    },

    say: async (text) => {
      try {
        await socialApi.say(text)
        await poll()
        put({ readSeq: get().seq })
        return null
      } catch (e) {
        return errText(e)
      }
    },
    setIn: async (inChat) => {
      try {
        await socialApi.setIn(inChat)
        put({ chatIn: inChat, msgs: [], seq: 0 })
        await poll()
        put({ readSeq: get().seq })
        pushToast({ kind: 'success', text: inChat ? 'Lobby-Chat beigetreten' : 'Lobby-Chat verlassen · du bist unsichtbar' })
        return null
      } catch (e) {
        return errText(e)
      }
    },
    request: async (who) => {
      try {
        const r = await socialApi.request(who)
        await poll()
        // Namen in der Schreibweise des Servers (Eingabe ist ohne Gross-/Kleinschreibung)
        const typed = who.name?.trim().toLowerCase()
        const s = get()
        const name =
          (who.userId !== undefined ? nameOf(who.userId) : undefined) ??
          (typed ? [...s.friends.map((f) => f.name), ...s.outgoing.map((o) => o.name)].find((n) => n.toLowerCase() === typed) : undefined) ??
          who.name?.trim() ??
          'Anfrage'
        pushToast({
          kind: 'success',
          text: r.state === 'friend' ? `${name} hatte dich schon angefragt. Ihr seid jetzt befreundet` : `Anfrage an ${name} gesendet`,
        })
        return { error: null, state: r.state }
      } catch (e) {
        return { error: errText(e) }
      }
    },
    accept: async (userId) => {
      const name = nameOf(userId)
      try {
        await socialApi.accept(userId)
        await poll()
        const n = name ?? nameOf(userId)
        pushToast({ kind: 'success', text: n ? `${n} ist jetzt dein Freund` : 'Anfrage angenommen' })
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
        const r = await socialApi.invite(tableId, userId)
        const name = nameOf(userId) ?? null
        const entry: SentInvite = { id: r.id, toUserId: userId, toName: name, tableId: r.tableId ?? tableId, expiresAt: r.expiresAt ?? r.ts + INVITE_MS }
        put({ sent: [...get().sent.filter((i) => !(i.toUserId === userId && i.tableId === entry.tableId)), entry] })
        pushToast({ kind: 'success', text: `${name ?? 'Freund'} eingeladen · gilt 10 Minuten` })
        return null
      } catch (e) {
        return errText(e)
      }
    },
    decline: async (inviteId) => {
      put({ invites: get().invites.filter((i) => i.id !== inviteId) })
      try {
        await socialApi.decline(inviteId)
      } catch {
        /* schon weg */
      }
    },
  }
})

/** Offene Einladung von mir an userId fuer tableId (noch nicht abgelaufen), sonst undefined */
export function sentInviteFor(sent: SentInvite[], tableId: string, userId: number, clockOffset: number): SentInvite | undefined {
  const now = Date.now() + clockOffset
  return sent.find((i) => i.toUserId === userId && i.tableId.toLowerCase() === tableId.toLowerCase() && i.expiresAt > now)
}
