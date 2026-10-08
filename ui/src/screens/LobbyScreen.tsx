import { useCallback, useEffect, useState, type CSSProperties, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { tablesApi, type Table, type TableHosting, type TableSeat } from '../api/tables'
import { PasswordInput } from '../components/PasswordInput'
import { Button, Checkbox, EmptyState, Overlay, Segmented, TextField } from '../components/ui'
import { Icon, type IconName } from '../lib/icons'
import { SocialSidebar } from '../social/SocialSidebar'
import { useMarkLobbyRead } from '../social/useMarkLobbyRead'
import { openLocalApp } from '../lib/localApp'
import { tempoLabel } from '../lib/tempo'
import { useAuth } from '../store/auth'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { pollPaused, useSocial } from '../store/social'
import { useTable } from '../store/table'
import { pushToast } from '../store/ui'

const POLL_MS = 3000

/** Spalten: Tisch · Plaetze · Tempo · Status · Aktion (Minima passen bei 1280 px neben den Lobby-Chat) */
const COLUMNS = 'minmax(150px,1.6fr) minmax(170px,1.4fr) 84px 110px 128px'

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 403 mit needPassword: der Tisch ist privat */
function needsPassword(e: unknown): boolean {
  return e instanceof ApiError && e.status === 403 && (e.data as { needPassword?: boolean } | null)?.needPassword === true
}

/** Sichtbare Online-Mitglieder aus dem Social-Snapshot (Feld von O1; undefined = unbekannt) */
function selectOnline(s: unknown): number | undefined {
  const v = (s as { online?: number }).online
  return typeof v === 'number' ? v : undefined
}

/**
 * Online: Lobby mit offenen und laufenden Tischen, Tisch eroeffnen (auf dem Server oder - mit angebundener
 * MageLite-App - auf dem eigenen Rechner, oeffentlich oder privat mit Passwort), allein ueben, zuschauen;
 * rechts Lobby-Chat/Freunde.
 */
export function LobbyScreen() {
  const go = useNav((s) => s.go)
  const me = useAuth((s) => s.me)
  const loadMe = useAuth((s) => s.load)
  const tableId = useTable((s) => s.tableId)
  const setTableId = useTable((s) => s.setTableId)
  const online = useSocial(selectOnline)
  const [tables, setTables] = useState<Table[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [create, setCreate] = useState(false)
  /** Passwort-Abfrage fuer diesen privaten Tisch; error = letzter Fehlversuch */
  const [ask, setAsk] = useState<{ table: Table; error: string | null } | null>(null)
  useMarkLobbyRead()

  const load = useCallback(async () => {
    const stamp = useTable.getState().stamp
    try {
      const list = await tablesApi.list()
      setTables(list)
      useTable.getState().observeList(list, stamp)
    } catch {
      // Netz/Neustart: die Verbindungsleiste zeigt es, weiter pollen
    }
  }, [])

  useEffect(() => {
    void load()
    const iv = window.setInterval(() => {
      if (!pollPaused()) void load()
    }, POLL_MS)
    return () => window.clearInterval(iv)
  }, [load])

  const run = async (fn: () => Promise<Table>) => {
    setBusy(true)
    try {
      const t = await fn()
      setTableId(t.id)
      go('table')
      return true
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
      return false
    } finally {
      setBusy(false)
    }
  }

  const openTable = () => {
    if (tableId) {
      go('table')
      return
    }
    // frischer Stand des Host-Links (die App kann sich inzwischen angebunden haben)
    void loadMe().catch(() => undefined)
    setCreate(true)
  }

  const join = async (t: Table, password?: string) => {
    setBusy(true)
    try {
      const r = await tablesApi.join(t.id, password)
      setAsk(null)
      setTableId(r.id)
      go('table')
    } catch (e) {
      if (needsPassword(e)) setAsk({ table: t, error: password ? errText(e) : null })
      else pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setBusy(false)
    }
  }

  const spectate = (t: Table) => {
    if (!t.gameId) return
    useGame.getState().connect(t.gameId, { spectate: true })
    go('game')
  }

  const count = tables?.length ?? 0
  const sub = [count === 1 ? '1 Tisch' : `${count} Tische`, online !== undefined ? `${online} Spieler online` : null].filter(Boolean).join(' · ')

  return (
    <div className="flex h-full min-h-0" data-testid="lobby-screen">
      <div className="flex h-full min-w-0 flex-1 flex-col gap-4 overflow-y-auto px-8 py-[26px] scrollbar-thin board:gap-[26px] board:px-14 board:py-11">
        <div className="flex items-center gap-4">
          <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Lobby</h1>
          {tables !== null && <span className="text-[14px] text-fg-3">{count === 0 ? 'Kein offener Tisch' : sub}</span>}
          <span className="flex-1" />
          <Button
            variant="secondary"
            icon="autoMana"
            onClick={() => (me?.tier === 'public' ? void openLocalApp() : go('solo'))}
            title={me?.tier === 'public' ? 'Spiele gegen Bots laufen für dich in der MageLite-App auf deinem PC' : undefined}
            testId="lobby-solo"
          >
            Allein üben
          </Button>
          <Button variant="primary" icon="plus" disabled={busy} onClick={openTable} testId="lobby-open-table">
            Tisch eröffnen
          </Button>
        </div>

        {tables !== null && tables.length === 0 ? (
          <EmptyState
            className="flex-1"
            icon="lobby"
            title="Gerade kein offener Tisch"
            text="Eröffne einen Tisch und lade Freunde ein. Freie Plätze bleiben leer – setze Bots oder lade Freunde ein."
            primary={
              <Button variant="primary" icon="plus" disabled={busy} onClick={openTable}>
                Tisch eröffnen
              </Button>
            }
          />
        ) : (
          <div className="flex flex-col" role="table" aria-label="Tische">
            <div role="row" className="tbl-head" style={{ gridTemplateColumns: COLUMNS, padding: '0 14px 10px' }}>
              <span>Tisch</span>
              <span>Plätze</span>
              <span>Tempo</span>
              <span>Status</span>
              <span />
            </div>
            {tables?.map((t) => (
              <LobbyRow key={t.id} t={t} meName={me?.name} busy={busy} onOpen={() => {
                setTableId(t.id)
                go('table')
              }} onJoin={() => void join(t)} onSpectate={() => spectate(t)} />
            ))}
          </div>
        )}
      </div>
      <SocialSidebar />
      {create && (
        <CreateTableDialog
          busy={busy}
          onClose={() => setCreate(false)}
          onCreate={async (opts) => {
            if (await run(() => tablesApi.create(opts))) setCreate(false)
          }}
        />
      )}
      {ask && <PasswordDialog table={ask.table} error={ask.error} busy={busy} onClose={() => setAsk(null)} onJoin={(pw) => void join(ask.table, pw)} />}
    </div>
  )
}

/** wie GameHost.MAX_SPECTATORS */
const MAX_SPECTATORS = 8

function LobbyRow({ t, meName, busy, onOpen, onJoin, onSpectate }: { t: Table; meName?: string; busy: boolean; onOpen: () => void; onJoin: () => void; onSpectate: () => void }) {
  const seated = t.mySeat !== null
  const running = t.state === 'RUNNING'
  const free = t.seats.filter((s) => s.kind === 'OPEN').length
  const remote = t.hosting === 'REMOTE'
  const hostLabel = t.host ? `${meName ?? t.hostName} (du)` : t.hostName
  const where = remote ? (t.host ? ' · auf deinem Rechner' : ` · auf ${t.hostName}s Rechner`) : ''

  let status: { text: string; color: string }
  if (seated) status = { text: 'Du sitzt hier', color: 'var(--color-ember)' }
  else if (running) status = { text: t.turn ? `Läuft · Zug ${t.turn}` : 'Läuft', color: 'var(--color-fg-3)' }
  else if (remote && !t.hostLinkOk) status = { text: 'Gastgeber offline', color: 'var(--color-fg-3)' }
  else if (free > 0) status = { text: `Offen · ${t.humans}/4`, color: 'var(--color-chosen)' }
  else status = { text: 'Voll', color: 'var(--color-fg-3)' }

  const rowStyle: CSSProperties = { gridTemplateColumns: COLUMNS, padding: 14 }
  if (seated) {
    rowStyle.background = 'color-mix(in oklab, var(--color-ember) 5%, transparent)'
    rowStyle.boxShadow = 'inset 2px 0 0 var(--color-ember)'
  }
  const btnStyle: CSSProperties = { height: 36, padding: '0 14px', fontSize: 15 }

  return (
    <div role="row" className="tbl-row" style={rowStyle} data-testid="lobby-row" data-table={t.id} data-hosting={t.hosting} data-locked={t.locked ? 'true' : undefined}>
      <div className="flex min-w-0 flex-col gap-1">
        <span className="flex min-w-0 items-center gap-1.5 font-display text-[20px] font-semibold leading-none text-fg-1">
          {t.locked && <Icon name="lock" size={15} className="flex-none text-fg-3" title="Privater Tisch – Passwort nötig" />}
          <span className="truncate">{t.name}</span>
        </span>
        <span className="flex min-w-0 items-center gap-1 truncate text-[12.5px] text-fg-3">
          {remote && <Icon name="desktop" size={12} className="flex-none" />}
          <span className="truncate">
            Gastgeber {hostLabel}
            {where}
          </span>
        </span>
      </div>
      <div className="flex min-w-0 flex-wrap gap-1.5">
        {t.seats.map((s, i) => (
          <SeatChip key={i} seat={s} />
        ))}
      </div>
      <span className="font-display text-[15px] font-semibold uppercase leading-none tracking-[.06em] text-fg-2">{tempoLabel(t.tempo)}</span>
      <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.08em]" style={{ color: status.color }}>
        {status.text}
      </span>
      <div className="flex justify-end">
        {seated ? (
          <Button variant="primary" style={btnStyle} onClick={onOpen} testId="lobby-open">
            Öffnen
          </Button>
        ) : !running && free > 0 ? (
          <Button variant="secondary" style={btnStyle} disabled={busy} onClick={onJoin} testId="lobby-join" icon={t.locked ? 'lock' : undefined}>
            Setzen
          </Button>
        ) : running && t.canSpectate && t.gameId ? (
          <Button variant="ghost" style={btnStyle} onClick={onSpectate} testId="spectate-btn" title={t.spectators ? `${t.spectators} schauen zu` : undefined}>
            Zuschauen
          </Button>
        ) : running && t.gameId ? (
          <Button
            variant="ghost"
            style={btnStyle}
            disabled
            testId="spectate-btn"
            title={remote ? 'Bei Tischen auf dem Rechner des Gastgebers geht Zuschauen (noch) nicht' : (t.spectators ?? 0) >= MAX_SPECTATORS ? `Alle ${MAX_SPECTATORS} Zuschauerplätze sind belegt` : 'Zuschauen geht nur, solange du an keinem Tisch sitzt'}
          >
            Zuschauen
          </Button>
        ) : null}
      </div>
    </div>
  )
}

function SeatChip({ seat }: { seat: TableSeat }) {
  const open = seat.kind === 'OPEN'
  const icon: IconName = seat.kind === 'BOT' ? 'bot' : open ? 'addFriend' : 'human'
  const label = seat.kind === 'BOT' ? 'Bot' : open ? 'frei' : (seat.name ?? '?')
  return (
    <span
      className={`inline-flex h-7 items-center gap-[5px] rounded-xs px-2 text-[12.5px] font-semibold ${open ? 'text-fg-4' : 'bg-bg-4 text-fg-1'}`}
      style={open ? { boxShadow: 'inset 0 0 0 1px var(--color-line-3)' } : undefined}
      title={open ? 'Freier Platz' : seat.kind === 'BOT' ? 'Bot' : seat.name}
    >
      <Icon name={icon} size={12} />
      <span className="max-w-[110px] truncate">{label}</span>
    </span>
  )
}

/**
 * Tisch eroeffnen: Name, Ort (Server / eigener Rechner - nur mit angebundener MageLite-App), privat mit Passwort.
 * Der Host-Link-Stand kommt aus /api/me (Lobby laedt ihn beim Oeffnen neu).
 */
function CreateTableDialog({ busy, onClose, onCreate }: { busy: boolean; onClose: () => void; onCreate: (opts: { name?: string; hosting: TableHosting; password?: string }) => Promise<void> }) {
  const hostLink = useAuth((s) => s.hostLink)
  // oeffentliche (selbst registrierte) Konten: Server-Tische nur fuer Eingeladene
  const serverAllowed = useAuth((s) => s.me?.tier !== 'public')
  const [name, setName] = useState('')
  const [hosting, setHosting] = useState<TableHosting>(serverAllowed ? 'SERVER' : 'REMOTE')
  const [locked, setLocked] = useState(false)
  const [password, setPassword] = useState('')
  const desktop = typeof window !== 'undefined' && !!window.mageliteDesktop
  const canCreate = !busy && (!locked || password.trim().length > 0) && (hosting !== 'REMOTE' || hostLink)

  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    if (!canCreate) return
    void onCreate({ name: name.trim() || undefined, hosting, password: locked ? password.trim() : undefined })
  }

  return (
    <Overlay
      testId="create-table-dialog"
      label="Lobby"
      title="Tisch eröffnen"
      width={520}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" kbd="Esc" onClick={onClose} testId="modal-cancel">
            Abbrechen
          </Button>
          <Button variant="primary" icon="plus" kbd="Enter" disabled={!canCreate} onClick={() => submit()} testId="create-table-submit">
            Eröffnen
          </Button>
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-5 px-5 py-[18px]">
        <TextField label="Name des Tisches" autoFocus value={name} maxLength={40} placeholder="z. B. Freitagsrunde" onChange={(e) => setName(e.target.value)} data-testid="create-table-name" />
        <div className="flex flex-col gap-2">
          <span className="label">Wo rechnet das Spiel</span>
          <Segmented<TableHosting>
            variant="boxed"
            ariaLabel="Wo rechnet das Spiel"
            className="self-start"
            itemStyle={{ padding: '8px 14px' }}
            value={hosting}
            onChange={setHosting}
            items={[
              {
                id: 'SERVER',
                label: 'Auf dem Server',
                disabled: !serverAllowed,
                testId: 'create-table-server',
                title: serverAllowed ? undefined : 'Server-Tische gibt es nur für eingeladene Spieler',
              },
              { id: 'REMOTE', label: 'Auf meinem Rechner', disabled: !hostLink, testId: 'create-table-remote', title: hostLink ? undefined : 'Dafür muss die MageLite-App auf deinem PC laufen und hier angemeldet sein' },
            ]}
          />
          <span className="text-[12.5px] leading-[1.45] text-fg-3" data-testid="create-table-hosting-hint">
            {!serverAllowed && !hostLink
              ? 'Tische eröffnest du auf deinem eigenen Rechner: MageLite-App installieren, dort „Online spielen“ und anmelden. Beitreten kannst du überall.'
              : hosting === 'REMOTE'
              ? 'Das Spiel und die Bots laufen in deiner MageLite-App; der Server reicht nur durch. Der Server bleibt frei für andere.'
              : hostLink
                ? 'Der Server rechnet nur ein Spiel gleichzeitig. Deine App ist verbunden – du kannst auch auf deinem Rechner hosten.'
                : desktop
                  ? 'Du bist in der App, aber noch nicht verbunden – einen Moment, oder neu anmelden.'
                  : 'Mehr Tische gleichzeitig: MageLite-App installieren, dort anmelden, dann „Auf meinem Rechner“ wählen.'}
          </span>
        </div>
        <div className="flex flex-col gap-2.5">
          <Checkbox checked={locked} onChange={setLocked} label="Privat – Beitritt nur mit Passwort (Eingeladene brauchen keins)" testId="create-table-private" />
          {locked && (
            <PasswordInput label="Tisch-Passwort" value={password} maxLength={40} autoComplete="off" autoFocus onChange={(e) => setPassword(e.target.value)} data-testid="create-table-password" />
          )}
        </div>
      </form>
    </Overlay>
  )
}

/** Beitritt zu einem privaten Tisch: Passwort abfragen (403 needPassword). */
function PasswordDialog({ table, error, busy, onClose, onJoin }: { table: Table; error: string | null; busy: boolean; onClose: () => void; onJoin: (password: string) => void }) {
  const [password, setPassword] = useState('')
  const submit = (e?: FormEvent) => {
    e?.preventDefault()
    if (busy || !password.trim()) return
    onJoin(password.trim())
  }
  return (
    <Overlay
      testId="table-password-dialog"
      variant="confirm"
      width={420}
      label="Privater Tisch"
      title={table.name}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" kbd="Esc" onClick={onClose} testId="modal-cancel">
            Abbrechen
          </Button>
          <Button variant="primary" kbd="Enter" disabled={busy || !password.trim()} onClick={() => submit()} testId="table-password-submit">
            Beitreten
          </Button>
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-2 pt-1">
        <span className="text-[13px] text-fg-3">{table.hostName} hat diesen Tisch mit einem Passwort geschützt.</span>
        <PasswordInput label="Passwort" autoFocus autoComplete="off" value={password} onChange={(e) => setPassword(e.target.value)} error={error ?? undefined} data-testid="table-password" />
      </form>
    </Overlay>
  )
}
