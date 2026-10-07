import { useMemo, useState } from 'react'
import type { LobbyMember } from '../api/social'
import { Button, Popover } from '../components/ui'
import { Icon } from '../lib/icons'
import { useAuth } from '../store/auth'
import { useConn } from '../store/conn'
import { useSocial } from '../store/social'
import { pushToast } from '../store/ui'
import { ChatInput } from './ChatInput'
import { ChatMessages, type ChatMessage } from './ChatMessages'

type Relation = 'friend' | 'incoming' | 'outgoing' | 'none'

const REL_SUB: Record<Relation, string> = {
  friend: 'Dein Freund',
  incoming: 'Hat dir eine Anfrage geschickt',
  outgoing: 'Anfrage gesendet',
  none: 'Im Lobby-Chat',
}
const REL_ACTION: Record<Relation, string> = {
  friend: 'Schon befreundet',
  incoming: 'Anfrage annehmen',
  outgoing: 'Anfrage gesendet',
  none: 'Als Freund hinzufügen',
}

/**
 * Lobby-Chat (Tab der Seitenleiste): Kopf "N im Chat" + Verlassen, Mitglieder-Chips mit Menue (Freund hinzufuegen/annehmen),
 * Nachrichten mit Systemzeilen, Eingabe. Wer den Chat verlassen hat, sieht "Du bist unsichtbar" + Beitreten.
 */
export function LobbyChat() {
  const me = useAuth((s) => s.me)
  const chatIn = useSocial((s) => s.chatIn)
  const loaded = useSocial((s) => s.loaded)
  const msgs = useSocial((s) => s.msgs)
  const members = useSocial((s) => s.members)
  const friends = useSocial((s) => s.friends)
  const outgoing = useSocial((s) => s.outgoing)
  const incoming = useSocial((s) => s.incoming)
  const away = useSocial((s) => s.away)
  const say = useSocial((s) => s.say)
  const setIn = useSocial((s) => s.setIn)
  const offline = useConn((s) => s.offline)
  const [error, setError] = useState<string | null>(null)
  const [menu, setMenu] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)

  const flash = (e: string | null) => {
    setError(e)
    if (e) window.setTimeout(() => setError((cur) => (cur === e ? null : cur)), 4000)
  }

  const toggleIn = async (v: boolean) => {
    if (busy) return
    setBusy(true)
    setMenu(null)
    const err = await setIn(v)
    setBusy(false)
    if (err) pushToast({ kind: 'error', text: err })
  }

  const chatMsgs = useMemo<ChatMessage[]>(
    () => msgs.map((m) => ({ id: m.seq, ts: m.ts, authorId: m.userId, name: m.name, text: m.text, sys: m.sys })),
    [msgs],
  )

  // eigener Chip zuerst
  const sorted = useMemo(() => {
    const own = members.filter((m) => m.id === me?.id)
    return [...own, ...members.filter((m) => m.id !== me?.id)]
  }, [members, me?.id])

  const relation = (userId: number): Relation => {
    if (friends.some((f) => f.id === userId)) return 'friend'
    if (incoming.some((r) => r.userId === userId)) return 'incoming'
    if (outgoing.some((r) => r.userId === userId)) return 'outgoing'
    return 'none'
  }

  if (loaded && !chatIn) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3.5 p-6 text-center" data-testid="chat-hidden">
        <Icon name="invisible" size={34} className="text-fg-4" />
        <span className="font-display text-[22px] font-semibold uppercase leading-none tracking-[.04em] text-fg-1">Du bist unsichtbar</span>
        <span className="text-[13.5px] leading-[1.5] text-fg-3">
          Du stehst nicht in der Mitgliederliste und bekommst keine Nachrichten. Gilt für dein Konto, auch nach dem nächsten Anmelden.
        </span>
        <Button
          variant="primary"
          icon="toTable"
          testId="chat-join"
          className="mt-1"
          style={{ height: 42, padding: '0 16px', fontSize: 17 }}
          disabled={busy}
          onClick={() => void toggleIn(true)}
        >
          Beitreten
        </Button>
      </div>
    )
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid="lobby-chat">
      <div className="flex items-center gap-2.5 border-b border-line-1 px-[18px] py-2.5">
        <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.1em] text-fg-3">{members.length} im Chat</span>
        <span className="flex-1" />
        <Button
          variant="ghost"
          icon="logout"
          testId="chat-leave"
          title="Chat verlassen – du bist dann für andere unsichtbar"
          style={{ height: 28, padding: '0 8px', fontSize: 13, gap: 6 }}
          disabled={busy}
          onClick={() => void toggleIn(false)}
        >
          Verlassen
        </Button>
      </div>

      <div className="flex flex-wrap gap-1.5 border-b border-line-1 px-[18px] py-2.5">
        {sorted.map((m) => (
          <MemberChip
            key={m.id}
            m={m}
            own={m.id === me?.id}
            friend={friends.some((f) => f.id === m.id)}
            relation={relation(m.id)}
            open={menu === m.id}
            onToggle={() => setMenu((cur) => (cur === m.id ? null : m.id))}
            onClose={() => setMenu(null)}
          />
        ))}
        {loaded && sorted.length === 0 && <span className="py-[5px] text-[12.5px] text-fg-4">Gerade ist niemand sonst da.</span>}
      </div>

      <ChatMessages
        className="flex-1 px-[18px] py-3"
        msgs={chatMsgs}
        meId={me?.id}
        head={<span className="self-center text-center text-[11.5px] leading-[1.4] text-fg-4">Verlauf: letzte 100 Nachrichten · wird bei Server-Neustart geleert</span>}
        empty={<span className="self-center text-[13px] text-fg-4">{loaded ? 'Noch keine Nachrichten. Sag hallo!' : 'Lade …'}</span>}
        onName={(cm) => {
          const id = Number(cm.authorId)
          if (members.some((x) => x.id === id)) setMenu(id)
        }}
      />

      <div className="flex flex-none flex-col gap-2 border-t border-line-1 px-[18px] pb-4 pt-3">
        {away && <span className="text-[12px] leading-[1.4] text-fg-3">Abwesend – Chat pausiert. Bewege die Maus, um weiterzulesen.</span>}
        {error && (
          <span className="field-error" role="alert">
            <Icon name="error" size={14} />
            {error}
          </span>
        )}
        <ChatInput
          placeholder={offline ? 'Keine Verbindung' : 'Nachricht an alle in der Lobby'}
          disabled={offline}
          onSend={async (t) => {
            const err = await say(t)
            flash(err)
            return err
          }}
        />
      </div>
    </div>
  )
}

