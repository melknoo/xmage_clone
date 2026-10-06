import { useEffect, useRef, useState } from 'react'
import type { LobbyMsg } from '../api/social'
import { useAuth } from '../store/auth'
import { useSocial } from '../store/social'

const NAME_COLORS = ['#ffd98a', '#38e1c6', '#5cb8ff', '#f59ec8', '#b9a3ff', '#9be08a', '#ffad73']

function colorOf(userId: number): string {
  return NAME_COLORS[userId % NAME_COLORS.length]
}

/** Globaler Chat aller Angemeldeten (Server-Modus). Standardmäßig drin, "Verlassen" macht unsichtbar. */
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
  const request = useSocial((s) => s.request)
  const markRead = useSocial((s) => s.markRead)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showMembers, setShowMembers] = useState(false)
  const [pick, setPick] = useState<{ userId: number; name: string } | null>(null)
  const [stick, setStick] = useState(true)
  const ref = useRef<HTMLDivElement>(null)

  // sichtbar = gelesen
  useEffect(() => {
    markRead()
  }, [msgs, markRead])

  useEffect(() => {
    if (stick && ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [msgs, stick, chatIn])

  const flash = (e: string | null) => {
    setError(e)
    if (e) window.setTimeout(() => setError(null), 4000)
  }

  const submit = async () => {
    const t = text.trim()
    if (!t) return
    setText('')
    const err = await say(t)
    if (err) {
      setText(t)
      flash(err)
    }
  }

  const relation = (userId: number): 'me' | 'friend' | 'pending' | 'none' => {
    if (userId === me?.id) return 'me'
    if (friends.some((f) => f.id === userId)) return 'friend'
    if (outgoing.some((r) => r.userId === userId) || incoming.some((r) => r.userId === userId)) return 'pending'
    return 'none'
  }

  if (loaded && !chatIn) {
    return (
      <div className="glass flex items-center gap-3 rounded-2xl px-4 py-3">
        <div className="text-xl">💬</div>
        <div className="min-w-0 flex-1 text-sm text-ink-300">
          Du bist nicht im <b className="text-ink-100">Lobby-Chat</b>.
        </div>
        <button className="btn-ghost !py-1 !text-xs" onClick={async () => flash(await setIn(true))}>
          Beitreten
        </button>
      </div>
    )
  }

  return (
    <div className="glass relative flex h-[420px] flex-col overflow-hidden rounded-2xl">
      <div className="flex items-center gap-2 border-b border-white/10 px-4 py-2.5">
        <div className="text-sm font-semibold uppercase tracking-wider text-ink-300">Lobby-Chat</div>
        <button className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[11px] font-semibold text-emerald-300 hover:bg-emerald-500/25" onClick={() => setShowMembers((v) => !v)} title="Wer ist im Chat?">
          {members.length} im Chat
        </button>
        <button className="ml-auto rounded px-1.5 py-0.5 text-[11px] text-ink-400 hover:bg-white/10 hover:text-ink-200" onClick={async () => flash(await setIn(false))} title="Chat verlassen – du bist dann für andere unsichtbar">
          Verlassen
        </button>
      </div>

      {showMembers && (
        <div className="flex flex-wrap gap-1 border-b border-white/10 bg-ink-950/40 px-3 py-2">
          {members.map((m) => (
            <button key={m.id} className="rounded-full bg-white/5 px-2 py-0.5 text-[11px] hover:bg-white/10" style={{ color: colorOf(m.id) }} onClick={() => setPick({ userId: m.id, name: m.name })}>
              {m.id === me?.id ? `${m.name} (du)` : m.name}
            </button>
          ))}
        </div>
      )}

      <div
        ref={ref}
        className="min-h-0 flex-1 overflow-y-auto px-3 py-2 text-[13px] leading-snug scrollbar-thin"
        onScroll={(e) => {
          const el = e.currentTarget
          setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
        }}
      >
        {msgs.length === 0 && <div className="py-2 text-xs italic text-ink-400">{loaded ? 'Noch keine Nachrichten. Sag hallo!' : 'Lade …'}</div>}
        {msgs.map((m: LobbyMsg) => (
          <div key={m.seq} className="py-0.5 [overflow-wrap:anywhere]">
            <span className="mr-1.5 tabular-nums text-[10px] text-ink-500">{new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
            <button className="font-semibold hover:underline" style={{ color: colorOf(m.userId) }} onClick={() => setPick({ userId: m.userId, name: m.name })}>
              {m.userId === me?.id ? 'Du' : m.name}
            </button>
            <span className="text-ink-400">: </span>
            <span className="text-ink-100">{m.text}</span>
          </div>
        ))}
      </div>

      {pick && (
        <div className="absolute inset-x-3 top-12 z-10 rounded-xl bg-ink-900/95 p-3 shadow-xl ring-1 ring-white/15">
          <div className="flex items-center gap-2">
            <span className="font-semibold" style={{ color: colorOf(pick.userId) }}>
              {pick.name}
            </span>
            <button className="ml-auto text-xs text-ink-400 hover:text-ink-100" onClick={() => setPick(null)}>
              ✕
            </button>
          </div>
          <div className="mt-2 text-xs text-ink-300">
            {(() => {
              const r = relation(pick.userId)
              if (r === 'me') return 'Das bist du.'
              if (r === 'friend') return 'Ihr seid befreundet.'
              if (r === 'pending') return 'Freundschaftsanfrage läuft.'
              return (
                <button
                  className="btn-primary !py-1 !text-xs"
                  onClick={async () => {
                    const err = await request({ userId: pick.userId })
                    setPick(null)
                    flash(err)
                  }}
                >
                  Als Freund hinzufügen
                </button>
              )
            })()}
          </div>
        </div>
      )}

      <div className="shrink-0 border-t border-white/10 p-2">
        {away && <div className="mb-1.5 text-[11px] text-amber-300">Abwesend – Chat pausiert. Bewege die Maus, um weiterzulesen.</div>}
        {error && <div className="mb-1.5 text-[11px] text-blood-400">{error}</div>}
        <input
          className="w-full rounded-lg bg-ink-950/70 px-3 py-1.5 text-sm ring-1 ring-white/15 outline-none placeholder:text-ink-500 focus:ring-gold-400/60"
          placeholder="Nachricht an alle … (Enter)"
          maxLength={300}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              void submit()
            }
          }}
        />
      </div>
    </div>
  )
}
