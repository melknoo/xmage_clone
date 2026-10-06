import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { useGame } from '../store/game'
import { Rich } from '../lib/mana'
import { ActivityLine } from './ActivityIndicator'
import type { Interaction } from './interaction'
import { SKIP_LABEL, SKIPS, usePromptButtons } from './promptActions'

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
  const clearMarks = useGame((s) => s.clearMarks)
  const marks = inter.marked.size
  const attacking = useGame((s) => (s.state?.combat ?? []).reduce((n, g) => n + g.attackers.length, 0))
  // laufendes F-Tasten-Passen (z.B. F9 "bis zu meinem Zug") - jederzeit abbrechbar
  const skips = useGame((s) => s.state?.players.find((pl) => pl.me)?.skips)
  const skipText = skips && skips.length > 0 ? SKIP_LABEL[skips[0]] ?? 'Passe automatisch' : null
  // Zwei-Klick-Bestaetigung ("Alle angreifen"): erster Klick scharf schalten, naechster Prompt setzt zurueck
  const [armed, setArmed] = useState<string | null>(null)
  useEffect(() => setArmed(null), [p?.id])

  const priority = p?.kind === 'SELECT' && p.mode === 'priority'
  const busy = raw && answered === raw.id

  return (
    <div className="glass flex min-h-[52px] items-center gap-3 rounded-2xl px-4 py-2">
      <div className="min-w-0 flex-1">
        {p ? (
          <motion.div key={p.id} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} className="text-sm leading-snug text-ink-100">
            <span className="mr-2 inline-block h-2 w-2 animate-pulse rounded-full bg-gold-400 align-middle" />
            {marks > 0 ? (
              <span className="font-semibold text-arcane-400">
                {marks} markiert – {inter.mode === 'attack' ? 'Gegner oder Planeswalker anklicken: alle greifen ihn an' : 'Angreifer anklicken: alle blocken ihn'}
              </span>
            ) : (
              <Rich segs={p.message} onObject={(id) => setHover(objects.get(id) ?? null)} />
            )}
            {marks === 0 && (inter.mode === 'attack' || inter.mode === 'block') && (
              <span className="ml-2 text-xs text-ink-400">
                Shift+Klick: mehrere markieren
                {inter.mode === 'attack' && attacking > 0 && ' · Klick auf einen Angreifer nimmt ihn zurück'}
              </span>
            )}
            {p.secondMessage && (
              <div className="text-xs text-ink-300">
                <Rich segs={p.secondMessage} />
              </div>
            )}
          </motion.div>
        ) : skipText ? (
          <div className="flex items-center gap-2 text-sm text-arcane-400">
            <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-arcane-400" />
            <span className="font-semibold">⏭ {skipText}</span>
            <span className="text-xs text-ink-400">– F3 oder „Stopp“ hält wieder an</span>
          </div>
        ) : (
          <ActivityLine
            fallback={
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
            }
          />
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {skipText && (
          <button
            className="btn-ghost !border-arcane-400/60 !px-2.5 !py-1.5 !text-xs !text-arcane-400"
            title="Automatisches Passen beenden – du bekommst wieder Priorität (z. B. in der Endphase eines Gegners)"
            onClick={() => action('PASS_PRIORITY_CANCEL_ALL_ACTIONS')}
          >
            ⏹ Stopp
            <span className="kbd">F3</span>
          </button>
        )}
        {priority &&
          SKIPS.filter((s) => s.action !== 'PASS_PRIORITY_UNTIL_STACK_RESOLVED' || stackSize > 0).map((s) => (
            <button key={s.action} className="btn-ghost !px-2.5 !py-1.5 !text-xs" title={`${s.title} (${s.hotkey})`} onClick={() => action(s.action)}>
              {s.label}
              <span className="kbd">{s.hotkey}</span>
            </button>
          ))}
        {marks > 0 && (
          <button className="btn-ghost !px-2.5 !py-1.5 !text-xs" onClick={clearMarks} title="Markierung aufheben (Esc)">
            Markierung aufheben
            <span className="kbd">Esc</span>
          </button>
        )}
        {buttons.map((b) => (
          <button
            key={b.label}
            className={`${BTN[b.kind]} min-w-[110px] ${armed === b.label ? 'ring-2 ring-blood-400' : ''}`}
            title={b.title}
            onClick={() => {
              if (b.confirm && armed !== b.label) {
                setArmed(b.label)
                return
              }
              setArmed(null)
              b.run()
            }}
          >
            {armed === b.label && b.confirm ? b.confirm : b.label}
            {b.hotkey && <span className="kbd">{b.hotkey}</span>}
          </button>
        ))}
      </div>
    </div>
  )
}
