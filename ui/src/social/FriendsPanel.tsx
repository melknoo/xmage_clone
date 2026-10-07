import { useState } from 'react'
import type { Friend } from '../api/social'
import { Button, StatusDot } from '../components/ui'
import { Icon } from '../lib/icons'
import { sentInviteFor, useSocial } from '../store/social'
import { pushToast } from '../store/ui'
import { FriendRow, type InviteState } from './FriendRow'
import { inviteStatusText } from './InviteFriendsList'

export interface FriendsPanelProps {
  /** Tisch fuer "Einladen"; Standard: mein Tisch (useSocial().myTable), solange er einladbar ist */
  inviteTableId?: string | null
  /** userIds, die schon an diesem Tisch sitzen */
  seatedIds?: number[]
  /** Altbestand (ohne Wirkung) */
  compact?: boolean
}

/**
 * Tab "Freunde": Freund per exaktem Namen hinzufuegen, Anfragen an mich, Freundesliste (online → am Tisch → im Spiel →
 * offline) mit Einladen-Knopf, solange ich an einem einladbaren Tisch sitze, darunter gesendete Anfragen.
 * Entfernen und Zurueckziehen als Hover-Icons.
 */
export function FriendsPanel({ inviteTableId, seatedIds = [] }: FriendsPanelProps) {
  const friends = useSocial((s) => s.friends)
  const incoming = useSocial((s) => s.incoming)
  const outgoing = useSocial((s) => s.outgoing)
  const loaded = useSocial((s) => s.loaded)
  const myTable = useSocial((s) => s.myTable)
  const sent = useSocial((s) => s.sent)
  const clockOffset = useSocial((s) => s.clockOffset)
  const request = useSocial((s) => s.request)
  const accept = useSocial((s) => s.accept)
  const remove = useSocial((s) => s.remove)
  const invite = useSocial((s) => s.invite)
  const [name, setName] = useState('')
  const [addErr, setAddErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const tableId = inviteTableId !== undefined ? inviteTableId : myTable?.invitable ? myTable.id : null

  const add = async () => {
    const n = name.trim()
    if (!n || busy) return
    setBusy(true)
    const r = await request({ name: n })
    setBusy(false)
    if (r.error) setAddErr(r.error)
    else {
      setName('')
      setAddErr(null)
    }
  }

  const act = async (p: Promise<string | null>, okText?: string) => {
    const err = await p
    if (err) pushToast({ kind: 'error', text: err })
    else if (okText) pushToast({ kind: 'success', text: okText })
  }

  const inviteState = (f: Friend, seated: boolean): InviteState => {
    if (!tableId) return { kind: 'none' }
    if (seated) return { kind: 'seated' }
    const s = sentInviteFor(sent, tableId, f.id, clockOffset)
    return s ? { kind: 'sent', expiresAt: s.expiresAt } : { kind: 'can' }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto scrollbar-thin" data-testid="friends-panel">
      <div className="flex flex-col gap-[7px] border-b border-line-1 px-[18px] py-3.5">
        <label htmlFor="friend-add" className="font-display text-[12px] font-semibold uppercase leading-none tracking-[.12em] text-fg-3">
          Freund hinzufügen · exakter Name
        </label>
        <div className="flex gap-1.5">
          <div className="field flex-1" style={{ height: 38, padding: '0 10px' }} data-error={addErr ? 'true' : undefined}>
            <input
              id="friend-add"
              placeholder="z. B. Hanna"
              maxLength={24}
              value={name}
              data-testid="friend-add-input"
              aria-invalid={addErr ? true : undefined}
              onChange={(e) => {
                setName(e.target.value)
                setAddErr(null)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  void add()
                }
              }}
            />
          </div>
          <Button variant="secondary" size="sm" style={{ height: 38 }} testId="friend-add-submit" disabled={!name.trim() || busy} onClick={() => void add()}>
            Anfragen
          </Button>
        </div>
        {addErr && (
          <span className="field-error" role="alert">
            <Icon name="error" size={14} />
            {addErr}
          </span>
        )}
      </div>

      {incoming.length > 0 && (
        <div className="flex flex-col gap-2 border-b border-line-1 px-[18px] py-3" data-testid="friends-incoming">
          <span className="font-display text-[12px] font-semibold uppercase leading-none tracking-[.12em] text-ember">Anfragen an dich</span>
          {incoming.map((r) => (
            <div key={r.userId} className="flex items-center gap-2" data-name={r.name}>
              <span className="min-w-0 flex-1 truncate text-[14px] font-semibold text-fg-1">{r.name}</span>
              <Button variant="ghost" size="xs" testId="friend-decline" onClick={() => void act(remove(r.userId), `Anfrage von ${r.name} abgelehnt`)}>
                Ablehnen
              </Button>
              <Button variant="primary" size="xs" style={{ height: 30, padding: '0 10px', fontSize: 13, letterSpacing: '.06em' }} testId="friend-accept" onClick={() => void act(accept(r.userId))}>
                Annehmen
              </Button>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-0.5 px-[18px] py-3">
        <span className="pb-1.5 font-display text-[12px] font-semibold uppercase leading-none tracking-[.12em] text-fg-3">Freunde · {friends.length}</span>
        {loaded && friends.length === 0 && outgoing.length === 0 && (
          <span className="py-2 text-[13px] leading-[1.45] text-fg-3">Noch keine Freunde. Füge jemanden per Name hinzu oder klick im Lobby-Chat auf einen Namen.</span>
        )}
        {friends.map((f) => {
          const seated = !!tableId && (seatedIds.includes(f.id) || (f.status === 'table' && f.tableId?.toLowerCase() === tableId.toLowerCase()))
          return (
            <FriendRow
              key={f.id}
              friend={f}
              variant="list"
              invite={inviteState(f, seated)}
              statusText={tableId ? inviteStatusText(f, seated) : undefined}
              onInvite={() => void act(invite(tableId ?? '', f.id))}
              onRemove={() => void act(remove(f.id), `${f.name} entfernt`)}
            />
          )
        })}
        {outgoing.map((r) => (
          <div key={r.userId} className="group flex items-center gap-2.5 border-b border-line-1 py-[9px] text-fg-3" data-testid="friend-outgoing" data-name={r.name}>
            <StatusDot status="pending" />
            <span className="min-w-0 flex-1 truncate text-[14px]">{r.name}</span>
            <span className="font-display text-[12px] font-semibold uppercase leading-none tracking-[.08em]">Anfrage gesendet</span>
            <Button
              variant="icon"
              icon="close"
              title="Anfrage zurückziehen"
              aria-label="Anfrage zurückziehen"
              className="-my-1 opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
              onClick={() => void act(remove(r.userId), `Anfrage an ${r.name} zurückgezogen`)}
            />
          </div>
        ))}
      </div>
    </div>
  )
}
