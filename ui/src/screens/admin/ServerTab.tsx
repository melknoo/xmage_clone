import { useState } from 'react'
import { adminApi, type AdminGame, type AdminTable, type BudgetStatus, type ServerInfo } from '../../api/admin'
import { Button, ProgressBar, TableHead, TableRow, Th } from '../../components/ui'
import { pushToast } from '../../store/ui'
import { AdminSection, created, errText, mb, span } from './shared'

/** Spalten Spiele: Wo · Spieler · Tempo · Zug · Laeuft seit · Zuschauer · Aktion */
const GAME_COLUMNS = 'minmax(140px,1fr) minmax(200px,1.6fr) 90px 60px 110px 90px 130px'
/** Spalten Tische: Name · Gastgeber · Zustand · Plaetze · Eroeffnet · Aktion */
const TABLE_COLUMNS = 'minmax(160px,1.2fr) minmax(120px,1fr) 110px minmax(150px,1fr) 100px 130px'

/**
 * Server-Uebersicht: Kennzahlen (Version, Laufzeit, Speicher, Belegung, online, Tische), laufende Spiele mit "Beenden"
 * und Tische mit "Schliessen" (je mit Bestaetigung im Knopf). Daten kommen vom AdminScreen (Polling alle 5 s).
 */
export function ServerTab({ info, onChanged }: { info: ServerInfo | null; onChanged: () => void }) {
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState<string | null>(null)

  if (!info) return <div className="px-3 py-4 text-[13px] text-fg-3">Lade …</div>

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
      onChanged()
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setBusy(false)
      setConfirm(null)
    }
  }

  const abort = (g: AdminGame) =>
    run(async () => {
      await adminApi.abortGame(g.id)
      pushToast({ kind: 'success', text: 'Spiel wird beendet' })
    })
  const close = (t: AdminTable) =>
    run(async () => {
      await adminApi.closeTable(t.id)
      pushToast({ kind: 'success', text: `Tisch „${t.name}“ geschlossen` })
    })

  const heapPct = info.heapMax > 0 ? Math.round((info.heapUsed / info.heapMax) * 100) : 0
  const full = info.running >= info.maxGames

  return (
    <div className="flex max-w-[1200px] flex-col gap-6 board:gap-8" data-testid="admin-server">
      <div className="grid grid-cols-3 gap-y-5 border-b border-line-2 pb-5 board:grid-cols-6">
        <Tile label="Version" value={info.version} sub="Engine" />
        <Tile label="Laufzeit" value={span(info.uptimeMs)} sub={`seit ${created(info.startedAt)}`} />
        <Tile label="Spiele" value={`${info.running}/${info.maxGames}`} sub={full ? 'voll – andere warten' : 'frei'} tone={full ? 'ember' : undefined} />
        <Tile label="Online" value={String(info.online)} sub="außerhalb von Spielen" />
        <Tile label="Tische" value={String(info.tableCount)} sub="in der Lobby" />
        <div className="flex min-w-0 flex-col gap-2.5 border-l border-line-2 pl-5" data-kpi="heap">
          <span className="label">Speicher</span>
          <span className="num text-[40px] leading-[.85] text-fg-1">{heapPct} %</span>
          <ProgressBar value={info.heapUsed} max={info.heapMax} height={3} tone={heapPct > 80 ? 'attack' : 'fg'} />
          <span className="truncate text-[12.5px] text-fg-3">
            {mb(info.heapUsed)} von {mb(info.heapMax)}
          </span>
        </div>
      </div>

      {info.budget && <BudgetRow budget={info.budget} />}

      <AdminSection title="Laufende Spiele" testId="admin-games">
        {info.games.length === 0 ? (
          <span className="px-3 text-[13px] text-fg-3">Gerade läuft kein Spiel.</span>
        ) : (
          <div className="flex flex-col" role="table" aria-label="Laufende Spiele">
            <TableHead columns={GAME_COLUMNS}>
              <Th>Wo</Th>
              <Th>Spieler</Th>
              <Th>Tempo</Th>
              <Th num>Zug</Th>
              <Th>Läuft seit</Th>
              <Th num>Zuschauer</Th>
              <Th />
            </TableHead>
            {info.games.map((g) => (
              <TableRow key={g.id} columns={GAME_COLUMNS} testId="admin-game-row">
                <span className="flex min-w-0 flex-col">
                  <span className="truncate text-[14px] font-semibold text-fg-1">{g.table ?? 'Allein gegen Bots'}</span>
                  {g.remoteHost && <span className="truncate text-[12px] text-fg-3">auf dem Rechner von {g.remoteHost}</span>}
                </span>
                <span className="flex min-w-0 flex-wrap items-baseline gap-x-2 text-[13px]">
                  {g.humans.map((h, i) => (
                    <span key={h.userId} className={h.conceded ? 'text-fg-4 line-through' : h.connected ? 'text-fg-1' : 'text-attack'} title={h.conceded ? 'aufgegeben' : h.connected ? 'verbunden' : 'getrennt'}>
                      {h.name}
                      {i < g.humans.length - 1 ? ',' : ''}
                    </span>
                  ))}
                  {g.bots > 0 && <span className="text-fg-3">+ {g.bots} {g.bots === 1 ? 'Bot' : 'Bots'}</span>}
                </span>
                <span className="text-[13px] text-fg-3">{g.tempo.toLowerCase()}</span>
                <span className="tbl-num">{g.turn || '–'}</span>
                <span className="text-[13px] text-fg-3">{g.startedAt ? span(Date.now() - g.startedAt) : '–'}</span>
                <span className="tbl-num">{g.spectators}</span>
                <span className="flex justify-end">
                  {confirm === g.id ? (
                    <Button variant="dangerConfirm" size="xs" autoFocus disabled={busy} onBlur={() => setConfirm(null)} onClick={() => void abort(g)} testId="admin-abort-confirm">
                      Wirklich beenden?
                    </Button>
                  ) : (
                    <Button variant="secondary" size="xs" icon="concede" disabled={busy} onClick={() => setConfirm(g.id)} testId="admin-abort">
                      Beenden
                    </Button>
                  )}
                </span>
              </TableRow>
            ))}
          </div>
        )}
      </AdminSection>

      <AdminSection title="Tische" testId="admin-tables">
        {info.tables.length === 0 ? (
          <span className="px-3 text-[13px] text-fg-3">Kein Tisch offen.</span>
        ) : (
          <div className="flex flex-col" role="table" aria-label="Tische">
            <TableHead columns={TABLE_COLUMNS}>
              <Th>Name</Th>
              <Th>Gastgeber</Th>
              <Th>Zustand</Th>
              <Th>Plätze</Th>
              <Th>Eröffnet</Th>
              <Th />
            </TableHead>
            {info.tables.map((t) => (
              <TableRow key={t.id} columns={TABLE_COLUMNS} testId="admin-table-row">
                <span className="truncate text-[14px] font-semibold text-fg-1">{t.name}</span>
                <span className="truncate text-[13px] text-fg-2">{t.hostName}</span>
                <span className={t.state === 'RUNNING' ? 'text-[13px] text-ember' : 'text-[13px] text-fg-3'}>{t.state === 'RUNNING' ? 'Spiel läuft' : 'Lobby'}</span>
                <span className="text-[13px] text-fg-3">
                  {t.humans} {t.humans === 1 ? 'Mensch' : 'Menschen'}
                  {t.bots ? ` · ${t.bots} ${t.bots === 1 ? 'Bot' : 'Bots'}` : ''}
                  {t.open ? ` · ${t.open} frei` : ''}
                </span>
                <span className="text-[13px] text-fg-3">{created(t.createdAt)}</span>
                <span className="flex justify-end">
                  {confirm === t.id ? (
                    <Button variant="dangerConfirm" size="xs" autoFocus disabled={busy} onBlur={() => setConfirm(null)} onClick={() => void close(t)} testId="admin-close-table-confirm">
                      Wirklich schließen?
                    </Button>
                  ) : (
                    <Button variant="secondary" size="xs" icon="close" disabled={busy} onClick={() => setConfirm(t.id)} testId="admin-close-table">
                      Schließen
                    </Button>
                  )}
                </span>
              </TableRow>
            ))}
          </div>
        )}
      </AdminSection>
    </div>
  )
}

