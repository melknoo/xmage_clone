import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState, type ReactNode } from 'react'

/** offene Ansichts-Dialoge (Friedhof, Exil, Bibliothek); solange einer offen ist, gehen Spiel-Hotkeys nicht durch */
let openViewers = 0
export const viewerOpen = () => openViewers > 0

export function Modal({
  title,
  children,
  onClose,
  footer,
  wide,
  closable = true,
  minimizable = false,
  viewer = false,
}: {
  title?: ReactNode
  children: ReactNode
  onClose?: () => void
  footer?: ReactNode
  wide?: boolean
  closable?: boolean
  /** Dialog kann eingeklappt werden, damit das Spielfeld sichtbar und bedienbar ist (Tab schaltet um) */
  minimizable?: boolean
  /** reine Ansicht im Spiel: Esc schliesst nur diesen Dialog, Leertaste & Co. erreichen das Spiel nicht */
  viewer?: boolean
}) {
  const [minimized, setMinimized] = useState(false)

  useEffect(() => {
    if (!viewer) return
    openViewers++
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopImmediatePropagation()
      onClose?.()
    }
    // Capture-Phase: vor den Spiel-Hotkeys
    window.addEventListener('keydown', onKey, true)
    return () => {
      openViewers--
      window.removeEventListener('keydown', onKey, true)
    }
  }, [viewer, onClose])

  useEffect(() => {
    if (!minimizable) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return
      const t = e.target as HTMLElement
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) return
      e.preventDefault()
      setMinimized((m) => !m)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [minimizable])

  if (minimizable && minimized) {
    return (
      <motion.div
        className="glass fixed bottom-[200px] left-1/2 z-50 flex max-w-[min(640px,90vw)] -translate-x-1/2 items-center gap-3 rounded-xl px-4 py-2 shadow-2xl ring-1 ring-gold-400/50"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <div className="min-w-0 truncate text-sm font-semibold text-gold-300">{title ?? 'Dialog'}</div>
        <button className="btn-primary shrink-0 !px-3 !py-1 !text-xs" onClick={() => setMinimized(false)} title="Dialog wieder öffnen (Tab)">
          Dialog öffnen
        </button>
      </motion.div>
    )
  }

  return (
    <AnimatePresence>
      <motion.div
        className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/70 p-6 backdrop-blur-sm"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onMouseDown={(e) => {
          if (e.target === e.currentTarget && closable) onClose?.()
        }}
      >
        <motion.div
          className={`glass flex max-h-[88vh] w-full flex-col rounded-2xl shadow-2xl ${wide ? 'max-w-6xl' : 'max-w-xl'}`}
          initial={{ scale: 0.96, y: 10 }}
          animate={{ scale: 1, y: 0 }}
          transition={{ type: 'spring', stiffness: 420, damping: 32 }}
        >
          {(title || (closable && onClose) || minimizable) && (
            <div className="flex items-center justify-between gap-3 border-b border-white/10 px-5 py-3">
              <div className="font-display text-lg font-semibold tracking-wide text-gold-300">{title}</div>
              <div className="flex shrink-0 items-center gap-1">
                {minimizable && (
                  <button className="rounded-md px-2 py-1 text-ink-300 hover:bg-white/10 hover:text-white" onClick={() => setMinimized(true)} title="Minimieren – Spielfeld ansehen (Tab)">
                    ▁
                  </button>
                )}
                {closable && onClose && (
                  <button className="rounded-md px-2 py-1 text-ink-300 hover:bg-white/10 hover:text-white" onClick={onClose}>
                    ✕
                  </button>
                )}
              </div>
            </div>
          )}
          <div className="min-h-0 flex-1 overflow-auto p-5 scrollbar-thin">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-white/10 px-5 py-3">{footer}</div>}
        </motion.div>
      </motion.div>
    </AnimatePresence>
  )
}
