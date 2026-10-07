import { useCallback, useEffect, useState } from 'react'
import { adminApi, type Account, type CreatedAccount as Created } from '../api/admin'
import { Button, StatusDot, TextField } from '../components/ui'
import { useAuth } from '../store/auth'
import { pushToast } from '../store/ui'

/** Spalten: Name · Status · Erstellt · Aktionen */
const COLUMNS = 'minmax(140px,1fr) minmax(160px,1fr) minmax(120px,.8fr) 130px'
const MONTHS = ['Jan.', 'Feb.', 'März', 'Apr.', 'Mai', 'Juni', 'Juli', 'Aug.', 'Sep.', 'Okt.', 'Nov.', 'Dez.']

/**
 * Klartext-Codes dieser Sitzung (erzeugt oder rotiert). Der Server speichert nur den Hash, deshalb laesst sich
 * spaeter nur kopieren, was in dieser Sitzung erzeugt wurde. Lebt bis zum Neuladen.
 */
const sessionCodes = new Map<number, string>()

function inviteLink(code: string): string {
  return `${window.location.origin}${window.location.pathname}#invite=${code}`
}

/** "heute" | "gestern" | "12. Sep." (Vorjahr mit Jahreszahl) */
function created(ts: number, now = Date.now()): string {
  const d = new Date(ts)
  const n = new Date(now)
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((start(n) - start(d)) / 86_400_000)
  if (days <= 0) return 'heute'
  if (days === 1) return 'gestern'
  return `${d.getDate()}. ${MONTHS[d.getMonth()]}${d.getFullYear() !== n.getFullYear() ? ` ${d.getFullYear()}` : ''}`
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** Einladungen (Admin): Code erzeugen, Liste mit Status, Kopieren (nur Codes dieser Sitzung), Rotieren, Loeschen. */
export function AdminScreen() {
  const me = useAuth((s) => s.me)
  const [list, setList] = useState<Account[]>([])
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  /** zuletzt erzeugter Code (wird nur einmal angezeigt) */
  const [fresh, setFresh] = useState<Created | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null)

  const reload = useCallback(async () => {
    try {
      setList(await adminApi.list())
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
      await reload()
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setBusy(false)
    }
  }

  const create = () =>
    run(async () => {
      const c = await adminApi.create(name.trim())
      sessionCodes.set(c.id, c.code)
      setFresh(c)
      setName('')
    })

  const rotate = (a: Account) =>
    run(async () => {
      const r = await adminApi.rotate(a.id)
      sessionCodes.set(a.id, r.code)
      setFresh({ id: a.id, name: a.name, code: r.code })
      pushToast({ kind: 'success', text: `Neuer Code für ${a.name} – der alte gilt nicht mehr` })
    })

  const remove = (a: Account) =>
    run(async () => {
      await adminApi.remove(a.id)
      sessionCodes.delete(a.id)
      setConfirmDelete(null)
      if (fresh?.id === a.id) setFresh(null)
      pushToast({ kind: 'success', text: `${a.name} gelöscht` })
    })

  const copyLink = async (code: string) => {
    try {
      await navigator.clipboard.writeText(inviteLink(code))
      pushToast({ kind: 'success', text: 'Link kopiert' })
    } catch {
      pushToast({ kind: 'error', text: 'Kopieren nicht möglich – bitte markieren und kopieren.' })
    }
  }

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto px-8 py-[26px] scrollbar-thin board:gap-[26px] board:px-14 board:py-11">
      <div className="flex items-baseline gap-4">
        <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Einladungen</h1>
        <span className="text-[14px] text-fg-3">Nur für dich als Admin sichtbar</span>
      </div>

      <form
        className="flex max-w-[720px] items-end gap-2.5"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim() && !busy) void create()
        }}
      >
        <TextField className="flex-1" fieldHeight={42} label="Name des Freundes" value={name} onChange={(e) => setName(e.target.value)} maxLength={24} placeholder="z. B. Jonas" data-testid="admin-name" />
        <Button type="submit" variant="primary" icon="code" disabled={busy || !name.trim()} testId="admin-create-code" style={{ height: 40, padding: '0 16px', fontSize: 17 }}>
          Code erzeugen
        </Button>
      </form>

      {fresh && (
        <div className="flex max-w-[980px] items-center gap-[18px] rounded-md bg-bg-3 px-[18px] py-4" style={{ boxShadow: 'inset 0 0 0 1px var(--color-target)' }} data-testid="admin-fresh-code">
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <span className="label" style={{ color: 'var(--color-target)' }}>
              Neuer Code für {fresh.name} · nur jetzt sichtbar
            </span>
            <div className="flex min-w-0 flex-wrap items-baseline gap-4">
              <span className="font-mono text-[24px] font-medium leading-none tracking-[.12em] text-fg-1 select-all">{fresh.code}</span>
              <span className="min-w-0 truncate font-mono text-[12.5px] font-medium text-fg-3 select-all">{inviteLink(fresh.code).replace(/^https?:\/\//, '')}</span>
            </div>
          </div>
          <Button variant="secondary" icon="copy" onClick={() => void copyLink(fresh.code)} testId="admin-copy-fresh">
            Link kopieren
          </Button>
        </div>
      )}

      <div className="flex max-w-[1100px] flex-col" role="table" aria-label="Einladungen">
        <div role="row" className="tbl-head" style={{ gridTemplateColumns: COLUMNS }}>
          <span>Name</span>
          <span>Status</span>
          <span>Erstellt</span>
          <span />
        </div>
        {list.length === 0 && <div className="px-3 py-4 text-[13px] text-fg-3">Noch keine Einladungen.</div>}
        {list.map((a) => {
          const own = a.id === me?.id
          const used = a.lastSeen !== null
          const code = sessionCodes.get(a.id)
          return (
            <div key={a.id} role="row" className="tbl-row" style={{ gridTemplateColumns: COLUMNS }} data-testid="admin-row">
              <span className="flex min-w-0 items-baseline gap-2">
                <span className="truncate text-[14px] font-semibold text-fg-1">{a.name}</span>
                {own && <span className="text-[12px] text-fg-3">(du)</span>}
              </span>
              <span className={`flex min-w-0 items-center gap-[7px] text-[13px] ${used ? 'text-fg-2' : 'text-fg-3'}`}>
                <StatusDot status={used ? 'used' : 'unused'} className="!h-[7px] !w-[7px]" />
                <span className="truncate" title={a.hasPassword && a.email ? a.email : undefined}>
                  {used ? `Angemeldet · ${a.hasPassword ? 'Konto gesichert' : 'Gast'}` : 'Code noch nicht benutzt'}
                </span>
              </span>
              <span className="text-[13px] text-fg-3">{created(a.createdAt)}</span>
              <span className="flex justify-end gap-1">
                {confirmDelete === a.id ? (
                  <Button variant="dangerConfirm" size="xs" autoFocus disabled={busy} onBlur={() => setConfirmDelete(null)} onClick={() => void remove(a)} testId="admin-delete-confirm">
                    Wirklich löschen?
                  </Button>
                ) : (
                  <>
                    <Button
                      variant="icon"
                      icon="copy"
                      className={ICON_CLASS} style={iconStyle}
                      disabled={!code}
                      title={code ? 'Link kopieren' : 'Code nur direkt nach dem Erzeugen sichtbar – Code rotieren erzeugt einen neuen'}
                      aria-label="Link kopieren"
                      onClick={() => code && void copyLink(code)}
                    />
                    {!own && (
                      <>
                        <Button variant="icon" icon="rotate" className={ICON_CLASS} style={iconStyle} disabled={busy} title="Code rotieren" aria-label="Code rotieren" onClick={() => void rotate(a)} testId="admin-rotate" />
                        <Button variant="icon" icon="delete" className={ICON_CLASS} style={iconStyle} disabled={busy} title="Löschen" aria-label="Löschen" onClick={() => setConfirmDelete(a.id)} testId="admin-delete" />
                      </>
                    )}
                  </>
                )}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/** Icon-Knoepfe der Liste: Kontur line-3, fg-2 (Prototyp) */
const ICON_CLASS = '!text-fg-2 hover:!text-fg-1'
const iconStyle = { boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }
