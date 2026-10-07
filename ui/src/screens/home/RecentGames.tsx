import { useEffect, useState, type ReactNode } from 'react'
import { cardImageUrl } from '../../api/client'
import { statsApi, type HistoryGame } from '../../api/stats'
import { useCatalogStore } from '../../decks/catalog'
import { DASH, duration, placeColor, relDay, xp } from '../../lib/format'
import { useNav } from '../../store/nav'

export interface RecentGamesProps {
  /** wie viele Partien (Prototyp: 3, kompakt 2) */
  limit: number
  /** Commander-Kunst je Zeile (lokaler Held) */
  withArt?: boolean
  /** server: Meta-Zeile "Online mit {n} · ..." bzw. "Allein · ..." */
  variant?: 'local' | 'server'
}

/** Kopf einer Held-Liste: Label + Ember-Link rechts, Linie line-3 */
export function SectionHead({ label, link, onLink, testId }: { label: string; link?: string; onLink?: () => void; testId?: string }) {
  return (
    <div className="flex items-baseline justify-between border-b border-line-3 pb-3">
      <span className="font-display text-[14px] font-semibold uppercase leading-none tracking-[.14em] text-fg-3">{label}</span>
      {link && (
        <button type="button" className="cursor-pointer text-[13px] leading-none text-ember hover:text-ember-hover" onClick={onLink} data-testid={testId}>
          {link}
        </button>
      )}
    </div>
  )
}

/** Commander-Kunst 64x40 (r2, bg-4 als Platzhalter) */
export function ArtThumb({ src, title }: { src: string | null; title?: string }) {
  return (
    <div
      className="h-10 w-16 flex-none overflow-hidden rounded-xs bg-bg-4"
      title={title}
      style={src ? { backgroundImage: `url("${src}")`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
    />
  )
}

/** Hinweiszeile in leeren Held-Listen */
export function ListNote({ children }: { children: ReactNode }) {
  return <div className="py-[22px] text-[14px] leading-[1.5] text-fg-3">{children}</div>
}

/** Dauer im Held: "42 min" bzw. "1:31 h" */
function heldDuration(ms: number): string {
  const d = duration(ms)
  return d.includes(':') ? `${d} h` : d
}

/** Mitspielende Menschen (ohne den eigenen Platz = Mensch mit derselben Platzierung) */
function coPlayers(g: HistoryGame): string[] {
  return (g.seats ?? []).filter((s) => s.human === 1 && s.placement !== g.placement).map((s) => s.name)
}

/**
 * "Letzte Partien" auf dem Held (GET /api/history). Platz-Ziffer gelb/fg-2/fg-3, Deck, Meta, XP in Gelb.
 * local: mit Kunst 64x40, Meta "{Zuege} Zuege · {Dauer} · {heute, 21:40}", Link "Verlauf" -> Statistik/Verlauf.
 * server: ohne Kunst, Meta "Online mit X · N Zuege · heute" bzw. "Allein · …", Link "Statistik".
 */
export function RecentGames({ limit, withArt = false, variant = 'local' }: RecentGamesProps) {
  const go = useNav((s) => s.go)
  const decks = useCatalogStore((s) => s.decks)
  const [games, setGames] = useState<HistoryGame[] | null>(null)
  const [failed, setFailed] = useState(false)
  const server = variant === 'server'

  useEffect(() => {
    let stop = false
    // Spiele mit Fehlerende zaehlen nicht; etwas mehr holen, damit nach dem Filtern genug uebrig bleiben
    statsApi
      .history(Math.max(limit * 2, limit + 5))
      .then((all) => {
        if (!stop) setGames(all.filter((g) => g.endReason !== 'error').slice(0, limit))
      })
      .catch(() => {
        if (!stop) setFailed(true)
      })
    return () => {
      stop = true
    }
  }, [limit])

  const artOf = (g: HistoryGame): string | null => {
    const d = g.deckId != null ? decks.find((x) => x.id === g.deckId) : undefined
    if (d?.commanderSet && d.commanderNum) return cardImageUrl({ set: d.commanderSet, num: d.commanderNum }, { size: 'art_crop' })
    const first = (g.commander ?? '').split(' & ')[0]
    return first ? cardImageUrl({ name: first }, { size: 'art_crop' }) : null
  }

  const meta = (g: HistoryGame): string => {
    if (server) {
      const others = coPlayers(g)
      const who = others.length ? `Online mit ${others.join(', ')}` : 'Allein'
      return [who, g.turns ? `${g.turns} Züge` : null, relDay(g.endedAt).split(',')[0]].filter(Boolean).join(' · ')
    }
    return [`${g.turns} Züge`, heldDuration(g.durationMs), relDay(g.endedAt)].join(' · ')
  }

  const art = withArt && !server
  const columns = art ? '44px 64px minmax(0,1fr) auto' : '44px minmax(0,1fr) auto'

  return (
    <div className="flex min-h-0 flex-col overflow-hidden" data-testid="recent-games">
      <SectionHead
        label="Letzte Partien"
        link={server ? 'Statistik' : 'Verlauf'}
        onLink={() => (server ? go('stats') : go('stats', { statsTab: 'history' }))}
        testId="recent-games-link"
      />
      {failed && <ListNote>Der Verlauf konnte nicht geladen werden.</ListNote>}
      {games && games.length === 0 && <ListNote>Noch keine Partien. Nach dem ersten Spiel stehen hier Platz, Deck und verdiente XP.</ListNote>}
      {games?.map((g) => {
        const xpText = xp(g.xp)
        return (
          <div
            key={g.id}
            className={`grid items-center border-b border-line-board ${server ? 'gap-3.5 py-[11px]' : 'gap-4 py-3'}`}
            style={{ gridTemplateColumns: columns }}
            data-testid="recent-game"
          >
            <span className="num leading-none" style={{ fontSize: server ? 28 : 32, color: placeColor(g.placement) }}>
              {g.placement > 0 ? `${g.placement}.` : '–'}
            </span>
            {art && <ArtThumb src={artOf(g)} title={g.commander} />}
            <div className={`flex min-w-0 flex-col ${server ? 'gap-[3px]' : 'gap-1'}`}>
              <span className="truncate font-semibold leading-[1.3] text-fg-1" style={{ fontSize: server ? 14.5 : 15 }}>
                {g.deckName}
              </span>
              <span className="truncate text-[12.5px] leading-[1.4] text-fg-3">{meta(g)}</span>
            </div>
            <span
              className="num whitespace-nowrap leading-none"
              style={{ fontSize: server ? 19 : 20, color: xpText === DASH ? 'var(--color-fg-4)' : 'var(--color-target)' }}
            >
              {xpText}
            </span>
          </div>
        )
      })}
    </div>
  )
}
