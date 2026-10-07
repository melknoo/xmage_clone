// Lobby-Chat, Freunde und Tisch-Einladungen (Server-Modus). Ein Poll liefert alles.
import { api } from './client'
import type { Tempo } from './types'

export interface LobbyMsg {
  seq: number
  ts: number
  userId: number
  name: string
  text: string
  /** Systemzeile (userId 0, text vollstaendig, ohne Namensanzeige), z.B. "Hanna ist dem Lobby-Chat beigetreten" */
  sys?: boolean
}

export interface LobbyMember {
  id: number
  name: string
}

export type FriendStatus = 'online' | 'table' | 'game' | 'offline'

export interface Friend {
  id: number
  name: string
  status: FriendStatus
  tableId?: string | null
  tableName?: string | null
}

export interface FriendRequest {
  userId: number
  name: string
  state: 'incoming' | 'outgoing'
  since: number
}

export interface TableInvite {
  id: number
  fromUserId: number
  fromName: string
  toUserId: number
  tableId: string
  tableName: string
  ts: number
  /** Ablauf (Serverzeit ms) = ts + 10 min; Countdown mit SocialSnapshot.now gegen Uhrversatz */
  expiresAt?: number
  /** nur im Poll (an mich): Menschen am Tisch (n/4) */
  humans?: number
  /** nur im Poll (an mich): Tempo des Tisches */
  tempo?: Tempo
}

/** Von mir verschickte, noch gueltige Einladung (Tisch einladbar, Freund sitzt nicht) */
export interface SentInvite {
  id: number
  toUserId: number
  /** fehlt/null, wenn die Person nicht (mehr) in meiner Freundesliste ist */
  toName?: string | null
  tableId: string
  expiresAt: number
}

/** Mein Tisch (Home-/Lobby-Leiste) */
export interface MyTable {
  id: string
  name: string
  tempo: Tempo
  state: 'LOBBY' | 'RUNNING'
  humans: number
  /** ich bin Gastgeber */
  host: boolean
  /** LOBBY und mindestens ein Platz OPEN */
  invitable: boolean
}

export interface SocialSnapshot {
  chatIn: boolean
  seq: number
  msgs: LobbyMsg[]
  members: LobbyMember[]
  friends: Friend[]
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  invites: TableInvite[]
  /** Serverzeit (ms) beim Poll, fuer den Uhrversatz der Countdowns */
  now?: number
  /** Anzahl sichtbarer Online-Mitglieder (wie members.length) */
  online?: number
  /** Anzahl offener Tische */
  tables?: number
  /** Tisch, an dem ich sitze, sonst null */
  myTable?: MyTable | null
  /** meine offenen Einladungen */
  sent?: SentInvite[]
}

export const socialApi = {
  poll: (after: number) => api.get<SocialSnapshot>(`/api/social?after=${after}`),
  say: (text: string) => api.post<LobbyMsg>('/api/social/chat', { text }),
  setIn: (inChat: boolean) => api.put<{ in: boolean }>('/api/social/chat', { in: inChat }),
  request: (who: { name?: string; userId?: number }) => api.post<{ state: 'outgoing' | 'friend' }>('/api/friends', who),
  accept: (userId: number) => api.post<{ state: 'friend' }>(`/api/friends/${userId}/accept`),
  remove: (userId: number) => api.del<{ ok: boolean }>(`/api/friends/${userId}`),
  invite: (tableId: string, userId: number) => api.post<TableInvite>(`/api/tables/${encodeURIComponent(tableId)}/invite`, { userId }),
  decline: (inviteId: number) => api.del<{ ok: boolean }>(`/api/social/invites/${inviteId}`),
}
