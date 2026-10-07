import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { tablesApi, type Table, type TableSeat } from '../api/tables'
import { Button, EmptyState } from '../components/ui'
import { Icon, type IconName } from '../lib/icons'
import { tempoLabel } from '../lib/tempo'
import { useAuth } from '../store/auth'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useSocial } from '../store/social'
import { useTable } from '../store/table'
import { pushToast } from '../store/ui'

const POLL_MS = 3000

/** Spalten: Tisch · Plaetze · Tempo · Status · Aktion */
const COLUMNS = 'minmax(200px,1.6fr) minmax(200px,1.4fr) 100px 120px 150px'

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** Sichtbare Online-Mitglieder aus dem Social-Snapshot (Feld von O1; undefined = unbekannt) */
function selectOnline(s: unknown): number | undefined {
  const v = (s as { online?: number }).online
  return typeof v === 'number' ? v : undefined
}

/** Online: Lobby mit offenen und laufenden Tischen, Tisch eroeffnen, allein ueben, zuschauen. */
export function LobbyScreen() {
  const go = useNav((s) => s.go)
  const me = useAuth((s) => s.me)
  const tableId = useTable((s) => s.tableId)
  const setTableId = useTable((s) => s.setTableId)
  const online = useSocial(selectOnline)
  const [tables, setTables] = useState<Table[] | null>(null)
  const [busy, setBusy] = useState(false)

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
      if (!document.hidden) void load()
    }, POLL_MS)
    return () => window.clearInterval(iv)
  }, [load])

  const run = async (fn: () => Promise<Table>) => {
    setBusy(true)
    try {
      const t = await fn()
      setTableId(t.id)
      go('table')
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setBusy(false)
    }
  }

  const openTable = () => {
    if (tableId) go('table')
    else void run(() => tablesApi.create())
  }

  const spectate = (t: Table) => {
    if (!t.gameId) return
    useGame.getState().connect(t.gameId, { spectate: true })
    go('game')
  }

  const count = tables?.length ?? 0
  const sub = [count === 1 ? '1 Tisch' : `${count} Tische`, online !== undefined ? `${online} Spieler online` : null].filter(Boolean).join(' · ')

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto px-8 py-[26px] scrollbar-thin board:gap-[26px] board:px-14 board:py-11">
      <div className="flex items-center gap-4">
        <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Lobby</h1>
        {tables !== null && <span className="text-[14px] text-fg-3">{count === 0 ? 'Kein offener Tisch' : sub}</span>}
        <span className="flex-1" />
        <Button variant="secondary" icon="autoMana" onClick={() => go('solo')} testId="lobby-solo">
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
            }} onJoin={() => void run(() => tablesApi.join(t.id))} onSpectate={() => spectate(t)} />
          ))}
        </div>
      )}
    </div>
  )
}

/** wie GameHost.MAX_SPECTATORS */
const MAX_SPECTATORS = 8

function LobbyRow({ t, meName, busy, onOpen, onJoin, onSpectate }: { t: Table; meName?: string; busy: boolean; onOpen: () => void; onJoin: () => void; onSpectate: () => void }) {
  const seated = t.mySeat !== null
  const running = t.state === 'RUNNING'
  const free = t.seats.filter((s) => s.kind === 'OPEN').length
  const hostLabel = t.host ? `${meName ?? t.hostName} (du)` : t.hostName

  let status: { text: string; color: string }
  if (seated) status = { text: 'Du sitzt hier', color: 'var(--color-ember)' }
  else if (running) status = { text: t.turn ? `Läuft · Zug ${t.turn}` : 'Läuft', color: 'var(--color-fg-3)' }
  else if (free > 0) status = { text: `Offen · ${t.humans}/4`, color: 'var(--color-chosen)' }
  else status = { text: 'Voll', color: 'var(--color-fg-3)' }

  const rowStyle: CSSProperties = { gridTemplateColumns: COLUMNS, padding: 14 }
  if (seated) {
    rowStyle.background = 'color-mix(in oklab, var(--color-ember) 5%, transparent)'
    rowStyle.boxShadow = 'inset 2px 0 0 var(--color-ember)'
  }
  const btnStyle: CSSProperties = { height: 36, padding: '0 14px', fontSize: 15 }

  return (
    <div role="row" className="tbl-row" style={rowStyle} data-testid="lobby-row" data-table={t.id}>
      <div className="flex min-w-0 flex-col gap-1">
        <span className="truncate font-display text-[20px] font-semibold leading-none text-fg-1">{t.name}</span>
        <span className="truncate text-[12.5px] text-fg-3">Gastgeber {hostLabel}</span>
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
          <Button variant="secondary" style={btnStyle} disabled={busy} onClick={onJoin} testId="lobby-join">
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
            title={(t.spectators ?? 0) >= MAX_SPECTATORS ? `Alle ${MAX_SPECTATORS} Zuschauerplätze sind belegt` : 'Zuschauen geht nur, solange du an keinem Tisch sitzt'}
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
