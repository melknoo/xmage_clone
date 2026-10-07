import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import type { TableInvite } from '../api/social'
import { tablesApi } from '../api/tables'
import { Button } from '../components/ui'
import { Icon } from '../lib/icons'
import { enter } from '../lib/motion'
import { tempoLabel } from '../lib/tempo'
import { useNav } from '../store/nav'
import { useSocial } from '../store/social'
import { useTable } from '../store/table'
import { pushToast } from '../store/ui'
import { useCountdown } from './useCountdown'

/**
 * Einladungskarte unten rechts (340, Ember-Kontur, 3-px-Restzeitbalken, Tischdaten, Ablehnen/Beitreten).
 * Nur im Shell-Zweig von App.tsx, nie im Spiel. Zeigt nur die neueste gueltige Einladung (nicht fuer den eigenen Tisch).
 */
export function InviteCard() {
  const invites = useSocial((s) => s.invites)
  const tableId = useTable((s) => s.tableId)
  const myTableId = useSocial((s) => s.myTable?.id)
  const screen = useNav((s) => s.screen)
  const [gone, setGone] = useState<number[]>([])

  const mine = (id: string) => [tableId, myTableId].some((t) => !!t && t.toLowerCase() === id.toLowerCase())
  const inv = screen === 'game' ? undefined : invites.filter((i) => !mine(i.tableId) && !gone.includes(i.id)).sort((a, b) => b.ts - a.ts)[0]

  return (
    <AnimatePresence>
      {inv && <Card key={inv.id} inv={inv} onExpired={() => setGone((g) => [...g, inv.id])} />}
    </AnimatePresence>
  )
}

function Card({ inv, onExpired }: { inv: TableInvite; onExpired: () => void }) {
  const decline = useSocial((s) => s.decline)
  const refresh = useSocial((s) => s.refresh)
  const setTableId = useTable((s) => s.setTableId)
  const go = useNav((s) => s.go)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const cd = useCountdown(inv.expiresAt ?? inv.ts + 10 * 60_000)

  // abgelaufen: Karte ausblenden (der Server raeumt sie beim naechsten Poll weg)
  const expired = cd.expired
  useEffect(() => {
    if (expired) onExpired()
  }, [expired, onExpired])

  const join = async () => {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const t = await tablesApi.join(inv.tableId)
      setTableId(t.id)
      go('table')
      pushToast({ kind: 'success', text: `Du sitzt jetzt am Tisch „${t.name ?? inv.tableName}“` })
      void refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const no = () => {
    void decline(inv.id)
    pushToast({ kind: 'info', text: 'Einladung abgelehnt' })
  }

  return (
    <motion.div
      {...enter}
      className="fixed bottom-5 right-5 z-[60] w-[340px] overflow-hidden rounded-md bg-bg-3"
      style={{ boxShadow: 'var(--shadow-invite)' }}
      role="dialog"
      aria-label="Tisch-Einladung"
      data-testid="invite-card"
    >
      <div className="h-[3px] bg-line-2">
        <div className="h-full bg-ember transition-[width] duration-4 ease-out" style={{ width: `${cd.ratio * 100}%` }} />
      </div>
      <div className="flex flex-col gap-2.5 px-4 pb-3.5 pt-4">
        <div className="flex items-center gap-2">
          <Icon name="invite" size={16} className="text-ember" />
          <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.12em] text-ember">Einladung</span>
          <span className="flex-1" />
          <span className="font-mono text-[11px] font-medium leading-none text-fg-3">noch {cd.text}</span>
        </div>
        <span className="text-[15px] leading-[1.4] text-fg-1">
          <span className="font-semibold">{inv.fromName}</span> lädt dich an den Tisch ein.
        </span>
        <div className="flex flex-wrap gap-x-3.5 gap-y-1 font-display text-[14px] font-semibold uppercase leading-none tracking-[.06em] text-fg-2">
          <span className="min-w-0 truncate">{inv.tableName}</span>
          {typeof inv.humans === 'number' && <span>{inv.humans}/4</span>}
          {inv.tempo && <span>{tempoLabel(inv.tempo)}</span>}
        </div>
        {error && (
          <span className="field-error" role="alert">
            <Icon name="error" size={14} />
            {error}
          </span>
        )}
        <div className="mt-1 flex gap-2">
          <Button variant="secondary" className="flex-1" testId="invite-decline" disabled={busy} onClick={no}>
            Ablehnen
          </Button>
          <Button
            variant="primary"
            className="flex-1"
            style={{ height: 40, fontSize: 16 }}
            testId="invite-accept"
            disabled={busy}
            onClick={() => void join()}
          >
            Beitreten
          </Button>
        </div>
      </div>
    </motion.div>
  )
}