/** Laufzeit diesen Monat gegen das Budget, geschaetzte Kosten, Zustand der oeffentlichen Konten. */
function BudgetRow({ budget }: { budget: BudgetStatus }) {
  const hours = budget.minutes / 60
  const max = budget.budgetMin / 60
  const pct = budget.budgetMin > 0 ? Math.min(100, Math.round((budget.minutes / budget.budgetMin) * 100)) : 100
  const cost = (hours * budget.pricePerHour).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const maxCost = (max * budget.pricePerHour).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  // Monat und Stichtag in UTC (wie die Engine zaehlt), sonst kippt "Oktober" nachts in "November"
  const [y, m] = budget.month.split('-').map(Number)
  const month = new Date(Date.UTC(y, m - 1, 15)).toLocaleDateString('de-DE', { month: 'long', timeZone: 'UTC' })
  const reset = new Date(budget.resetsAt).toLocaleDateString('de-DE', { day: 'numeric', month: 'long', timeZone: 'UTC' })
  return (
    <div className="flex flex-col gap-2.5 border-b border-line-2 pb-5 pl-5" data-kpi="budget" data-testid="admin-budget">
      <span className="label">Laufzeit {month}</span>
      <div className="flex items-baseline gap-4">
        <span className="num text-[40px] leading-[.85]" style={{ color: budget.exhausted ? 'var(--color-ember)' : 'var(--color-fg-1)' }}>
          {hours.toLocaleString('de-DE', { maximumFractionDigits: 1 })} / {max.toLocaleString('de-DE', { maximumFractionDigits: 1 })} h
        </span>
        <span className="text-[13px] text-fg-3">
          ≈ {cost} $ von {maxCost} $ · {budget.exhausted ? `registrierte Konten gesperrt bis ${reset}` : `registrierte Konten frei (${pct} %)`}
        </span>
      </div>
      <ProgressBar value={Math.min(budget.minutes, budget.budgetMin)} max={Math.max(1, budget.budgetMin)} height={3} tone={budget.exhausted ? 'attack' : pct >= 80 ? 'ember' : 'fg'} />
    </div>
  )
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub: string; tone?: 'ember' }) {
  return (
    <div className="flex min-w-0 flex-col gap-2.5 border-l border-line-2 pl-5">
      <span className="label">{label}</span>
      <span className="num truncate text-[40px] leading-[.85]" style={{ color: tone === 'ember' ? 'var(--color-ember)' : 'var(--color-fg-1)' }}>
        {value}
      </span>
      <span className="truncate text-[12.5px] text-fg-3">{sub}</span>
    </div>
  )
}
