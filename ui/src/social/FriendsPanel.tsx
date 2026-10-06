import { useState } from 'react'
import type { Friend, FriendStatus } from '../api/social'
import { useSocial } from '../store/social'

const STATUS: Record<FriendStatus, { dot: string; label: string }> = {
  online: { dot: 'bg-emerald-400', label: 'online' },
  table: { dot: 'bg-gold-400', label: 'am Tisch' },
  game: { dot: 'bg-arcane-400', label: 'im Spiel' },
  offline: { dot: 'bg-ink-600', label: 'offline' },
}

/**
 * Freundesliste mit Status, Anfragen und "Freund hinzufügen".
 * {@code inviteTableId}: ich sitze an diesem Tisch -> Knopf "Einladen" je Freund ({@code seatedIds} sitzen schon dort).
 */
export function FriendsPanel({ inviteTableId, seatedIds = [], compact = false }: { inviteTableId?: string | null; seatedIds?: number[]; compact?: boolean }) {
  const friends = useSocial((s) => s.friends)
  const incoming = useSocial((s) => s.incoming)
  const outgoing = useSocial((s) => s.outgoing)
  const loaded = useSocial((s) => s.loaded)
  const request = useSocial((s) => s.request)
  const accept = useSocial((s) => s.accept)
  const remove = useSocial((s) => s.remove)
  const invite = useSocial((s) => s.invite)
  const [name, setName] = useState('')
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)
  const [invited, setInvited] = useState<Record<number, boolean>>({})
  const [confirmRemove, setConfirmRemove] = useState<number | null>(null)

  const show = (err: string | null, okText: string) => {
    setMsg(err ? { text: err, ok: false } : { text: okText, ok: true })
    window.setTimeout(() => setMsg(null), 3500)
  }

  const add = async () => {
    const n = name.trim()
    if (!n) return
    const err = await request({ name: n })
    if (!err) setName('')
    show(err, `Anfrage an ${n} gesendet`)
  }

  const doInvite = async (f: Friend) => {
    if (!inviteTableId) return
    const err = await invite(inviteTableId, f.id)
    if (!err) setInvited((m) => ({ ...m, [f.id]: true }))
    show(err, `${f.name} eingeladen`)
  }

  const online = friends.filter((f) => f.status !== 'offline').length

  return (
    <div className="glass flex flex-col overflow-hidden rounded-2xl">
      <div className="flex items-center justify-between border-b border-white/10 px-4 py-2.5">
        <div className="text-sm font-semibold uppercase tracking-wider text-ink-300">Freunde</div>
        <div className="text-xs text-ink-400">{friends.length ? `${online} / ${friends.length} online` : ''}</div>
      </div>

      {incoming.length > 0 && (
        <div className="border-b border-white/10 bg-gold-400/5 px-3 py-2">
          <div className="mb-1 text-[11px] font-semibold uppercase tracking-wider text-gold-300">Anfragen</div>
          {incoming.map((r) => (
            <div key={r.userId} className="flex items-center gap-2 py-1 text-sm">
              <span className="min-w-0 flex-1 truncate font-semibold text-ink-100">{r.name}</span>
              <button className="btn-primary !px-2 !py-0.5 !text-xs" onClick={async () => show(await accept(r.userId), `${r.name} ist jetzt dein Freund`)}>
                Annehmen
              </button>
              <button className="btn-ghost !px-2 !py-0.5 !text-xs" onClick={async () => show(await remove(r.userId), 'Abgelehnt')}>
                Ablehnen
              </button>
            </div>
          ))}
        </div>
      )}

      <div className={`${compact ? 'max-h-[220px]' : 'max-h-[300px]'} min-h-[60px] overflow-y-auto px-2 py-1.5 scrollbar-thin`}>
        {loaded && friends.length === 0 && <div className="px-2 py-2 text-xs italic text-ink-400">Noch keine Freunde. Füge jemanden per Name hinzu oder klick im Lobby-Chat auf einen Namen.</div>}
        {friends.map((f) => {
          const st = STATUS[f.status]
          const seated = seatedIds.includes(f.id) || (!!inviteTableId && f.status === 'table' && f.tableId === inviteTableId)
          return (
            <div key={f.id} className="group flex items-center gap-2 rounded-lg px-2 py-1.5 hover:bg-white/5">
              <span className={`h-2 w-2 shrink-0 rounded-full ${st.dot}`} />
              <div className="min-w-0 flex-1">
                <div className={`truncate text-sm font-semibold ${f.status === 'offline' ? 'text-ink-400' : 'text-ink-100'}`}>{f.name}</div>
                <div className="truncate text-[11px] text-ink-400">
                  {seated ? 'an deinem Tisch' : f.status === 'table' && f.tableName ? `am Tisch „${f.tableName}“` : st.label}
                </div>
              </div>
              {inviteTableId && !seated && (
                <button className="btn-ghost !px-2 !py-0.5 !text-xs" disabled={invited[f.id]} onClick={() => doInvite(f)} title={f.status === 'offline' ? 'Kommt an, sobald er/sie online ist (10 min gültig)' : undefined}>
                  {invited[f.id] ? 'Eingeladen ✓' : 'Einladen'}
                </button>
              )}
              {confirmRemove === f.id ? (
                <span className="flex items-center gap-1 text-[11px]">
                  <button className="rounded px-1.5 py-0.5 text-blood-400 hover:bg-blood-500/20" onClick={async () => { setConfirmRemove(null); show(await remove(f.id), `${f.name} entfernt`) }}>
                    Entfernen
                  </button>
                  <button className="rounded px-1.5 py-0.5 text-ink-400 hover:bg-white/10" onClick={() => setConfirmRemove(null)}>
                    Nein
                  </button>
                </span>
              ) : (
                <button className="rounded px-1 text-xs text-ink-500 opacity-0 transition hover:text-blood-400 group-hover:opacity-100" title="Freund entfernen" onClick={() => setConfirmRemove(f.id)}>
                  ✕
                </button>
              )}
            </div>
          )
        })}
        {outgoing.map((r) => (
          <div key={r.userId} className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-ink-400">
            <span className="h-2 w-2 shrink-0 rounded-full border border-ink-500" />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm">{r.name}</div>
              <div className="text-[11px]">Anfrage gesendet</div>
            </div>
            <button className="rounded px-1.5 py-0.5 text-[11px] hover:bg-white/10 hover:text-ink-200" onClick={async () => show(await remove(r.userId), 'Anfrage zurückgezogen')}>
              zurückziehen
            </button>
          </div>
        ))}
      </div>

      <div className="border-t border-white/10 p-2">
        <div className="flex gap-1.5">
          <input
            className="min-w-0 flex-1 rounded-lg bg-ink-950/70 px-2.5 py-1.5 text-sm ring-1 ring-white/15 outline-none placeholder:text-ink-500 focus:ring-gold-400/60"
            placeholder="Freund hinzufügen (Name)"
            maxLength={24}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && add()}
          />
          <button className="btn-ghost !px-3 !py-1 !text-xs" disabled={!name.trim()} onClick={add}>
            +
          </button>
        </div>
        {msg && <div className={`mt-1.5 text-xs ${msg.ok ? 'text-emerald-300' : 'text-blood-400'}`}>{msg.text}</div>}
      </div>
    </div>
  )
}
