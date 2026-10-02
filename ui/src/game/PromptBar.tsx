import { motion } from 'motion/react'
import { useGame } from '../store/game'
import { Rich } from '../lib/mana'
import type { Interaction } from './interaction'
import { SKIPS, usePromptButtons } from './promptActions'

const BTN: Record<string, string> = { primary: 'btn-primary', ghost: 'btn-ghost', danger: 'btn-danger', arcane: 'btn-arcane' }

export function PromptBar({ inter }: { inter: Interaction }) {
  const p = inter.prompt
  const buttons = usePromptButtons(inter)
  const action = useGame((s) => s.action)
  const waitingFor = useGame((s) => s.waitingFor)
  const thinking = useGame((s) => s.thinking)
  const stackSize = useGame((s) => s.state?.stack.length ?? 0)
  const setHover = useGame((s) => s.setHover)
  const objects = useGame((s) => s.objects)
  const answered = useGame((s) => s.answeredPromptId)
  const raw = useGame((s) => s.prompt)

  const priority = p?.kind === 'SELECT' && p.mode === 'priority'
  const busy = raw && answered === raw.id

  return (
    <div className="glass flex min-h-[52px] items-center gap-3 rounded-2xl px-4 py-2">
      <div className="min-w-0 flex-1">
        {p ? (
          <motion.div key={p.id} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} className="text-sm leading-snug text-ink-100">
            <span className="mr-2 inline-block h-2 w-2 animate-pulse rounded-full bg-gold-400 align-middle" />
            <Rich segs={p.message} onObject={(id) => setHover(objects.get(id) ?? null)} />
            {p.secondMessage && (
              <div className="text-xs text-ink-300">
                <Rich segs={p.secondMessage} />
              </div>
            )}
          </motion.div>
        ) : (
          <div className="flex items-center gap-2 text-sm text-ink-300">
            {busy ? (
              <span className="animate-pulse">…</span>
            ) : (
              <>
                <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-arcane-400" />
                {thinking || waitingFor ? <span>Warte auf {waitingFor ?? 'Bot'} …</span> : <span>Gegner sind am Zug …</span>}
              </>
            )}
          </div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {priority &&
          SKIPS.filter((s) => s.action !== 'PASS_PRIORITY_UNTIL_STACK_RESOLVED' || stackSize > 0).map((s) => (
            <button key={s.action} className="btn-ghost !px-2.5 !py-1.5 !text-xs" title={`${s.title} (${s.hotkey})`} onClick={() => action(s.action)}>
              {s.label}
              <span className="kbd">{s.hotkey}</span>
            </button>
          ))}
        {buttons.map((b) => (
          <button key={b.label} className={`${BTN[b.kind]} min-w-[110px]`} onClick={b.run}>
            {b.label}
            {b.hotkey && <span className="kbd">{b.hotkey}</span>}
          </button>
        ))}
      </div>
    </div>
  )
}
