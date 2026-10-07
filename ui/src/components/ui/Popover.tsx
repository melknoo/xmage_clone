import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react'
import { enter } from '../../lib/motion'

/**
 * Popover unter seinem Ausloeser (bg-3, r4, Padding 8, Schatten popover). Der Elternknoten muss
 * position: relative haben und Ausloeser + Popover enthalten (Klicks dort gelten als "innen").
 * Esc und Klick ausserhalb schliessen. Breiten: Einladen 300, Mitglieder-Menue 220.
 * variant popover: bg-3, r4, Padding 8 (Einladen) · menu: bg-4, r3, Padding 6 (Mitglieder-Menue).
 */
export type PopoverVariant = 'popover' | 'menu'

export function Popover({
  open,
  onClose,
  width,
  title,
  children,
  align = 'left',
  className = '',
  style,
  testId,
  variant = 'popover',
  offset = 8,
}: {
  open: boolean
  onClose: () => void
  width: number
  /** Kopf (Barlow 600 12 .12em fg-3, Versalien per CSS) */
  title?: ReactNode
  children: ReactNode
  /** an welcher Kante des Ausloesers ausrichten */
  align?: 'left' | 'right' | 'center'
  className?: string
  style?: CSSProperties
  testId?: string
  variant?: PopoverVariant
  /** Abstand unter dem Ausloeser in px (Standard 8) */
  offset?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  useEffect(() => {
    closeRef.current = onClose
  })

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      const el = ref.current
      const scope = el?.parentElement ?? el
      if (scope && e.target instanceof Node && scope.contains(e.target)) return
      closeRef.current()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopImmediatePropagation()
      closeRef.current()
    }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open])

  const pos: CSSProperties =
    align === 'right' ? { right: 0 } : align === 'center' ? { left: '50%', x: '-50%' } as CSSProperties : { left: 0 }

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          ref={ref}
          {...enter}
          role="dialog"
          className={`${variant === 'menu' ? 'menu-popover' : 'popover'} absolute z-30 ${className}`}
          style={{ width, top: `calc(100% + ${offset}px)`, ...pos, ...style }}
          data-testid={testId}
        >
          {title && <div className="px-2 pb-2.5 pt-2 font-display text-[12px] font-semibold uppercase leading-none tracking-[.12em] text-fg-3">{title}</div>}
          {children}
        </motion.div>
      )}
    </AnimatePresence>
  )
}
