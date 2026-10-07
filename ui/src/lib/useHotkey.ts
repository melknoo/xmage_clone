import { useEffect, useRef } from 'react'
import { modalOpen } from '../components/BoardModal'

/** Fokus in einem Eingabefeld? Dann gehoeren die Tasten dem Feld. */
export function isTypingTarget(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)
}

export interface HotkeyOptions {
  /** Standard true */
  enabled?: boolean
  /** auch bei offenem Dialog/Overlay ausloesen (Standard false: modalOpen() schaltet ab) */
  whileModal?: boolean
  /** auch im Eingabefeld ausloesen (Standard false) */
  inInputs?: boolean
}

/**
 * Globale Taste fuer Meta-Screens (z. B. Enter = Spiel starten). key = KeyboardEvent.key
 * ('Enter', 'Escape', ' ', 'F2' ...), auch mehrere. Ignoriert Eingabefelder, Strg/Alt/Meta, Tastenwiederholung,
 * schon behandelte Events und schweigt, solange ein Dialog/Overlay offen ist (modalOpen()).
 */
export function useHotkey(key: string | string[], fn: (e: KeyboardEvent) => void, opts: HotkeyOptions = {}): void {
  const { enabled = true, whileModal = false, inInputs = false } = opts
  const ref = useRef(fn)
  useEffect(() => {
    ref.current = fn
  })
  const keys = Array.isArray(key) ? key.join('|') : key
  useEffect(() => {
    if (!enabled) return
    const list = keys.split('|')
    const onKey = (e: KeyboardEvent) => {
      if (!list.includes(e.key) || e.repeat || e.ctrlKey || e.altKey || e.metaKey || e.defaultPrevented) return
      if (!inInputs && isTypingTarget(e.target)) return
      if (!whileModal && modalOpen()) return
      ref.current(e)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [keys, enabled, whileModal, inInputs])
}
