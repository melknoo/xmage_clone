import { useState } from 'react'
import type { Friend } from '../api/social'
import { sentInviteFor, useSocial } from '../store/social'
import { pushToast } from '../store/ui'
import { FRIEND_STATUS_TEXT, FriendRow, type InviteState } from './FriendRow'

export interface InviteFriendsListProps {
  /** Tisch, zu dem eingeladen wird */
  tableId: string
  /** userIds, die schon am Tisch sitzen ("Am Tisch") */
  seatedIds: number[]
  /** popover: Einladen-Popover (Held/Lobby) · panel: Spalte im Tisch-Screen */
  variant: 'popover' | 'panel'
}

const sameTable = (a: string | null | undefined, b: string) => !!a && a.toLowerCase() === b.toLowerCase()

/** Statuszeile im Einladen-Kontext: "an deinem Tisch", "im Spiel · sieht die Einladung nach der Partie" … */
export function inviteStatusText(f: Friend, seated: boolean): string {
  if (seated) return 'an deinem Tisch'
  if (f.status === 'game') return 'im Spiel · sieht die Einladung nach der Partie'
  return FRIEND_STATUS_TEXT[f.status]
}

/**
 * Freundesliste zum Einladen (Status, "Einladen" / "Eingeladen · m:ss" / "Am Tisch", offline nicht einladbar).
 * Reihenfolge wie vom Server: online → am Tisch → im Spiel → offline. Gesendete Einladungen kommen aus useSocial().sent.
 */
export function InviteFriendsList({ tableId, seatedIds, variant }: InviteFriendsListProps) {
  const friends = useSocial((s) => s.friends)
  const sent = useSocial((s) => s.sent)
  const clockOffset = useSocial((s) => s.clockOffset)
  const loaded = useSocial((s) => s.loaded)
  const invite = useSocial((s) => s.invite)
  const [busy, setBusy] = useState<number | null>(null)

  const doInvite = async (f: Friend) => {
    if (busy !== null) return
    setBusy(f.id)
    const err = await invite(tableId, f.id)
    setBusy(null)
    if (err) pushToast({ kind: 'error', text: err })
  }

  if (loaded && friends.length === 0) {
    return (
      <div className={`text-[13px] leading-[1.45] text-fg-3 ${variant === 'popover' ? 'px-2 pb-2' : 'py-3'}`}>
        Noch keine Freunde. Füge sie im Tab „Freunde“ auf der Startseite per Name hinzu.
      </div>
    )
  }

  return (
    <div className="flex flex-col" data-testid="invite-friends">
      {friends.map((f) => {
        const seated = seatedIds.includes(f.id) || (f.status === 'table' && sameTable(f.tableId, tableId))
        const s = seated ? undefined : sentInviteFor(sent, tableId, f.id, clockOffset)
        const state: InviteState = seated ? { kind: 'seated' } : s ? { kind: 'sent', expiresAt: s.expiresAt } : { kind: 'can' }
        return (
          <FriendRow
            key={f.id}
            friend={f}
            variant={variant === 'popover' ? 'popover' : 'table'}
            invite={state}
            statusText={inviteStatusText(f, seated)}
            onInvite={() => void doInvite(f)}
          />
        )
      })}
    </div>
  )
}
