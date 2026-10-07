import { Fragment, type ReactNode } from 'react'
import type { RichSeg } from '../api/types'

// Mana-/Spielsymbole wie {2}{G}{T} via mana-font (https://mana.andrewgioia.com)

const SYMBOL_CLASS: Record<string, string> = {
  T: 'ms-tap',
  Q: 'ms-untap',
  E: 'ms-e',
  S: 'ms-s',
  C: 'ms-c',
  X: 'ms-x',
  Y: 'ms-y',
  Z: 'ms-z',
  P: 'ms-p',
  CHAOS: 'ms-chaos',
  PW: 'ms-planeswalker',
  '∞': 'ms-infinity',
  '½': 'ms-1-2',
}

function symbolClass(sym: string): string {
  const s = sym.toUpperCase()
  if (SYMBOL_CLASS[s]) return SYMBOL_CLASS[s]
  if (/^\d+$/.test(s)) return `ms-${s}`
  // Hybrid/Phyrexian: W/U, 2/W, G/P, W/U/P
  if (s.includes('/')) return `ms-${s.replace(/\//g, '').toLowerCase()}`
  return `ms-${s.toLowerCase()}`
}

/** flat (Standard): ohne ms-shadow – im Design sind Manasymbole flach, Abstand 2 px. flat={false} = alter Schatten */
export function ManaSymbol({ sym, size = 'md', flat = true }: { sym: string; size?: 'sm' | 'md' | 'lg'; flat?: boolean }) {
  const cls = symbolClass(sym)
  const sz = size === 'sm' ? 'text-[0.8em]' : size === 'lg' ? 'text-[1.3em]' : 'text-[0.95em]'
  const cost = cls === 'ms-tap' || cls === 'ms-untap' ? '' : 'ms-cost'
  return <i className={`ms ${cls} ${cost} ${flat ? '' : 'ms-shadow'} ${sz} mx-[1px] align-[-0.1em]`} title={`{${sym}}`} />
}

/** Ersetzt {X}-Symbole in Text durch Mana-Icons. */
export function withSymbols(text: string, keyPrefix = ''): ReactNode[] {
  const out: ReactNode[] = []
  const re = /\{([^}]+)\}/g
  let last = 0
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    out.push(<ManaSymbol key={`${keyPrefix}s${i++}`} sym={m[1]} />)
    last = m.index + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

export function ManaCost({ cost, size, flat = true }: { cost?: string; size?: 'sm' | 'md' | 'lg'; flat?: boolean }) {
  if (!cost) return null
  const syms = [...cost.matchAll(/\{([^}]+)\}/g)].map((m) => m[1])
  return (
    <span className="inline-flex items-center whitespace-nowrap">
      {syms.map((s, i) => (
        <ManaSymbol key={i} sym={s} size={size} flat={flat} />
      ))}
    </span>
  )
}

/** Regeltext aus XMage: einfache HTML-Tags entfernen, <br> -> Zeilenumbruch, Symbole rendern. Kein innerHTML. */
export function RulesText({ text }: { text: string }) {
  const clean = text
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/?(i|b|font|span|div|p)[^>]*>/gi, '')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&mdash;/g, '—')
    .replace(/&ndash;/g, '–')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
  return (
    <>
      {clean.split('\n').map((line, i) => (
        <Fragment key={i}>
          {i > 0 && <br />}
          {withSymbols(line, `l${i}`)}
        </Fragment>
      ))}
    </>
  )
}

/** Rich-Text-Segmente aus der Engine (Log/Prompts). */
export function Rich({ segs, onObject }: { segs?: RichSeg[]; onObject?: (id: string) => void }) {
  if (!segs) return null
  return (
    <>
      {segs.map((s, i) => {
        if (s.br) return <br key={i} />
        const content = withSymbols(s.text ?? '', `r${i}`)
        if (s.obj) {
          return (
            <span
              key={i}
              // nicht data-obj: das ist der Anker der Karten auf dem Tisch (FX, Skripte)
              data-ref={s.obj}
              className="cursor-help font-semibold text-fg-1 underline decoration-transparent underline-offset-2 transition-colors duration-1 hover:text-ember hover:decoration-ember"
              onMouseEnter={() => onObject?.(s.obj!)}
            >
              {content}
            </span>
          )
        }
        const cls = [s.i ? 'italic' : '', s.b ? 'font-semibold' : ''].join(' ')
        return (
          <span key={i} className={cls}>
            {content}
          </span>
        )
      })}
    </>
  )
}

export const COLOR_NAMES: Record<string, string> = { W: 'Weiß', U: 'Blau', B: 'Schwarz', R: 'Rot', G: 'Grün' }

/** Farbidentitaet als Manasymbole; flach (ohne Schatten) wie im Design, flat={false} = alter Schatten */
export function ColorPips({ colors, size = 'md', flat = true }: { colors?: string; size?: 'sm' | 'md' | 'lg'; flat?: boolean }) {
  if (!colors) return <ManaSymbol sym="C" size={size} flat={flat} />
  return (
    <span className="inline-flex">
      {colors.split('').map((c) => (
        <ManaSymbol key={c} sym={c} size={size} flat={flat} />
      ))}
    </span>
  )
}
