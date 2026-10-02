import { AnimatePresence, motion } from 'motion/react'
import type { ReactNode } from 'react'

export function Modal({
  title,
  children,
  onClose,
  footer,
  wide,
  closable = true,
}: {
  title?: ReactNode
  children: ReactNode
  onClose?: () => void
  footer?: ReactNode
  wide?: boolean
  closable?: boolean
}) {
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
          {(title || (closable && onClose)) && (
            <div className="flex items-center justify-between border-b border-white/10 px-5 py-3">
              <div className="font-display text-lg font-semibold tracking-wide text-gold-300">{title}</div>
              {closable && onClose && (
                <button className="rounded-md px-2 py-1 text-ink-300 hover:bg-white/10 hover:text-white" onClick={onClose}>
                  ✕
                </button>
              )}
            </div>
          )}
          <div className="min-h-0 flex-1 overflow-auto p-5 scrollbar-thin">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-white/10 px-5 py-3">{footer}</div>}
        </motion.div>
      </motion.div>
    </AnimatePresence>
  )
}
