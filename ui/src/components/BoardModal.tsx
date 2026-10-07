import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '../lib/icons'
import { DUR, EASE_OUT, enter } from '../lib/motion'
import { useUi } from '../store/ui'

/* ------------------------------------------------------------------------------------------------
 * Gemeinsame Zaehler fuer alle Dialoge (BoardModal, Overlay)
 * ---------------------------------------------------------------------------------------------- */

/** offene Ansichts-Dialoge (Friedhof, Exil, Bibliothek, Menue); solange einer offen ist, gehen Spiel-Hotkeys nicht durch */
let openViewers = 0
/** offene, nicht minimierte Dialoge/Overlays */
let openModals = 0

/** true, solange ein Ansichts-Dialog offen (und nicht minimiert) ist */
export const viewerOpen = () => openViewers > 0
/** true, solange irgendein Dialog oder Overlay offen (und nicht minimiert) ist; useHotkey schweigt dann */
export const modalOpen = () => openModals > 0

/** intern (ui/Overlay.tsx): Dialog zaehlen; liefert das Aufraeumen */
export function trackDialog(viewer: boolean): () => void {
  openModals++
  if (viewer) openViewers++
  return () => {
    openModals--
    if (viewer) openViewers--
  }
}

/* Tasten gehen nur an den zuletzt geoeffneten Brett-Dialog */
const keyStack: string[] = []
const isTop = (id: string) => keyStack[keyStack.length - 1] === id

/* ------------------------------------------------------------------------------------------------
 * Portal-Ziel in der linken Brettspalte
 * ---------------------------------------------------------------------------------------------- */

let rootEl: HTMLElement | null = null
const rootListeners = new Set<() => void>()
const subscribeRoot = (cb: () => void) => {
  rootListeners.add(cb)
  return () => rootListeners.delete(cb)
}
const getRoot = () => rootEl
function setRoot(el: HTMLElement | null) {
  if (rootEl === el) return
  rootEl = el
  rootListeners.forEach((l) => l())
}

/**
 * Portal-Ziel fuer BoardModal. GameScreen rendert es einmal als absolut positionierte Ebene in der linken
 * Brettspalte (die Spalte braucht position: relative). Kopfleiste und Seitenleiste bleiben dann bedienbar.
 * Ohne gemountetes Ziel faellt BoardModal auf eine fixe Vollbild-Ebene zurueck.
 */
export function BoardModalRoot({ className = '' }: { className?: string }) {
  return <div ref={setRoot} data-board-modal-root="" className={`pointer-events-none absolute inset-0 z-40 ${className}`} />
}

/* ------------------------------------------------------------------------------------------------
 * BoardModal
 * ---------------------------------------------------------------------------------------------- */

export interface BoardModalProps {
  /** eigenes deutsches Label im Kopf (Versalien per CSS), z. B. "Fähigkeit wählen"; auch Text der Pille */
  label: string
  /** danger: Label in Karmin (zerstoererische Dialoge) */
  labelTone?: 'default' | 'danger'
  /** Titel: Engine-Text (Originalschreibung) oder eigener Titel */
  title: ReactNode
  /** engine = Plex 16/500 (Standard), own = Barlow 600 26 */
  titleVariant?: 'engine' | 'own'
  /** Panelbreite in px (game/layout.ts -> modal.*); Standard 560 */
  width?: number
  /** Inhalt; der Body hat kein eigenes Padding */
  children?: ReactNode
  /** Buttons rechts, Abstand 8 */
  footer?: ReactNode
  onClose?: () => void
  /** Scrim-Klick und Esc schliessen (onClose); Standard true */
  closable?: boolean
  /** Tab minimiert zur Pille ueber der Aktionsleiste */
  minimizable?: boolean
  /** Text der Pille statt "Dialog öffnen · {label}" (z. B. "Ergebnis anzeigen") */
  minimizedLabel?: string
  /**
   * kontrolliert minimiert (z. B. Spielende: "Tisch ansehen"); ohne Angabe verwaltet BoardModal den Zustand selbst.
   * Kontrolliert und minimiert oeffnet Tab bzw. Pillen-Klick wieder, auch ohne minimizable.
   */
  minimized?: boolean
  onMinimizedChange?: (minimized: boolean) => void
  /** reine Ansicht (Zonen, Menue): zaehlt fuer viewerOpen(), Esc wird nie an das Spiel durchgereicht */
  viewer?: boolean
  /** Leertaste/Enter (Capture-Phase, nicht in Eingabefeldern) */
  onSpace?: () => void
  /** Esc; Standard: onClose (wenn closable) */
  onEsc?: () => void
  /** data-testid der Wurzel; Standard 'game-modal' */
  testId?: string
}

const isTyping = (t: EventTarget | null) => {
  const el = t as HTMLElement | null
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)
}

