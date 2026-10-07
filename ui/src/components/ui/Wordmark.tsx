/**
 * Wortmarke "MAGELITE" (Barlow Condensed 700, .08em, Ember). Groessen: 19 Kopfleiste, 34 Laden, 46 Login.
 * short: "ML" fuer die Navigation (20, .06em).
 */
export function Wordmark({ size, short = false, className = '' }: { size: number; short?: boolean; className?: string }) {
  return (
    <span className={`wordmark ${className}`} style={{ fontSize: size, letterSpacing: short ? '.06em' : undefined }}>
      {short ? 'ML' : 'MageLite'}
    </span>
  )
}
