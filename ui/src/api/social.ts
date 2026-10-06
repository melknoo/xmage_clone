// Lobby-Chat, Freunde und Tisch-Einladungen (Server-Modus). Ein Poll liefert alles.
import { api } from './client'

export interface LobbyMsg {
  seq: number
  ts: number
  userId: number
  name: string
  text: string
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
