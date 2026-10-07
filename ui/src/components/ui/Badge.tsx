/**
 * Zaehl-Badge in Ember. nav: min 18x18, Barlow 700 12, mit 2-px-Freistellung (fuer Icons, absolut
 * right -12 / top -7 positionieren); tab: min 16x16, 11 px, ohne Freistellung. 0 oder weniger: nichts.
 */
export function Badge({ count, variant = 'nav', max = 99, className = '', title }: { count: number; variant?: 'nav' | 'tab'; max?: number; className?: string; title?: string }) {
  if (!(count > 0)) return null
  return (
    <span className={`${variant === 'tab' ? 'badge-tab' : 'badge'} ${className}`} title={title} data-testid="badge">
      {count > max ? `${max}+` : count}
    </span>
  )
}
