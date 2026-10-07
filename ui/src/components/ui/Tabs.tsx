import type { CSSProperties, ReactNode } from 'react'
import { Badge } from './Badge'

export interface TabItem<T extends string = string> {
  id: T
  label: ReactNode
  badge?: number
  testId?: string
  title?: string
}

/** md: Seiten-Tabs (Barlow 15, gap 24, unten 10) · sm: Seitenleiste/Log (13, gap 18, unten 8) */
export type TabsSize = 'md' | 'sm'

const LIST_STYLE: Record<TabsSize, CSSProperties | undefined> = { md: undefined, sm: { gap: 18 } }
const TAB_STYLE: Record<TabsSize, CSSProperties | undefined> = { md: undefined, sm: { fontSize: 13, paddingBottom: 8 } }

/** Seiten-Tabs: aktiv fg-1 + Ember-Unterstrich, inaktiv #7d786f. Labels normal schreiben. */
export function Tabs<T extends string>({
  items,
  value,
  onChange,
  size = 'md',
  className = '',
  style,
}: {
  items: TabItem<T>[]
  value: T
  onChange: (id: T) => void
  size?: TabsSize
  /** Klassen der Tab-Leiste; im Overlay-Kopf z. B. "px-5 pt-4" (Linie dann ueber die volle Breite) */
  className?: string
  style?: CSSProperties
}) {
  return (
    <div role="tablist" className={`tab-list ${className}`} style={style ? { ...LIST_STYLE[size], ...style } : LIST_STYLE[size]}>
      {items.map((it) => (
        <button
          key={it.id}
          type="button"
          role="tab"
          aria-selected={it.id === value}
          className="tab"
          style={TAB_STYLE[size]}
          title={it.title}
          data-testid={it.testId}
          onClick={() => onChange(it.id)}
        >
          {it.label}
          {it.badge !== undefined && <Badge count={it.badge} variant="tab" />}
        </button>
      ))}
    </div>
  )
}
