import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../store/auth'

interface Account {
  id: number
  name: string
  admin: boolean
  createdAt: number
  lastSeen: number | null
}

interface Created {
  id: number
  name: string
  code: string
}

function ago(ts: number | null): string {
  if (!ts) return 'noch nie'
  const d = Date.now() - ts
  const min = Math.floor(d / 60000)
  if (min < 1) return 'gerade eben'
  if (min < 60) return `vor ${min} min`
  const h = Math.floor(min / 60)
  if (h < 48) return `vor ${h} h`
  return `vor ${Math.floor(h / 24)} Tagen`
}

function inviteLink(code: string): string {
  return `${window.location.origin}${window.location.pathname}#invite=${code}`
}

export function AdminScreen() {
  const me = useAuth((s) => s.me)
  const [list, setList] = useState<Account[]>([])
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** zuletzt erzeugter Code (wird nur einmal angezeigt) */
  const [fresh, setFresh] = useState<Created | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null)
  const [copied, setCopied] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      setList(await api.get<Account[]>('/api/admin/invites'))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const create = () =>
    run(async () => {
      const c = await api.post<Created>('/api/admin/invites', { name: name.trim() })
      setFresh(c)
      setName('')
    })

  const rotate = (a: Account) =>
    run(async () => {
      const r = await api.post<{ id: number; code: string }>(`/api/admin/invites/${a.id}/rotate`)
      setFresh({ id: a.id, name: a.name, code: r.code })
    })

  const remove = (a: Account) =>
    run(async () => {
      await api.del(`/api/admin/invites/${a.id}`)
      setConfirmDelete(null)
      if (fresh?.id === a.id) setFresh(null)
    })

  const copy = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(key)
      window.setTimeout(() => setCopied(null), 1500)
    } catch {
      setError('Kopieren nicht möglich – bitte markieren und kopieren.')
    }
  }

  return (
    <div className="h-full overflow-y-auto p-10 scrollbar-thin">
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <div>
          <h1 className="font-display text-3xl font-bold text-gold-300">Einladungen</h1>
          <p className="mt-1 text-sm text-ink-300">
            Jeder Freund bekommt einen eigenen Code und damit eigene Decks, eigenen Helden und eigene Statistik. Codes werden nur einmal angezeigt.
          </p>
        </div>

        <div className="glass rounded-2xl p-5">
          <div className="text-sm font-semibold text-ink-200">Neue Einladung</div>
          <div className="mt-2 flex gap-2">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && name.trim() && create()}
              maxLength={24}
              placeholder="Name des Freundes"
              className="flex-1 rounded-xl border border-white/10 bg-ink-950/60 px-3 py-2 text-ink-100 outline-none focus:border-gold-400/60"
            />
            <button
              onClick={create}
              disabled={busy || !name.trim()}
              className="rounded-xl bg-gold-400/15 px-4 py-2 font-semibold text-gold-300 ring-1 ring-gold-400/40 transition hover:bg-gold-400/25 disabled:opacity-40"
            >
              Anlegen
            </button>
          </div>
        </div>

        {fresh && (
          <div className="rounded-2xl border border-gold-400/40 bg-gold-400/10 p-5">
            <div className="text-sm font-semibold text-gold-200">Code für {fresh.name} – jetzt weitergeben, er wird nicht noch einmal angezeigt</div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <code className="rounded-lg bg-ink-950/70 px-3 py-2 font-mono text-lg tracking-[0.15em] text-gold-200">{fresh.code}</code>
              <button onClick={() => copy(fresh.code, 'code')} className="rounded-lg bg-white/5 px-3 py-2 text-sm text-ink-100 hover:bg-white/10">
                {copied === 'code' ? 'Kopiert ✓' : 'Code kopieren'}
              </button>
              <button onClick={() => copy(inviteLink(fresh.code), 'link')} className="rounded-lg bg-white/5 px-3 py-2 text-sm text-ink-100 hover:bg-white/10">
                {copied === 'link' ? 'Kopiert ✓' : 'Link kopieren'}
              </button>
              <button onClick={() => setFresh(null)} className="ml-auto text-sm text-ink-400 hover:text-ink-200">
                Ausblenden
              </button>
            </div>
            <div className="mt-2 break-all font-mono text-xs text-ink-400">{inviteLink(fresh.code)}</div>
          </div>
        )}

        {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}

        <div className="glass overflow-hidden rounded-2xl">
          <table className="w-full text-sm">
            <thead className="bg-white/5 text-left text-xs uppercase tracking-wider text-ink-400">
              <tr>
                <th className="px-4 py-2">Name</th>
                <th className="px-4 py-2">Zuletzt gesehen</th>
                <th className="px-4 py-2 text-right">Aktionen</th>
              </tr>
            </thead>
            <tbody>
              {list.length === 0 && (
                <tr>
                  <td colSpan={3} className="px-4 py-6 text-center text-ink-400">
                    Noch keine Einladungen.
                  </td>
                </tr>
              )}
              {list.map((a) => (
                <tr key={a.id} className="border-t border-white/5">
                  <td className="px-4 py-2 text-ink-100">
                    {a.name}
                    {a.admin && <span className="ml-2 rounded bg-gold-400/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-gold-300">Admin</span>}
                    {a.id === me?.id && <span className="ml-2 text-xs text-ink-400">(du)</span>}
                  </td>
                  <td className="px-4 py-2 text-ink-300">{ago(a.lastSeen)}</td>
                  <td className="px-4 py-2 text-right">
                    {confirmDelete === a.id ? (
                      <span className="inline-flex items-center gap-2">
                        <span className="text-xs text-blood-300">Konto und Decks löschen?</span>
                        <button onClick={() => remove(a)} disabled={busy} className="rounded bg-blood-500/20 px-2 py-1 text-xs font-semibold text-blood-300 hover:bg-blood-500/30">
                          Ja, löschen
                        </button>
                        <button onClick={() => setConfirmDelete(null)} className="rounded bg-white/5 px-2 py-1 text-xs text-ink-200 hover:bg-white/10">
                          Abbrechen
                        </button>
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-2">
                        <button onClick={() => rotate(a)} disabled={busy} className="rounded bg-white/5 px-2 py-1 text-xs text-ink-200 hover:bg-white/10" title="Neuer Code, der alte wird sofort ungültig">
                          Neuer Code
                        </button>
                        {a.id !== me?.id && (
                          <button onClick={() => setConfirmDelete(a.id)} disabled={busy} className="rounded bg-white/5 px-2 py-1 text-xs text-ink-200 hover:bg-blood-500/20 hover:text-blood-300">
                            Entfernen
                          </button>
                        )}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