function MemberChip({
  m,
  own,
  friend,
  relation,
  open,
  onToggle,
  onClose,
}: {
  m: LobbyMember
  own: boolean
  friend: boolean
  relation: Relation
  open: boolean
  onToggle: () => void
  onClose: () => void
}) {
  const request = useSocial((s) => s.request)
  const accept = useSocial((s) => s.accept)
  const [busy, setBusy] = useState(false)
  const inert = relation === 'friend' || relation === 'outgoing'

  const act = async () => {
    if (inert) {
      onClose()
      return
    }
    if (busy) return
    setBusy(true)
    let err: string | null
    if (relation === 'incoming') err = await accept(m.id)
    else err = (await request({ userId: m.id })).error
    setBusy(false)
    onClose()
    if (err) pushToast({ kind: 'error', text: err })
  }

  return (
    <div className="relative">
      <button
        type="button"
        className={`flex items-center gap-1.5 rounded-xs px-2 py-[5px] text-[12.5px] font-semibold leading-[1.2] transition-colors duration-1 ${own ? 'cursor-default text-ember' : 'text-fg-1 hover:bg-line-3'} ${open ? 'bg-line-3' : 'bg-bg-4'}`}
        data-testid="chat-member"
        data-name={m.name}
        data-own={own ? 'true' : undefined}
        aria-expanded={own ? undefined : open}
        onClick={own ? undefined : onToggle}
      >
        <span className="h-1.5 w-1.5 flex-none bg-chosen" style={{ borderRadius: '50%' }} aria-hidden />
        {own ? `${m.name} (du)` : m.name}
        {friend && !own && <Icon name="friends" size={11} className="text-fg-3" title="Freund" />}
      </button>
      {!own && (
        <Popover
          open={open}
          onClose={onClose}
          width={220}
          testId="member-menu"
          variant="menu"
          offset={6}
          style={{ zIndex: 12 }}
        >
          <div className="mb-1 flex flex-col gap-[3px] border-b border-line-3 px-2.5 py-2">
            <span className="truncate text-[14px] font-semibold leading-[1.25] text-fg-1">{m.name}</span>
            <span className="text-[12px] leading-[1.3] text-fg-3">{REL_SUB[relation]}</span>
          </div>
          <button
            type="button"
            className={`flex w-full items-center gap-2 rounded-xs px-2.5 py-[9px] text-left text-[13.5px] leading-[1.2] transition-colors duration-1 ${inert ? 'cursor-default text-fg-3' : 'text-fg-1 hover:bg-line-3'}`}
            data-testid="member-action"
            aria-disabled={inert || undefined}
            disabled={busy}
            onClick={() => void act()}
          >
            <Icon name={inert ? 'friend' : 'addFriend'} size={16} />
            {REL_ACTION[relation]}
          </button>
        </Popover>
      )}
    </div>
  )
}
