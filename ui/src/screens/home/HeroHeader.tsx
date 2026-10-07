import { useState } from 'react'
import { Button, ProgressBar } from '../../components/ui'
import { count } from '../../lib/format'
import { pushToast } from '../../store/ui'
import { useProfile } from './useProfile'

export interface HeroHeaderProps {
  /** local: Held-Screen lokal; server: Held im Server-Modus (neben Lobby-Chat/Freunden) */
  variant: 'local' | 'server'
}

/** Level immer zweistellig ("03") */
const pad2 = (n: number) => String(Math.max(0, n)).padStart(2, '0')

/**
 * Held-Kopf (Meta-/Online-Prototyp): grosse Level-Zahl, Name (umbenennbar ueber den Stift beim Hover), Titel in Gelb,
 * XP-Balken 4 px, "X / Y XP bis Level N". local: rechts Kennzahlen Spiele/Siege/Serie (Serie in Ember) und
 * "Naechster Titel"; server: kompakt (Level 130/96, Name 40) mit "N Spiele · N Siege · Serie N" in der XP-Zeile.
 */
export function HeroHeader({ variant }: HeroHeaderProps) {
  const { profile, rename } = useProfile()
  const local = variant === 'local'

  const level = profile?.level ?? 1
  const into = profile?.xpIntoLevel ?? 0
  const need = profile?.xpForNext ?? 0
  const games = profile?.games ?? 0
  const wins = profile?.wins ?? 0
  const streak = profile?.streak ?? 0

  return (
    <div
      className={local ? 'grid items-end gap-14' : 'flex items-end gap-7'}
      style={local ? { gridTemplateColumns: 'minmax(0,1fr) auto' } : undefined}
      data-testid="hero-header"
    >
      <div className={`flex min-w-0 items-end ${local ? 'gap-8' : 'gap-7'}`}>
        <div className="flex flex-col gap-1.5">
          <span className="font-display text-[14px] font-semibold uppercase leading-none tracking-[.16em] text-fg-3">Level</span>
          <span
            className={`num leading-[.78] text-fg-1 ${local ? 'text-[110px] board:text-[160px]' : 'text-[96px] board:text-[130px]'}`}
            data-testid="hero-level"
          >
            {pad2(level)}
          </span>
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-3 pb-1.5">
          <HeroName name={profile?.name ?? 'Planeswalker'} title={profile?.title ?? 'Novize'} size={local ? 44 : 40} onRename={rename} />
          <ProgressBar value={into} max={Math.max(1, need)} height={4} tone="target" testId="hero-xp" />
          <div className="flex justify-between gap-4 text-[13px] leading-[1.4] text-fg-3">
            <span className="whitespace-nowrap">
              <span className="font-semibold text-fg-1 tabular-nums">{into}</span> / {need} XP bis Level {level + 1}
            </span>
            {local ? (
              profile?.nextTitle && (
                <span className="truncate">
                  Nächster Titel <span className="font-semibold text-fg-1">{profile.nextTitle.title}</span> · Level {profile.nextTitle.level}
                </span>
              )
            ) : (
              <span className="truncate tabular-nums">
                {count(games)} Spiele · {count(wins)} Siege · Serie {count(streak)}
              </span>
            )}
          </div>
        </div>
      </div>
      {local && (
        <div className="flex items-end gap-10 border-l border-line-2 pl-10">
          <HeroStat label="Spiele" value={games} />
          <HeroStat label="Siege" value={wins} />
          <HeroStat label="Serie" value={streak} ember={streak > 0} />
        </div>
      )}
    </div>
  )
}

function HeroStat({ label, value, ember }: { label: string; value: number; ember?: boolean }) {
  return (
    <div className="flex flex-col gap-2">
      <span className="font-display text-[13px] font-semibold uppercase leading-none tracking-[.14em] text-fg-3">{label}</span>
      <span className="num text-[56px] leading-[.85]" style={{ color: ember ? 'var(--color-ember)' : 'var(--color-fg-1)' }}>
        {count(value)}
      </span>
    </div>
  )
}

/** Name in Versalien + Titel; Stift beim Hover oeffnet ein Inline-Feld (Enter speichert, Esc bricht ab). */
function HeroName({ name, title, size, onRename }: { name: string; title: string; size: number; onRename: (n: string) => Promise<void> }) {
  const [edit, setEdit] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (edit === null || busy) return
    setBusy(true)
    try {
      await onRename(edit)
      setEdit(null)
    } catch (e) {
      pushToast({ kind: 'error', text: `Umbenennen fehlgeschlagen: ${e instanceof Error ? e.message : String(e)}` })
    } finally {
      setBusy(false)
    }
  }

  if (edit !== null) {
    return (
      <form
        className="flex items-center gap-2"
        style={{ height: size }}
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
      >
        <input
          autoFocus
          className="field font-display font-semibold"
          style={{ height: 44, width: 360, maxWidth: '100%', fontSize: 26, letterSpacing: '.02em' }}
          value={edit}
          maxLength={24}
          aria-label="Name des Helden"
          onChange={(e) => setEdit(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.preventDefault()
              e.stopPropagation()
              setEdit(null)
            }
          }}
        />
        <Button type="submit" variant="secondary" kbd="Enter" disabled={busy}>
          Speichern
        </Button>
        <Button variant="ghost" kbd="Esc" onClick={() => setEdit(null)}>
          Abbrechen
        </Button>
      </form>
    )
  }

  return (
    <div className="group flex min-w-0 items-baseline gap-3.5">
      <span
        className="min-w-0 truncate font-display font-semibold uppercase leading-none tracking-[.02em] text-fg-1"
        style={{ fontSize: size }}
        data-testid="hero-name"
      >
        {name}
      </span>
      <span className="flex-none whitespace-nowrap text-[16px] font-semibold text-target">{title}</span>
      <Button
        variant="icon"
        icon="edit"
        title="Umbenennen"
        aria-label="Umbenennen"
        className="flex-none self-center opacity-0 transition-opacity duration-1 group-hover:opacity-100 focus-visible:opacity-100"
        onClick={() => setEdit(name)}
      />
    </div>
  )
}
