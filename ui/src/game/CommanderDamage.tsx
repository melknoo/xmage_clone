import { ProgressBar } from '../components/ui'
import { Icon } from '../lib/icons'
import { shortName } from './format'

/** Commander-Schaden ab 21 ist toedlich */
export const LETHAL_COMMANDER_DAMAGE = 21

function entries(dmg: Record<string, number> | undefined): [string, number][] {
  if (!dmg) return []
  return Object.entries(dmg)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1])
}

/**
 * Erlittener Commander-Schaden (Infospalte): Abschnitt mit Label, je Quelle Zeile + 3-px-Balken (Karmin, n/21);
 * ab 21 Chip "21 tödlich" statt Zahl. Ohne Schaden nichts.
 */
export function CommanderDamage({ dmg }: { dmg?: Record<string, number> }) {
  const list = entries(dmg)
  if (list.length === 0) return null
  return (
    <div className="section-rule flex shrink-0 flex-col gap-[7px] pt-2.5">
      <span className="label" style={{ fontSize: 12, letterSpacing: '.14em' }}>
        Commander-Schaden
      </span>
      {list.map(([name, n], i) => {
        const lethal = n >= LETHAL_COMMANDER_DAMAGE
        return (
          <div key={name} className="flex flex-col gap-[7px]" style={i > 0 ? { marginTop: 6 } : undefined} title={`Commander-Schaden von ${name}: ${n}`}>
            <div className="flex min-w-0 items-center gap-2 text-[13px] text-fg-1">
              <Icon name="cmdDamage" size={13} className="text-attack" />
              <span className="min-w-0 truncate">{shortName(name)}</span>
              <span className="flex-1" />
              {lethal ? (
                <span className="chip-fill-attack shrink-0" style={{ padding: '3px 5px', fontSize: 15 }}>
                  21 tödlich
                </span>
              ) : (
                <span className="num shrink-0 text-[18px] leading-none">
                  {n}
                  <span className="text-fg-4"> / {LETHAL_COMMANDER_DAMAGE}</span>
                </span>
              )}
            </div>
            <ProgressBar value={Math.min(n, LETHAL_COMMANDER_DAMAGE)} max={LETHAL_COMMANDER_DAMAGE} height={3} tone="attack" />
          </div>
        )
      })}
    </div>
  )
}

/** Kurzform fuer Gegner-Pods (Zonenzeile): Schwert + Zahl je Quelle, Name im Tooltip. */
export function CommanderDamageInline({ dmg }: { dmg?: Record<string, number> }) {
  const list = entries(dmg)
  if (list.length === 0) return null
  return (
    <>
      {list.map(([name, n]) => (
        <span
          key={name}
          className="flex shrink-0 items-center gap-1 text-attack"
          title={`Commander-Schaden von ${name}: ${n} / ${LETHAL_COMMANDER_DAMAGE}`}
        >
          <Icon name="cmdDamage" size={13} />
          {n}
        </span>
      ))}
    </>
  )
}
