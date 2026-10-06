import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import { tablesApi } from '../api/tables'
import { useNav } from '../store/nav'
import { useSocial } from '../store/social'
import { useTable } from '../store/table'

/** Tisch-Einladungen von Freunden (überall außer im laufenden Spiel). */
export function InviteToasts() {
  const invites = useSocial((s) => s.invites)
  const decline = useSocial((s) => s.decline)
  const refresh = useSocial((s) => s.refresh)
  const setTableId = useTable((s) => s.setTableId)
  const tableId = useTable((s) => s.tableId)
  const go = useNav((s) => s.go)
  const [busy, setBusy] = useState<number | null>(null)
  const [errors, setErrors] = useState<Record<number, string>>({})

  const visible = invites.filter((i) => i.tableId !== tableId)

  const join = async (inviteId: number, id: string) => {
    setBusy(inviteId)
    try {
      const t = await tablesApi.join(id)
      setTableId(t.id)
      go('table')
      void refresh()
    } catch (e) {
      setErrors((m) => ({ ...m, [inviteId]: e instanceof Error ? e.message : String(e) }))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-80 flex-col gap-2">
      <AnimatePresence>
        {visible.map((i) => (
          <motion.div
            key={i.id}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className="glass pointer-events-auto rounded-2xl p-4 shadow-2xl ring-1 ring-gold-400/40"
          >
            <div className="flex items-start gap-3">
              <div className="text-2xl">✉️</div>
              <div className="min-w-0 flex-1 text-sm text-ink-200">
                <b className="text-gold-300">{i.fromName}</b> lädt dich an <b className="text-ink-100">„{i.tableName}“</b> ein.
              </div>
            </div>
            {errors[i.id] && <div className="mt-2 text-xs text-blood-400">{errors[i.id]}</div>}
            <div className="mt-3 flex justify-end gap-2">
              <button className="btn-ghost !py-1 !text-xs" onClick={() => decline(i.id)}>
                Ablehnen
              </button>
              <button className="btn-primary !py-1 !text-xs" disabled={busy === i.id} onClick={() => join(i.id, i.tableId)}>
                {busy === i.id ? 'Trete bei …' : 'Beitreten'}
              </button>
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
