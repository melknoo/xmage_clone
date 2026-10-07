import { useCallback, useEffect, useState } from 'react'
import { adminApi, type Account, type CreatedAccount as Created } from '../../api/admin'
import { Button, StatusDot, TextField } from '../../components/ui'
import { pushToast } from '../../store/ui'
import { copyInviteLink, created, errText, ICON_CLASS, iconStyle, inviteLink, sessionCodes } from './shared'

/** Spalten: Name · Status · Erstellt · Aktionen */
const COLUMNS = 'minmax(140px,1fr) minmax(160px,1fr) minmax(120px,.8fr) 130px'

/**
 * Einladungen: Code erzeugen (nur einmal sichtbar), darunter die noch nicht benutzten Einladungen mit Kopieren
 * (nur Codes dieser Sitzung), Rotieren und Loeschen. Angemeldete Konten stehen unter "Nutzer".
 */
export function InvitesTab({ onShowUsers }: { onShowUsers: () => void }) {
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

  const open = list.filter((a) => a.lastSeen === null && !a.admin)
  const used = list.length - open.length

  return (
    <div className="flex flex-col gap-4 board:gap-[26px]">
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
          <Button variant="secondary" icon="copy" onClick={() => void copyInviteLink(fresh.code)} testId="admin-copy-fresh">
            Link kopieren
          </Button>
        </div>
      )}

      <div className="flex max-w-[1100px] flex-col" role="table" aria-label="Offene Einladungen">
        <div role="row" className="tbl-head" style={{ gridTemplateColumns: COLUMNS }}>
          <span>Offene Einladung</span>
          <span>Status</span>
          <span>Erstellt</span>
          <span />
        </div>
        {open.length === 0 && <div className="px-3 py-4 text-[13px] text-fg-3">Keine offenen Einladungen – alle Codes wurden benutzt.</div>}
        {open.map((a) => {
          const code = sessionCodes.get(a.id)
          return (
            <div key={a.id} role="row" className="tbl-row" style={{ gridTemplateColumns: COLUMNS }} data-testid="admin-row">
              <span className="truncate text-[14px] font-semibold text-fg-1">{a.name}</span>
              <span className="flex min-w-0 items-center gap-[7px] text-[13px] text-fg-3">
                <StatusDot status="unused" className="!h-[7px] !w-[7px]" />
                <span className="truncate">Code noch nicht benutzt</span>
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
                      className={ICON_CLASS}
                      style={iconStyle}
                      disabled={!code}
                      title={code ? 'Link kopieren' : 'Code nur direkt nach dem Erzeugen sichtbar – Code rotieren erzeugt einen neuen'}
                      aria-label="Link kopieren"
                      onClick={() => code && void copyInviteLink(code)}
                    />
                    <Button variant="icon" icon="rotate" className={ICON_CLASS} style={iconStyle} disabled={busy} title="Code rotieren" aria-label="Code rotieren" onClick={() => void rotate(a)} testId="admin-rotate" />
                    <Button variant="icon" icon="delete" className={ICON_CLASS} style={iconStyle} disabled={busy} title="Löschen" aria-label="Löschen" onClick={() => setConfirmDelete(a.id)} testId="admin-delete" />
                  </>
                )}
              </span>
            </div>
          )
        })}
      </div>
      {used > 0 && (
        <button type="button" className="self-start text-[13px] text-fg-3 underline-offset-2 hover:text-fg-1 hover:underline" onClick={onShowUsers}>
          {used} {used === 1 ? 'Konto ist' : 'Konten sind'} angemeldet – unter „Nutzer“
        </button>
      )}
    </div>
  )
}
