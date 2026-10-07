import { AnimatePresence, motion } from 'motion/react'
import { CardView } from '../components/CardView'
import { Icon } from '../lib/icons'
import { enter } from '../lib/motion'
import { useGame } from '../store/game'
import { useBoardLayout } from './layout'

/**
 * Aufgedeckte/angesehene Karten (store.reveals, 12 s; XMage zeigt sie nur einen Moment im State).
 * Schwebende Flaechen oben rechts im Brett (neben der Seitenleiste), Karten 62 bzw. 46 px.
 */
export function RevealPopups() {
  const reveals = useGame((s) => s.reveals)
  const dismiss = useGame((s) => s.dismissReveal)
  const setHover = useGame((s) => s.setHover)
  const { compact } = useBoardLayout()
  const cardW = compact ? 46 : 62
  return (
    <div
      className="pointer-events-none fixed z-[25] flex flex-col items-end gap-2"
      style={{ right: 'calc(var(--side-w, 336px) + 14px)', top: 'calc(var(--hdr-h, 50px) + 14px)', maxWidth: compact ? 460 : 560 }}
      data-testid="reveal-popups"
    >
      <AnimatePresence>
        {reveals.map((r) => (
          <motion.div key={r.key} {...enter} className="floating pointer-events-auto flex flex-col gap-2.5 p-3">
            <div className="flex items-center gap-2">
              <Icon name={r.looked ? 'spectate' : 'revealed'} size={16} className="text-fg-3" />
              <span className="label">{r.looked ? 'Angesehen' : 'Aufgedeckt'}</span>
              <span className="min-w-0 flex-1 truncate text-[13.5px] font-semibold text-fg-1">{r.name}</span>
              <button type="button" className="btn-icon" style={{ width: 28, height: 28 }} title="Schließen" aria-label="Schließen" onClick={() => dismiss(r.key)}>
                <Icon name="close" size={16} />
              </button>
            </div>
            <div className="flex flex-wrap justify-end gap-2">
              {r.cards.map((c) => (
                <CardView key={c.id} card={c} width={cardW} anchor={false} onHover={setHover} upright />
              ))}
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
