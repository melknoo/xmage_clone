import type { StatsOverview } from '../../api/stats'
import { shortName } from '../../game/format'
import { tempoLabel } from '../../lib/tempo'
import { ArtThumb, commanderArtUrl, SectionLabel } from './parts'

const MAX_FOES = 4

const games = (n: number) => `${n} ${n === 1 ? 'Spiel' : 'Spiele'}`
const partien = (n: number) => `${n} ${n === 1 ? 'Partie' : 'Partien'}`
/** Siege: Anzahl, 0 als "–" (wie im Prototyp) */
const wins = (n: number | null | undefined) => (n ? `${n} ${n === 1 ? 'Sieg' : 'Siege'}` : '–')

function mulliganLabel(m: number): string {
  if (m <= 0) return 'Ohne Mulligan'
  return m === 1 ? '1 Mulligan' : `${m} Mulligans`
}

function ListBlock({ title, rows, testId }: { title: string; rows: { key: string; k: string; v: string }[]; testId: string }) {
  return (
    <div className="flex min-w-0 flex-col" data-testid={testId}>
      <SectionLabel className="border-b border-line-3 pb-2.5">{title}</SectionLabel>
      {rows.map((r) => (
        <div key={r.key} className="flex items-baseline justify-between gap-3 border-b border-line-1 py-2.5 text-[13.5px] text-fg-1">
          <span className="truncate">{r.k}</span>
          <span className="num flex-none text-[16px] leading-none" style={{ color: r.v === '–' ? 'var(--color-fg-4)' : undefined }}>
            {r.v}
          </span>
        </div>
      ))}
    </div>
  )
}

/** Rechte Spalte der Uebersicht: Tempo, Mulligans, haeufigste Gegner */
export function Breakdowns({ ov }: { ov: StatsOverview }) {
  const tempoRows = ov.byTempo.map((r) => ({ key: r.tempo, k: `${tempoLabel(r.tempo)} · ${games(r.games)}`, v: wins(r.wins) }))
  const mullRows = ov.byMulligans.map((r) => ({ key: String(r.mulligans), k: `${mulliganLabel(r.mulligans)} · ${r.games}`, v: wins(r.wins) }))
  const foes = ov.opponents.slice(0, MAX_FOES)
  return (
    <div className="grid min-w-0 grid-cols-2 content-start gap-8">
      <ListBlock title="Tempo" rows={tempoRows} testId="stats-tempo" />
      <ListBlock title="Mulligans" rows={mullRows} testId="stats-mulligans" />
      {foes.length > 0 && (
        <div className="col-span-2 flex min-w-0 flex-col" data-testid="stats-opponents">
          <SectionLabel className="border-b border-line-3 pb-2.5">Häufigste Gegner</SectionLabel>
          <div className="flex flex-wrap gap-5 pt-3">
            {foes.map((f) => {
              const ahead = f.aheadOfYou === undefined || f.aheadOfYou === null ? '' : f.aheadOfYou > 0 ? ` · ${f.aheadOfYou}× vor dir` : ' · nie vor dir'
              return (
                <div key={f.commander} className="flex min-w-0 items-center gap-2.5" title={f.commander}>
                  <ArtThumb src={commanderArtUrl(f.commander)} width={34} height={34} radius={3} />
                  <div className="flex min-w-0 flex-col gap-[3px]">
                    <span className="max-w-[180px] truncate text-[13.5px] font-semibold text-fg-1">{shortName(f.commander) || f.commander}</span>
                    <span className="text-[12px] text-fg-3">
                      {partien(f.games)}
                      {ahead}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