export function BoardModal({
  label,
  labelTone = 'default',
  title,
  titleVariant = 'engine',
  width = 560,
  children,
  footer,
  onClose,
  closable = true,
  minimizable = false,
  minimizedLabel,
  minimized: minimizedProp,
  onMinimizedChange,
  viewer = false,
  onSpace,
  onEsc,
  testId,
}: BoardModalProps) {
  const id = useId()
  const root = useSyncExternalStore(subscribeRoot, getRoot, () => null)
  const [minimizedOwn, setMinimizedOwn] = useState(false)
  const controlled = minimizedProp !== undefined
  const minimized = controlled ? minimizedProp : minimizedOwn
  const setDialogMinimized = useUi((s) => s.setDialogMinimized)

  // aktuelle Handler ohne Neu-Registrierung der Tastatur-Listener
  const h = useRef({ onSpace, onEsc, onClose, closable, minimizable, viewer, minimized, controlled, onMinimizedChange })
  useLayoutEffect(() => {
    h.current = { onSpace, onEsc, onClose, closable, minimizable, viewer, minimized, controlled, onMinimizedChange }
  })
  const setMinimized = (m: boolean) => {
    const c = h.current
    if (!c.controlled) setMinimizedOwn(m)
    c.onMinimizedChange?.(m)
  }

  // Stapel fuer Tasten
  useEffect(() => {
    keyStack.push(id)
    return () => {
      const i = keyStack.lastIndexOf(id)
      if (i >= 0) keyStack.splice(i, 1)
    }
  }, [id])

  // Zaehler nur, solange nicht minimiert
  useEffect(() => {
    if (minimized) return
    return trackDialog(viewer)
  }, [minimized, viewer])

  // Minimiert-Zustand fuer die PromptBar
  useEffect(() => {
    setDialogMinimized(minimized)
  }, [minimized, setDialogMinimized])
  useEffect(() => () => setDialogMinimized(false), [setDialogMinimized])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!isTop(id)) return
      const c = h.current
      if (e.key === 'Tab') {
        // minimiert: Tab oeffnet wieder (auch kontrolliert ohne minimizable); offen: nur minimizable minimiert
        const can = c.minimized ? c.minimizable || c.controlled : c.minimizable
        if (!can || isTyping(e.target)) return
        e.preventDefault()
        e.stopImmediatePropagation()
        setMinimized(!c.minimized)
        return
      }
      if (c.minimized) return
      if (e.key === 'Escape') {
        const fn = c.onEsc ?? (c.closable ? c.onClose : undefined)
        if (fn || c.viewer) {
          e.preventDefault()
          e.stopImmediatePropagation()
          fn?.()
        }
        return
      }
      if ((e.key === ' ' || e.key === 'Enter') && c.onSpace && !isTyping(e.target)) {
        e.preventDefault()
        e.stopImmediatePropagation()
        if (!e.repeat) c.onSpace()
      }
    }
    // Capture-Phase: vor den Spiel-Hotkeys
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [id])

  const pos = root ? 'absolute' : 'fixed'
  const rootTestId = testId ?? 'game-modal'

  const body = minimized ? (
    <motion.button
      key="pill"
      type="button"
      {...enter}
      className={`pill pointer-events-auto ${pos} left-1/2 z-50 max-w-[calc(100%-32px)]`}
      style={{ bottom: 'calc(var(--prompt-h, 62px) + 14px)', x: '-50%' }}
      data-testid="modal-pill"
      title="Dialog wieder öffnen (Tab)"
      onClick={() => setMinimized(false)}
    >
      <span className="block h-[7px] w-[7px] shrink-0 bg-ember" aria-hidden />
      <span className="min-w-0 truncate">{minimizedLabel ?? `Dialog öffnen · ${label}`}</span>
      <kbd className="kbd-soft">Tab</kbd>
    </motion.button>
  ) : (
    <motion.div
      key="modal"
      className={`scrim pointer-events-auto ${pos} inset-0 z-50 flex items-center justify-center p-6`}
      data-testid={rootTestId}
      data-modal=""
      data-viewer={viewer ? '' : undefined}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: DUR.d3, ease: EASE_OUT }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && closable) onClose?.()
      }}
    >
      <motion.div
        {...enter}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="floating flex max-h-full max-w-full flex-col"
        style={{ width }}
      >
        <div className="flex items-start gap-3 border-b border-line-2 px-5 pb-[14px] pt-[18px]">
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <span className="label" style={labelTone === 'danger' ? { color: 'var(--color-attack)' } : undefined}>{label}</span>
            <div className={titleVariant === 'own' ? 'font-display text-[26px] font-semibold leading-none text-fg-1' : 'text-body-l font-medium text-fg-1'}>{title}</div>
          </div>
          {minimizable && (
            <button
              type="button"
              className="flex h-7 shrink-0 items-center gap-1.5 rounded-sm px-2 font-display text-[12px] font-semibold uppercase tracking-[.08em] text-fg-3 transition-colors duration-1 hover:bg-bg-4 hover:text-fg-1"
              title="Minimieren – Spielfeld ansehen (Tab)"
              onClick={() => setMinimized(true)}
            >
              <Icon name="minimize" size={14} />
              Minimieren
              <kbd className="kbd-mini">Tab</kbd>
            </button>
          )}
        </div>
        <div className="min-h-0 flex-1 overflow-auto scrollbar-thin">{children}</div>
        {footer && <div className="flex flex-wrap items-center justify-end gap-2 px-5 pb-[18px] pt-4">{footer}</div>}
      </motion.div>
    </motion.div>
  )

  const content = <AnimatePresence mode="wait">{body}</AnimatePresence>
  return root ? createPortal(content, root) : content
}
