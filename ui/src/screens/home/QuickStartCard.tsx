import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import type { DeckSpec, SampleDeck } from '../../api/types'
import { Button } from '../../components/ui'
import { useDeckCatalog } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { tempoLabel } from '../../lib/tempo'
import { useHotkey } from '../../lib/useHotkey'
import { useGame } from '../../store/game'
import { useNav, type LastSetup } from '../../store/nav'
import { pushToast } from '../../store/ui'
import { useProfile } from './useProfile'

/** Vorgefertigtes Deck fuer das erste Spiel (Prototyp: "Peer Through Time", Teferi) */
const FIRST_DECK = 'Peer Through Time'
/** zweiter Klick bei laufender Partie muss innerhalb dieser Zeit kommen */
const ARM_MS = 5_000

function defaultSetup(samples: SampleDeck[]): LastSetup | null {
  const s = samples.find((x) => x.name === FIRST_DECK) ?? samples[0]
  if (!s) return null
  const rnd: DeckSpec = { type: 'random' }
  return { deck: { type: 'sample', id: s.id }, bots: [rnd, rnd, rnd], tempo: 'NORMAL' }
}

/**
 * Schnellstart-Karte (Held, lokal): Commander-Kunst rechts (62 %, Verlauf von bg-3), Label "Schnellstart"
 * bzw. "Erstes Spiel", Deckname, Meta und "Spiel starten" mit Enter (globale Taste). Ohne gueltige letzte
 * Konfiguration: vorgefertigtes Deck "Peer Through Time" gegen 3 Zufallsdecks, Tempo Normal.
 * Laeuft schon eine Partie, braucht der Start einen zweiten Klick ("Laufende Partie beenden?").
 */
export function QuickStartCard() {
  const go = useNav((s) => s.go)
  const lastSetup = useNav((s) => s.lastSetup)
  const setLastSetup = useNav((s) => s.setLastSetup)
  const gameId = useGame((s) => s.gameId)
  const spectator = useGame((s) => s.spectator)
  const connect = useGame((s) => s.connect)
  const { profile } = useProfile()
  const { decks, samples, loaded, describe } = useDeckCatalog()
  const [busy, setBusy] = useState(false)
  const [armed, setArmed] = useState(false)

  // Letzte Konfiguration nur, wenn ihr Deck noch existiert; geloeschte Bot-Decks werden zu Zufallsdecks
  const setup: LastSetup | null = useMemo(() => {
    if (lastSetup && (!loaded || describe(lastSetup.deck))) {
      if (!loaded) return lastSetup
      return { ...lastSetup, bots: lastSetup.bots.map((b) => (describe(b) ? b : ({ type: 'random' } as DeckSpec))) }
    }
    return loaded ? defaultSetup(samples) : null
    // describe haengt nur an decks/samples
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastSetup, loaded, decks, samples])

  const info = setup ? describe(setup.deck) : null
  const firstGame = profile ? profile.games === 0 : !lastSetup
  const running = !!gameId && !spectator
  const bots = setup?.bots.length ?? 3

  const meta =
    info?.kind === 'sample' && firstGame
      ? ['Vorgefertigtes Deck', info.commander, `gegen ${bots} Bots`]
      : [info?.commander, `gegen ${bots} Bots`, setup ? `Tempo ${tempoLabel(setup.tempo)}` : null]

  useEffect(() => {
    if (!armed) return
    const h = window.setTimeout(() => setArmed(false), ARM_MS)
    return () => window.clearTimeout(h)
  }, [armed])
  useEffect(() => {
    if (!running) setArmed(false)
  }, [running])

  const start = async () => {
    if (!setup || busy) return
    setBusy(true)
    setArmed(false)
    try {
      const res = await api.post<{ gameId: string }>('/api/games', setup)
      setLastSetup(setup)
      connect(res.gameId)
      go('game')
    } catch (e) {
      // z.B. Deck inzwischen geloescht
      pushToast({ kind: 'error', text: `Spiel konnte nicht gestartet werden: ${e instanceof Error ? e.message : String(e)}` })
    } finally {
      setBusy(false)
    }
  }

  const activate = () => {
    if (!setup || busy) return
    if (running && !armed) {
      setArmed(true)
      return
    }
    void start()
  }

  // Enter = Spiel starten (nicht in Eingaben/Dialogen; ein fokussierter Knopf behaelt seine eigene Enter-Taste)
  useHotkey(
    'Enter',
    (e) => {
      const t = e.target as HTMLElement | null
      if (t && t !== document.body && t.closest('button, a, [role="button"], [role="tab"], [role="option"]')) return
      e.preventDefault()
      activate()
    },
    { enabled: !!setup && !busy },
  )

  return (
    <div className="relative h-[170px] overflow-hidden rounded-md bg-bg-3 board:h-[230px]" data-testid="home-quickstart-card">
      {info?.art && (
        <div
          className="absolute top-0 right-0 h-full w-[62%] bg-bg-4"
          style={{ backgroundImage: `url("${info.art}")`, backgroundSize: 'cover', backgroundPosition: 'center' }}
        />
      )}
      {/* Art-Overlay: einzige erlaubte Verlaufs-Ausnahme */}
      <div className="absolute inset-0" style={{ background: 'linear-gradient(90deg, var(--color-bg-3) 38%, rgba(26,25,23,.4) 70%, rgba(26,25,23,0))' }} />
      <div className="relative flex h-full flex-col justify-between px-6 py-5 board:px-8 board:py-7">
        <div className="flex min-w-0 flex-col gap-2 board:gap-2.5">
          <span className="flex items-center gap-1.5 font-display text-[14px] font-semibold uppercase leading-none tracking-[.14em] text-ember">
            <Icon name="autoMana" size={15} />
            {firstGame ? 'Erstes Spiel' : 'Schnellstart'}
          </span>
          <span className="truncate font-display text-[32px] font-semibold leading-none tracking-[.01em] text-fg-1 board:text-[40px]" data-testid="home-quickstart-deck">
            {info?.name ?? '…'}
          </span>
          <span className="truncate text-[14px] leading-[1.4] text-fg-2">{meta.filter(Boolean).join(' · ')}</span>
        </div>
        {busy ? (
          <Button variant="wait" icon="thinking" className="self-start" style={{ height: 46, padding: '0 20px' }}>
            Starte …
          </Button>
        ) : (
          <Button
            variant={armed ? 'dangerConfirm' : 'primary'}
            size="lg"
            icon="start"
            kbd="Enter"
            className="self-start"
            style={{ height: 46, padding: '0 20px' }}
            disabled={!setup}
            data-armed={armed ? 'true' : undefined}
            onClick={activate}
            onBlur={() => setArmed(false)}
            testId="home-quickstart"
            title={running ? 'Eine Partie läuft noch – ein neues Spiel beendet sie.' : undefined}
          >
            {armed ? 'Laufende Partie beenden?' : 'Spiel starten'}
          </Button>
        )}
      </div>
    </div>
  )
}
