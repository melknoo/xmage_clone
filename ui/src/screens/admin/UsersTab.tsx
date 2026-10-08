import type { AdminUser } from '../../api/admin'
import { EmptyState, Num, StatusDot, TableHead, TableRow, Th } from '../../components/ui'
import { ago, STATUS_LABEL } from './shared'

/** Spalten: Name · Status · Level · Spiele · Siege · Zuletzt online · Konto */
const COLUMNS = 'minmax(150px,1.2fr) minmax(150px,1fr) minmax(130px,.9fr) 64px 64px minmax(120px,.8fr) minmax(150px,1fr)'
/** Kompakt (Detail offen, Fenster < 1440): ohne "Zuletzt online" und "Konto" - stehen im Detail */
const COLUMNS_COMPACT = 'minmax(120px,1.2fr) minmax(130px,1fr) minmax(100px,.9fr) 56px 56px'

/** Reihenfolge: im Spiel, am Tisch, online, offline; darin zuletzt gesehen zuerst */
const RANK = { game: 0, table: 1, online: 2, offline: 3 } as const

/**
 * Nutzerliste (alle angemeldeten Konten, ohne offene Einladungen): Status live, Level/Titel, Spiele/Siege, zuletzt
 * online, Gast oder E-Mail. Klick auf eine Zeile oeffnet das Detail rechts.
 */
export function UsersTab({ users, meId, selected, onSelect, compact }: { users: AdminUser[] | null; meId?: number; selected: number | null; onSelect: (id: number | null) => void; compact?: boolean }) {
  if (users === null) return <div className="px-3 py-4 text-[13px] text-fg-3">Lade …</div>
  const active = users
    // nie angemeldete Einladungen stehen unter "Einladungen"; Registrierungen (auch unbestaetigte) hier
    .filter((u) => u.lastSeen !== null || u.tier === 'public')
    .sort((a, b) => RANK[a.status] - RANK[b.status] || (b.lastSeen ?? 0) - (a.lastSeen ?? 0))
  const columns = compact ? COLUMNS_COMPACT : COLUMNS
  const online = active.filter((u) => u.status !== 'offline').length
  const playing = active.filter((u) => u.status === 'game').length

  if (active.length === 0) {
    return <EmptyState icon="friends" title="Noch niemand angemeldet" text="Erzeuge unter „Einladungen“ einen Code und schick den Link an Freunde." />
  }
  return (
    <div className="flex flex-col gap-3">
      <span className="text-[13.5px] text-fg-3" data-testid="admin-users-summary">
        {active.length} {active.length === 1 ? 'Konto' : 'Konten'} · {online} online · {playing} im Spiel
      </span>
      <div className="flex max-w-[1200px] flex-col" role="table" aria-label="Nutzer">
        <TableHead columns={columns}>
          <Th>Name</Th>
          <Th>Status</Th>
          <Th>Level</Th>
          <Th num>Spiele</Th>
          <Th num>Siege</Th>
          {!compact && <Th>Zuletzt online</Th>}
          {!compact && <Th>Konto</Th>}
        </TableHead>
        {active.map((u) => (
          <TableRow key={u.id} columns={columns} open={selected === u.id} onClick={() => onSelect(selected === u.id ? null : u.id)} testId="admin-user-row">
            <span className="flex min-w-0 items-baseline gap-2">
              <span className="truncate text-[14px] font-semibold text-fg-1">{u.name}</span>
              {u.id === meId && <span className="text-[12px] text-fg-3">(du)</span>}
              {u.admin && <span className="label !text-[10.5px] !text-ember">Admin</span>}
              {!u.admin && u.tier === 'public' && (
                <span className="label !text-[10.5px]" title={u.verified ? 'Selbst registriert' : 'Selbst registriert, E-Mail noch nicht bestätigt'} data-testid="admin-user-public">
                  {u.verified ? 'Registriert' : 'Unbestätigt'}
                </span>
              )}
            </span>
            <span className="flex min-w-0 items-center gap-[7px] text-[13px] text-fg-2">
              <StatusDot status={u.status} />
              <span className="truncate" title={u.tableName ?? undefined}>
                {STATUS_LABEL[u.status]}
                {u.status === 'table' && u.tableName ? ` · ${u.tableName}` : ''}
              </span>
            </span>
            <span className="flex min-w-0 items-baseline gap-2">
              <span className="font-display text-[17px] font-semibold tabular-nums text-fg-1">{u.level}</span>
              <span className="truncate text-[12.5px] text-target">{u.title}</span>
            </span>
            <Num value={u.games} />
            <Num value={u.wins} />
            {!compact && <span className="text-[13px] text-fg-3">{ago(u.lastSeen)}</span>}
            {!compact && (
              <span className="truncate text-[13px] text-fg-3" title={u.email ?? undefined}>
                {u.hasPassword && u.email ? u.email : 'Gast'}
              </span>
            )}
          </TableRow>
        ))}
      </div>
    </div>
  )
}
