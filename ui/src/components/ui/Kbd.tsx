import type { ReactNode } from 'react'

/**
 * Tastenhinweis (IBM Plex Mono 500 10.5, r2). In Buttons setzt Button selbst den passenden Ton.
 * soft: freistehend/Pille/Optionszeilen (#2a2824 auf fg-2) · dim: in Sekundaer-Buttons (fg-3) ·
 * primary: auf Ember- oder Karmin-Flaeche · mini: Modal-Kopf (10 px)
 */
export type KbdTone = 'soft' | 'dim' | 'primary' | 'mini'

const TONE: Record<KbdTone, string> = { soft: 'kbd-soft', dim: 'kbd-dim', primary: 'kbd-primary', mini: 'kbd-mini' }

export function Kbd({ children, tone = 'soft', className = '' }: { children: ReactNode; tone?: KbdTone; className?: string }) {
  return <kbd className={`${TONE[tone]} ${className}`}>{children}</kbd>
}
