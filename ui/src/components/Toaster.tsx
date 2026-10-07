import { AnimatePresence, motion } from 'motion/react'
import { Icon, type IconName } from '../lib/icons'
import { Rich } from '../lib/mana'
import { enter } from '../lib/motion'
import { useUi, type ToastKind, type UiToast } from '../store/ui'

const KIND_ICON: Record<ToastKind, IconName> = { info: 'info', success: 'success', error: 'error' }
const KIND_COLOR: Record<ToastKind, string> = { info: 'text-block', success: 'text-chosen', error: 'text-attack' }

/**
 * Toasts oben mittig, ueber allem (auch ueber dem Brett). Einmal in App.tsx einhaengen, oberhalb des
 * Spiel-Zweigs. Inhalt kommt aus store/ui.ts (pushToast).
 */
export function Toaster() {
  const toasts = useUi((s) => s.toasts)
  return (
    <div
      className="pointer-events-none fixed left-1/2 top-[14px] z-[80] flex w-max max-w-[min(640px,calc(100vw-32px))] -translate-x-1/2 flex-col items-center gap-2"
      aria-live="polite"
      data-testid="toaster"
    >
      <AnimatePresence initial={false}>
        {toasts.map((t) => (
          <ToastItem key={t.id} t={t} />
        ))}
      </AnimatePresence>
    </div>
  )
}

function ToastItem({ t }: { t: UiToast }) {
  const dismiss = useUi((s) => s.dismissToast)
  return (
    <motion.div
      layout="position"
      {...enter}
      className={`pointer-events-auto max-w-full cursor-pointer ${t.kind === 'error' ? 'toast-error' : 'toast'}`}
      role={t.kind === 'error' ? 'alert' : 'status'}
      data-testid="toast"
      data-kind={t.kind}
      onClick={() => dismiss(t.id)}
    >
      <Icon name={t.icon ?? KIND_ICON[t.kind]} size={16} className={KIND_COLOR[t.kind]} />
      <span className="min-w-0 flex-1 break-words">{t.rich ? <Rich segs={t.rich} /> : t.text}</span>
      <button
        type="button"
        className="flex shrink-0 rounded-xs p-1 text-fg-4 transition-colors duration-1 hover:text-fg-1"
        title="Schließen"
        aria-label="Schließen"
        onClick={(e) => {
          e.stopPropagation()
          dismiss(t.id)
        }}
      >
        <Icon name="close" size={14} />
      </button>
    </motion.div>
  )
}
