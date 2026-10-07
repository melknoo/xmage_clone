// Gemeinsame Teile der Brett-Dialoge (PromptDialogs): Props, Titel, Ziffern-Tasten.
import { useEffect, useRef } from 'react'
import type { Card, Prompt } from '../../api/types'
import { viewerOpen } from '../../components/BoardModal'
import { Rich } from '../../lib/mana'
import { useUi } from '../../store/ui'
import type { BoardLayoutState } from '../layout'

export interface DialogProps {
  p: Prompt
  onHover: (c: Card | null) => void
  layout: BoardLayoutState
}

/** Engine-Titel in Originalschreibung (Plex, BoardModal titleVariant 'engine') */
export function EngineTitle({ p }: { p: Prompt }) {
  return <Rich segs={p.message} />
}

const isTyping = (t: EventTarget | null) => {
  const el = t as HTMLElement | null
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)
}

/**
 * Ziffern 1..9 waehlen eine Option (index 0..8). Schweigt bei minimiertem Dialog, offenem Ansichts-Dialog
 * (Pause, Zonen) und in Eingabefeldern.
 */
export function useDigitKeys(count: number, onDigit: (index: number) => void) {
  const fn = useRef(onDigit)
  useEffect(() => {
    fn.current = onDigit
  })
  useEffect(() => {
    if (count <= 0) return
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.altKey || e.metaKey || e.repeat || e.defaultPrevented) return
      if (!/^[1-9]$/.test(e.key)) return
      if (isTyping(e.target) || viewerOpen() || useUi.getState().dialogMinimized) return
      const i = Number(e.key) - 1
      if (i >= count) return
      e.preventDefault()
      fn.current(i)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [count])
}
