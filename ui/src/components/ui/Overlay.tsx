import { motion } from 'motion/react'
import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { DUR, EASE_OUT, enter } from '../../lib/motion'
import { trackDialog } from '../BoardModal'

/** Abstaende des Meta-Overlays zum <main>-Rand: ab 1440 px 64/48, darunter 28/22 */
export const OVERLAY_INSET_FULL = { x: 64, y: 48 } as const
export const OVERLAY_INSET_COMPACT = { x: 28, y: 22 } as const

function useAutoInset(): { x: number; y: number } {
  const [wide, setWide] = useState(() => typeof window === 'undefined' || window.innerWidth >= 1440)
  useEffect(() => {
    const on = () => setWide(window.innerWidth >= 1440)
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [])
  return wide ? OVERLAY_INSET_FULL : OVERLAY_INSET_COMPACT
}

/**
 * Meta-Overlay (Deck-Auswahl, Import, Loeschen-Bestaetigung). Wird INNERHALB von <main> gerendert
 * (main braucht position: relative), damit die Navigation nie unter dem Scrim liegt.
 * Ohne width: Panel mit Abstand `inset` zum Rand; mit width: zentriert in dieser Breite.
 * Esc und Scrim-Klick schliessen.
 * variant 'confirm': Bestaetigung (ohne Trennlinien, Titel 24, Inhalt direkt unter dem Titel, liegt mit z 22/23 ueber
 * einem anderen Overlay). scrim=false: kein Abdunkeln (darunter liegt schon ein Scrim), nur unsichtbarer Klickfang.
 */
export function Overlay({
  label,
  labelTone = 'default',
  title,
  onClose,
  footer,
  footerHint,
  headerRight,
  headerBelow,
  children,
  inset,
  width,
  testId,
  variant = 'default',
  scrim = true,
}: {
  /** Label im Kopf (Barlow 600 13 .12em fg-3, Versalien per CSS) */
  label: ReactNode
  /** danger: Label in Karmin (Loeschen-Bestaetigung) */
  labelTone?: 'default' | 'danger'
  /** eigener Titel, Barlow 600 26 */
  title: ReactNode
  onClose: () => void
  /** Buttons rechts im Fuss (Abstand 8) */
  footer?: ReactNode
  /** Hinweis links im Fuss (13 fg-3) */
  footerHint?: ReactNode
  /** rechts im Kopf, z. B. Suche + "Zufällig" */
  headerRight?: ReactNode
  /**
   * Zeile direkt unter dem Kopf, z. B. `<Tabs className="px-5 pt-4" …/>` (Deck-Auswahl). Der Kopf verliert dann
   * seine Trennlinie und sein unteres Padding; die Linie kommt aus dieser Zeile.
   */
  headerBelow?: ReactNode
  children: ReactNode
  inset?: { x: number; y: number }
  width?: number
  testId?: string
  variant?: 'default' | 'confirm'
  scrim?: boolean
}) {
  const auto = useAutoInset()
  const ins = inset ?? auto
  const closeRef = useRef(onClose)
  useEffect(() => {
    closeRef.current = onClose
  })

  useEffect(() => trackDialog(false), [])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopImmediatePropagation()
      closeRef.current()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [])

  const panelPos: CSSProperties = width
    ? { left: '50%', top: '50%', width, maxWidth: `calc(100% - ${2 * ins.x}px)`, maxHeight: `calc(100% - ${2 * ins.y}px)`, x: '-50%', y: '-50%' } as CSSProperties
    : { left: ins.x, right: ins.x, top: ins.y, bottom: ins.y }

  const confirm = variant === 'confirm'
  const titleId = useId()

  return (
    <>
      <motion.div
        className={`absolute inset-0 ${confirm ? 'z-[22]' : 'z-20'} ${scrim ? 'scrim' : ''}`}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: DUR.d3, ease: EASE_OUT }}
        onMouseDown={(e) => {
          if (e.target === e.currentTarget) onClose()
        }}
      />
      <motion.div
        {...(width ? { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 }, transition: enter.transition } : enter)}
        role={confirm ? 'alertdialog' : 'dialog'}
        aria-modal="true"
        aria-labelledby={titleId}
        className={`floating absolute ${confirm ? 'z-[23]' : 'z-[21]'} flex flex-col`}
        style={panelPos}
        data-testid={testId}
      >
        {confirm ? (
          <>
            <div className="flex flex-col gap-2.5 p-5">
              <span className="label" style={labelTone === 'danger' ? { color: 'var(--color-attack)' } : undefined}>{label}</span>
              <span id={titleId} className="font-display text-[24px] font-semibold leading-[1.1] text-fg-1">
                {title}
              </span>
              {children}
            </div>
            {(footer || footerHint) && (
              <div className="flex items-center gap-2 px-5 pb-[18px]">
                {footerHint && <span className="min-w-0 text-[13px] text-fg-3">{footerHint}</span>}
                <span className="flex-1" />
                {footer}
              </div>
            )}
          </>
        ) : (
          <>
            <div className={`flex items-center gap-4 px-5 pt-[18px] ${headerBelow ? '' : 'border-b border-line-2 pb-[14px]'}`}>
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <span className="label" style={labelTone === 'danger' ? { color: 'var(--color-attack)' } : undefined}>{label}</span>
                <div id={titleId} className="font-display text-[26px] font-semibold leading-none text-fg-1">
                  {title}
                </div>
              </div>
              {headerRight}
            </div>
            {headerBelow}
            <div className="min-h-0 flex-1 overflow-auto scrollbar-thin">{children}</div>
            {(footer || footerHint) && (
              <div className="flex items-center gap-2 border-t border-line-2 px-5 pb-4 pt-[14px]">
                {footerHint && <span className="min-w-0 text-[13px] text-fg-3">{footerHint}</span>}
                <span className="flex-1" />
                {footer}
              </div>
            )}
          </>
        )}
      </motion.div>
    </>
  )
}
