import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../store/auth'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { FriendsPanel } from '../social/FriendsPanel'
import { LobbyChat } from '../social/LobbyChat'

export interface Profile {
  name: string
  level: number
  title: string
  xpTotal: number
  xpIntoLevel: number
  xpForNext: number
  games: number
  wins: number
  streak: number
  nextTitle?: { level: number; title: string }
}

export function HomeScreen() {
  const go = useNav((s) => s.go)
  const lastSetup = useNav((s) => s.lastSetup)
  const connect = useGame((s) => s.connect)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [busy, setBusy] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const [editName, setEditName] = useState<string | null>(null)
  const mode = useAuth((s) => s.mode)
  const me = useAuth((s) => s.me)
  const secureDismissed = useAuth((s) => s.secureDismissed)
  const dismissSecure = useAuth((s) => s.dismissSecure)
  const showSecure = mode === 'server' && !!me && !me.hasPassword && !secureDismissed
  const online = mode === 'server'
  const tableId = useTable((s) => s.tableId)

  useEffect(() => {
    api.get<Profile>('/api/profile').then(setProfile).catch(() => setProfile(null))
  }, [])

  const quick = async () => {
    if (!lastSetup) return
    setBusy(true)
    setStartError(null)
    try {
      const res = await api.post<{ gameId: string }>('/api/games', lastSetup)
      connect(res.gameId)
      go('game')
    } catch (e) {
      // z.B. 409 "Gerade spielt ..." (online nur ein Spiel gleichzeitig) oder Deck geloescht
      setStartError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const saveName = async () => {
    if (editName === null) return
    const p = await api.put<Profile>('/api/profile', { name: editName.trim() || 'Planeswalker' })
    setProfile(p)
    setEditName(null)
  }

  const pct = profile ? Math.min(100, (profile.xpIntoLevel / Math.max(1, profile.xpForNext)) * 100) : 0
  return (
    <div className="relative h-full overflow-y-auto p-10 scrollbar-thin">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,rgba(245,184,74,0.10),transparent_60%)]" />
      <div className={`relative mx-auto ${online ? 'grid max-w-6xl items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]' : 'max-w-4xl'}`}>
        <div className="min-w-0">
          {showSecure && (
            <div className="mb-4 flex flex-wrap items-center gap-4 rounded-2xl border border-gold-400/30 bg-gold-400/10 px-5 py-4">
              <div className="text-2xl">🔐</div>
              <div className="min-w-0 flex-1 text-sm text-ink-200">
                <b className="text-gold-200">Du spielst als Gast.</b> Sichere dein Konto mit E-Mail und Passwort, dann kommst du auch ohne den Einladungscode wieder rein (z.&nbsp;B. auf einem anderen Gerät).
              </div>
              <div className="flex items-center gap-2">
                <button className="btn-primary !py-1.5 !text-xs" onClick={() => go('account')}>
                  Konto sichern
                </button>
                <button className="btn-ghost !py-1.5 !text-xs" onClick={dismissSecure}>
                  Als Gast weiterspielen
                </button>
              </div>
            </div>
          )}
          <div className="glass relative overflow-hidden rounded-3xl p-8">
            <div className="absolute -right-16 -top-16 h-64 w-64 rounded-full bg-gold-400/10 blur-3xl" />
            <div className="relative flex items-center gap-6">
              <div className="relative flex h-28 w-28 shrink-0 items-center justify-center rounded-full bg-linear-to-br from-gold-300 to-gold-500 shadow-xl shadow-gold-500/20">
                <div className="flex h-[104px] w-[104px] flex-col items-center justify-center rounded-full bg-ink-900">
                  <div className="text-[10px] font-semibold uppercase tracking-widest text-ink-400">Level</div>
                  <div className="font-display text-4xl font-bold text-gold-300">{profile?.level ?? 1}</div>
                </div>
              </div>
              <div className="min-w-0 flex-1">
                {editName !== null ? (
                  <div className="flex items-center gap-2">
                    <input autoFocus className="rounded-lg bg-ink-950/70 px-3 py-1.5 font-display text-2xl ring-1 ring-gold-400/50 outline-none" value={editName} maxLength={24} onChange={(e) => setEditName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && saveName()} />
                    <button className="btn-primary !py-1.5" onClick={saveName}>OK</button>
                  </div>
                ) : (
                  <button className="group flex items-baseline gap-2 text-left" onClick={() => setEditName(profile?.name ?? '')}>
                    <span className="font-display text-3xl font-bold tracking-wide text-ink-100">{profile?.name ?? 'Planeswalker'}</span>
                    <span className="text-xs text-ink-500 opacity-0 transition group-hover:opacity-100">✎ umbenennen</span>
                  </button>
                )}
                <div className="mt-0.5 font-display text-sm tracking-widest text-gold-300/90">{profile?.title ?? 'Novize'}</div>
                <div className="mt-4 h-3 overflow-hidden rounded-full bg-ink-800 ring-1 ring-white/5">
                  <div className="h-full rounded-full bg-linear-to-r from-gold-500 via-gold-400 to-gold-300 transition-all duration-700" style={{ width: `${pct}%` }} />
                </div>
                <div className="mt-1.5 flex justify-between text-xs text-ink-400">
                  <span>
                    {profile?.xpIntoLevel ?? 0} / {profile?.xpForNext ?? 150} XP bis Level {(profile?.level ?? 1) + 1}
                  </span>
                  {profile?.nextTitle && (
                    <span>
                      Nächster Titel: <span className="text-ink-200">{profile.nextTitle.title}</span> (Lv {profile.nextTitle.level})
                    </span>
                  )}
                </div>
              </div>
            </div>
            <div className="relative mt-6 grid grid-cols-3 gap-3">
              <Stat label="Spiele" value={profile?.games ?? 0} />
              <Stat label="Siege" value={profile?.wins ?? 0} />
              <Stat label="Siegesserie" value={profile?.streak ?? 0} />
            </div>
          </div>

          <div className="mt-6 grid grid-cols-2 gap-4">
            <button className="group glass relative overflow-hidden rounded-3xl p-6 text-left ring-1 ring-gold-400/30 transition hover:ring-gold-400/70" onClick={() => go('play')}>
              <div className="absolute -bottom-10 -right-10 h-40 w-40 rounded-full bg-gold-400/10 blur-2xl transition group-hover:bg-gold-400/20" />
              <div className="text-3xl">⚔️</div>
              <div className="mt-2 font-display text-xl font-bold text-gold-300">Neues Spiel</div>
              <div className="text-sm text-ink-300">Deck und Gegner wählen</div>
            </button>
            <button className="group glass relative overflow-hidden rounded-3xl p-6 text-left ring-1 ring-arcane-400/30 transition hover:ring-arcane-400/70 disabled:opacity-40" disabled={!lastSetup || busy} onClick={quick}>
              <div className="absolute -bottom-10 -right-10 h-40 w-40 rounded-full bg-arcane-400/10 blur-2xl transition group-hover:bg-arcane-400/20" />
              <div className="text-3xl">⚡</div>
              <div className="mt-2 font-display text-xl font-bold text-arcane-400">{busy ? 'Starte …' : 'Schnellstart'}</div>
              <div className="text-sm text-ink-300">{lastSetup ? 'Letzte Konfiguration erneut spielen' : 'Erst ein Spiel einrichten'}</div>
            </button>
          </div>
          {startError && <div className="mt-3 rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{startError}</div>}
        </div>
        {online && (
          <aside className="flex min-w-0 flex-col gap-4">
            <LobbyChat />
            <FriendsPanel inviteTableId={tableId} compact />
          </aside>
        )}
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-2xl bg-ink-950/40 px-4 py-3 ring-1 ring-white/5">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-ink-400">{label}</div>
      <div className="font-display text-2xl font-bold text-ink-100">{value}</div>
    </div>
  )
}
