import type { CSSProperties, KeyboardEvent, ReactNode } from 'react'

/**
 * Tabellen aus Grid-Zeilen (Statistik, Verlauf, Einladungen, Lobby). Spalten per `columns`
 * (grid-template-columns), gleiche Spalten fuer Kopf und Zeilen.
 * Klassen fuer eigene Markups: tableHeadClass, tableRowClass, tableNumClass.
 */
export const tableHeadClass = 'tbl-head'
export const tableRowClass = 'tbl-row'
/** Zahl rechtsbuendig, Barlow 600 17, tabular */
export const tableNumClass = 'tbl-num'

/** Gedankenstrich fuer fehlende Werte */
export const DASH = '–'

/**
 * Null-Regel: Anzahlen zeigen 0 (zeroDash nicht verwenden). Quoten, Mittelwerte und XP zeigen bei
 * 0/fehlend "–" (nie "0 %" oder "+0 XP"). fmt formatiert nur echte Werte.
 */
export function zeroDash(v: number | null | undefined, fmt: (n: number) => string = String): string {
  if (v === null || v === undefined || !Number.isFinite(v) || v === 0) return DASH
  return fmt(v)
}

/** wie zeroDash, aber 0 bleibt sichtbar (nur fehlende Werte werden "–") */
export function missingDash(v: number | null | undefined, fmt: (n: number) => string = String): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return DASH
  return fmt(v)
}

export function TableHead({ columns, children, className = '', style }: { columns: string; children: ReactNode; className?: string; style?: CSSProperties }) {
  return (
    <div role="row" className={`${tableHeadClass} ${className}`} style={{ gridTemplateColumns: columns, ...style }}>
      {children}
    </div>
  )
}

export function TableRow({
  columns,
  children,
  open,
  onClick,
  className = '',
  style,
  testId,
}: {
  columns: string
  children: ReactNode
  /** aufgeklappte Zeile: bg-3 + Ember-Kante links */
  open?: boolean
  onClick?: () => void
  className?: string
  style?: CSSProperties
  testId?: string
}) {
  return (
    <div
      role="row"
      className={`${tableRowClass} ${onClick ? 'cursor-pointer' : ''} ${className}`}
      style={{ gridTemplateColumns: columns, ...style }}
      data-open={open ? 'true' : undefined}
      aria-expanded={onClick && open !== undefined ? open : undefined}
      tabIndex={onClick ? 0 : undefined}
      data-testid={testId}
      onClick={onClick}
      onKeyDown={
        onClick
          ? (e: KeyboardEvent) => {
              if (e.key === 'Enter') onClick()
            }
          : undefined
      }
    >
      {children}
    </div>
  )
}

/** Kopfzelle; num = rechtsbuendig */
export function Th({ children, num, className = '' }: { children?: ReactNode; num?: boolean; className?: string }) {
  return (
    <span role="columnheader" className={`${num ? 'text-right' : ''} ${className}`}>
      {children}
    </span>
  )
}

/**
 * Zahlenzelle. Mit `value` gilt die Null-Regel: zero='dash' (Quote, Mittelwert, XP) zeigt bei 0 "–" in fg-4,
 * zero='show' (Anzahl) zeigt 0. Mit children wird nur gestylt.
 */
export function Num({
  value,
  format,
  zero = 'show',
  children,
  className = '',
  style,
}: {
  value?: number | null
  format?: (n: number) => string
  zero?: 'show' | 'dash'
  children?: ReactNode
  className?: string
  style?: CSSProperties
}) {
  let text: ReactNode = children
  let dash = false
  if (children === undefined) {
    text = zero === 'dash' ? zeroDash(value, format) : missingDash(value, format)
    dash = text === DASH
  }
  return (
    <span role="cell" className={`${tableNumClass} ${dash ? 'tbl-zero' : ''} ${className}`} style={style}>
      {text}
    </span>
  )
}
