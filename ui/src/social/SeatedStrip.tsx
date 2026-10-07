import { useState } from 'react'
import { Button } from '../components/ui'
import { Icon } from '../lib/icons'
import { tempoLabel } from '../lib/tempo'
import { useNav } from '../store/nav'
import { useSocial } from '../store/social'
import { useTable } from '../store/table'
import { InvitePopover } from './InvitePopover'

/**
 * Leiste "Du sitzt am Tisch · {name} · {humans}/4 · {Tempo}" mit "Einladen" (InvitePopover) und "Zum Tisch".
 * Liest useSocial().myTable selbst; erscheint nur, wenn ich an einem Tisch sitze.
 */
export function SeatedStrip() {
  const myTable = useSocial((s) => s.myTable)
  const friends = useSocial((s) => s.friends)
  const setTableId = useTable((s) => s.setTableId)
  const go = useNav((s) => s.go)
  const [open, setOpen] = useState(false)

  if (!myTable) return null
  const running = myTable.state === 'RUNNING'
  const seatedIds = friends.filter((f) => f.status === 'table' && f.tableId?.toLowerCase() === myTable.id.toLowerCase()).map((f) => f.id)
  const toTable = () => {
    setTableId(myTable.id)
    go('table')
  }

  return (
    <div className="flex items-center gap-4 rounded-md bg-bg-3 px-[18px] py-4" style={{ boxShadow: 'inset 0 0 0 1px var(--color-ember)' }} data-testid="seated-strip">
      <span className="flex h-11 w-11 flex-none items-center justify-center rounded-sm text-ember" style={{ background: 'color-mix(in oklab, var(--color-ember) 10%, transparent)' }}>
        <Icon name="toTable" size={22} />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-[5px]">
        <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.12em] text-ember">{running ? 'Du spielst am Tisch' : 'Du sitzt am Tisch'}</span>
        <span className="truncate font-display text-[24px] font-semibold leading-none text-fg-1">
          {myTable.name} · {myTable.humans}/4 · {tempoLabel(myTable.tempo)}
        </span>
      </div>
      <div className="relative flex-none">
        <Button
          variant="secondary"
          icon="addFriend"
          testId="seated-invite"
          aria-expanded={open}
          disabled={!myTable.invitable}
          title={myTable.invitable ? undefined : running ? 'Das Spiel läuft schon' : 'Am Tisch ist kein Platz frei'}
          onClick={() => setOpen((o) => !o)}
        >
          Einladen
        </Button>
        <InvitePopover open={open && myTable.invitable} onClose={() => setOpen(false)} tableId={myTable.id} seatedIds={seatedIds} />
      </div>
      <Button variant="primary" testId="seated-to-table" className="flex-none" onClick={toTable}>
        Zum Tisch
      </Button>
    </div>
  )
}
