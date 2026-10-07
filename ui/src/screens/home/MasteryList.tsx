import { cardImageUrl } from '../../api/client'
import type { StoredDeck } from '../../api/decks'
import { Button, ProgressBar } from '../../components/ui'
import { useCatalogStore } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { MASTERY_MAX_LEVEL, mastery } from '../../lib/mastery'
import { useNav } from '../../store/nav'
import { ArtThumb, SectionHead } from './RecentGames'

export interface MasteryListProps {
  /** hoechstens so viele Decks (nach Meisterschafts-XP absteigend) */
  limit?: number
}

function artOf(d: StoredDeck): string | null {
  if (d.commanderSet && d.commanderNum) return cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' })
  const first = d.commanders[0]
  return first ? cardImageUrl({ name: first }, { size: 'art_crop' }) : null
}

/**
 * "Deck-Meisterschaft" auf dem Held: eigene Decks mit Kunst 64x40, Name, "{xp} / {next} XP", 3-px-Balken (gelb)
 * und Stufe mit Stern. Leer: Erklaerung + "Deck importieren" (oeffnet den Import auf dem Decks-Screen).
 * Liest den Deck-Katalog aus dem Cache (QuickStartCard laedt ihn beim Mounten neu).
 */
export function MasteryList({ limit = 5 }: MasteryListProps) {
  const go = useNav((s) => s.go)
  const decks = useCatalogStore((s) => s.decks)
  const loaded = useCatalogStore((s) => s.loaded)
  const top = [...decks].sort((a, b) => (b.masteryXp ?? 0) - (a.masteryXp ?? 0)).slice(0, limit)

  return (
    <div className="flex min-h-0 flex-col overflow-hidden" data-testid="mastery-list">
      <SectionHead label="Deck-Meisterschaft" link="Decks" onLink={() => go('decks')} testId="mastery-list-link" />
      {loaded && decks.length === 0 && (
        <div className="flex flex-col items-start gap-3.5 py-[22px] text-[14px] leading-[1.5] text-fg-3">
          <span>Jedes eigene Deck steigt mit seinen Partien in Stufen auf. Zuerst ein Deck importieren.</span>
          <Button variant="secondary" icon="import" onClick={() => go('decks', { decksOverlay: 'import' })} testId="home-import">
            Deck importieren
          </Button>
        </div>
      )}
      {top.map((d) => {
        const m = mastery(d.masteryXp)
        const level = d.masteryLevel ?? m.level
        const next = d.masteryNext ?? m.threshold
        const max = m.max || level >= MASTERY_MAX_LEVEL
        const text = m.xp <= 0 ? 'noch nicht gespielt' : max ? `${m.xp} XP` : `${m.xp} / ${next} XP`
        return (
          <div
            key={d.id}
            className="grid items-center gap-4 border-b border-line-board py-3"
            style={{ gridTemplateColumns: '64px minmax(0,1fr) 56px' }}
            data-testid="mastery-row"
          >
            <ArtThumb src={artOf(d)} title={d.commanders.join(' & ')} />
            <div className="flex min-w-0 flex-col gap-[7px]">
              <div className="flex min-w-0 justify-between gap-2">
                <span className="truncate text-[15px] font-semibold leading-[1.3] text-fg-1">{d.name}</span>
                <span className="flex-none whitespace-nowrap text-[12.5px] leading-[1.4] text-fg-3 tabular-nums">{text}</span>
              </div>
              <ProgressBar value={max ? 1 : m.xp} max={max ? 1 : Math.max(1, next)} height={3} tone="target" />
            </div>
            <span className="num flex items-center justify-end gap-1 text-[22px] leading-none text-target" title={`Stufe ${level}`}>
              <Icon name="mastery" size={14} />
              {level}
            </span>
          </div>
        )
      })}
    </div>
  )
}
