import type { GameState } from '../api/types'

const STEPS: { key: string; label: string }[] = [
  { key: 'UNTAP', label: 'Enttappen' },
  { key: 'UPKEEP', label: 'Versorgung' },
  { key: 'DRAW', label: 'Ziehen' },
  { key: 'PRECOMBAT_MAIN', label: 'Main 1' },
  { key: 'BEGIN_COMBAT', label: 'Kampfbeginn' },
  { key: 'DECLARE_ATTACKERS', label: 'Angreifer' },
  { key: 'DECLARE_BLOCKERS', label: 'Blocker' },
  { key: 'FIRST_COMBAT_DAMAGE', label: 'Erstschlag' },
  { key: 'COMBAT_DAMAGE', label: 'Schaden' },
  { key: 'END_COMBAT', label: 'Kampfende' },
  { key: 'POSTCOMBAT_MAIN', label: 'Main 2' },
  { key: 'END_TURN', label: 'Ende' },
  { key: 'CLEANUP', label: 'Aufräumen' },
]

export function PhaseBar({ state }: { state: GameState }) {
  const active = state.players.find((p) => p.id === state.activePlayerId)
  const mine = active?.me
  return (
    <div className="flex items-center gap-3">
      <div className="max-w-[260px] truncate whitespace-nowrap text-xs text-ink-300">
        Runde <span className="font-semibold text-ink-100">{Math.ceil(state.turn / Math.max(1, state.players.length))}</span>
        <span className="mx-1.5 text-ink-500">·</span>
        <span className={mine ? 'font-semibold text-gold-300' : 'text-ink-100'}>{mine ? 'Dein Zug' : active?.name}</span>
      </div>
      <div className="flex items-center gap-0.5">
        {STEPS.filter((s) => s.key !== 'FIRST_COMBAT_DAMAGE' || state.step === 'FIRST_COMBAT_DAMAGE').map((s) => {
          const cur = s.key === state.step
          return (
            <div
              key={s.key}
              className={`whitespace-nowrap rounded-md px-1.5 py-0.5 text-[10px] font-medium transition-colors ${
                cur ? (mine ? 'bg-gold-400 text-ink-950' : 'bg-ink-200 text-ink-950') : 'text-ink-400'
              }`}
            >
              {s.label}
            </div>
          )
        })}
      </div>
    </div>
  )
}
