import { useCallback, useEffect, useState } from 'react'
import { tablesApi, type Table } from '../api/tables'
import { useAuth } from '../store/auth'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'

const POLL_MS = 3000

function ago(ts: number): string {
  const min = Math.floor((Date.now() - ts) / 60000)
  if (min < 1) return 'gerade eben'
  if (min < 60) return `vor ${min} min`
  return `vor ${Math.floor(min / 60)} h`
}

/** Online: offene Tische, Tisch eröffnen, Solo-Spiel gegen Bots. */
export function LobbyScreen() {
  const go = useNav((s) => s.go)
  const me = useAuth((s) => s.me)
  const tableId = useTable((s) => s.tableId)
  const setTableId = useTable((s) => s.setTableId)
  const [tables, setTables] = useState<Table[] | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const list = await tablesApi.list()
      setTables(list)
      const mine = list.find((t) => t.mySeat !== null)
      setTableId(mine ? mine.id : null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [setTableId])

  useEffect(() => {
    load()
    const iv = window.setInterval(load, POLL_MS)
    return () => window.clearInterval(iv)
  }, [load])

  const run = async (fn: () => Promise<Table | void>) => {
    setBusy(true)
    setError(null)
    try {
      const t = await fn()
      if (t) {
        setTableId(t.id)
        go('table')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="h-full overflow-y-auto p-8 scrollbar-thin">
      <h1 className="font-display text-3xl font-bold tracking-wide text-gold-300">Lobby</h1>
      <p className="mt-1 text-ink-300">Mit Freunden an einem Tisch – oder schnell allein gegen drei Bots.</p>

      <div className="mt-8 grid max-w-5xl gap-6 lg:grid-cols-[1.2fr_1fr]">
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">Tische</h2>
          <div className="flex flex-col gap-2">
            {tables === null && <div className="text-ink-400">Lade …</div>}
            {tables?.length === 0 && <div className="rounded-2xl bg-ink-900/60 p-6 text-center text-ink-400 ring-1 ring-white/10">Noch kein Tisch offen – eröffne einen und schick den Link herum.</div>}
            {tables?.map((t) => {
              const seated = t.mySeat !== null
              const free = t.seats.filter((s) => s.kind === 'OPEN').length
              return (
                <div key={t.id} className={`flex items-center gap-4 rounded-2xl px-5 py-3 ring-1 ${seated ? 'bg-gold-400/10 ring-gold-400/40' : 'bg-ink-900/60 ring-white/10'}`}>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-display text-lg font-semibold text-ink-100">{t.name}</span>
                      {t.state === 'RUNNING' ? (
                        <span className="rounded bg-arcane-500/20 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-arcane-400">spielt</span>
                      ) : (
                        <span className="rounded bg-white/5 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-ink-300">offen</span>
                      )}
                    </div>
                    <div className="truncate text-xs text-ink-400">
                      Gastgeber {t.hostName} · {t.seats.filter((s) => s.kind === 'HUMAN').map((s) => s.name).join(', ')} · {free} frei · {ago(t.updatedAt)}
                    </div>
                  </div>
                  {seated ? (
                    <button className="btn-primary !px-3 !py-1.5 !text-xs" onClick={() => go('table')}>
                      Zum Tisch
                    </button>
                  ) : t.state === 'LOBBY' && free > 0 ? (
                    <button className="btn-ghost !px-3 !py-1.5 !text-xs" disabled={busy} onClick={() => run(() => tablesApi.join(t.id))}>
                      Beitreten
                    </button>
                  ) : (
                    <span className="text-xs text-ink-500">{t.state === 'RUNNING' ? 'läuft' : 'voll'}</span>
                  )}
                </div>
              )
            })}
          </div>
          {error && <div className="mt-3 rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}
        </section>

        <section className="flex flex-col gap-6">
          <div className="glass rounded-2xl p-5">
            <div className="text-sm font-semibold text-ink-200">Tisch eröffnen</div>
            <div className="mt-1 text-xs text-ink-400">Du bist Gastgeber: Freunde treten über die Lobby oder deinen Link bei, freie Plätze füllst du mit Bots.</div>
            <div className="mt-3 flex gap-2">
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && !busy && run(() => tablesApi.create(name.trim() || undefined))}
                maxLength={40}
                placeholder={me ? `Tisch von ${me.name}` : 'Name des Tisches'}
                className="flex-1 rounded-xl border border-white/10 bg-ink-950/60 px-3 py-2 text-ink-100 outline-none focus:border-gold-400/60"
              />
              <button className="btn-primary !px-4 !py-2 !text-sm" disabled={busy || !!tableId} onClick={() => run(() => tablesApi.create(name.trim() || undefined))}>
                Eröffnen
              </button>
            </div>
            {tableId && <div className="mt-2 text-xs text-ink-400">Du sitzt schon an einem Tisch.</div>}
          </div>
          <div className="glass rounded-2xl p-5">
            <div className="text-sm font-semibold text-ink-200">Allein üben</div>
            <div className="mt-1 text-xs text-ink-400">Dein Deck gegen drei Bots, sofort los.</div>
            <button className="btn-ghost mt-3 !text-sm" onClick={() => go('solo')}>
              Schnellspiel gegen Bots ›
            </button>
          </div>
        </section>
      </div>
    </div>
  )
}
