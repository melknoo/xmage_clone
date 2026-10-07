import { AnimatePresence } from 'motion/react'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { DeckSpec, Tempo } from '../api/types'
import { useDeckCatalog } from '../decks/catalog'
import { DeckPicker } from '../decks/DeckPicker'
import { tempoLabel } from '../lib/tempo'
import { useHotkey } from '../lib/useHotkey'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { pushToast } from '../store/ui'
import { deckMastery } from './decks/deckMastery'
import { MatchSummary } from './setup/MatchSummary'
import { MyDeckCard } from './setup/MyDeckCard'
import { SeatCard } from './setup/SeatCard'
import { TempoCards } from './setup/TempoCards'

/** Standard fuer neue Spieler (und wenn das gemerkte Deck fehlt) */
const DEFAULT_SAMPLE = 'Peer Through Time'

const specKey = (s: DeckSpec | null | undefined) => (!s ? '' : s.type === 'random' ? 'random' : `${s.type}:${s.id}`)
const pickOne = <T,>(xs: T[]): T | undefined => xs[Math.floor(Math.random() * xs.length)]

/** Spiel-Setup gegen drei Bots (lokal "Spielen", im Server-Modus "Allein üben"). */
export function PlaySetupScreen() {
  const { decks, samples, loaded, describe } = useDeckCatalog()
  const lastSetup = useNav((s) => s.lastSetup)
  const setLastSetup = useNav((s) => s.setLastSetup)
  const go = useNav((s) => s.go)
  const connect = useGame((s) => s.connect)
  const running = useGame((s) => !!s.gameId && !s.gameOver)
  const [chosen, setChosen] = useState<DeckSpec | null>(lastSetup?.deck ?? null)
  const [bots, setBots] = useState<DeckSpec[]>(() => {
    const b = lastSetup?.bots ?? []
    return [0, 1, 2].map((i) => b[i] ?? { type: 'random' })
  })
  const [tempo, setTempo] = useState<Tempo>(lastSetup?.tempo ?? 'NORMAL')
  const [busy, setBusy] = useState(false)
  const [armed, setArmed] = useState(false)
  const [picker, setPicker] = useState<null | { target: 'me' | number }>(null)

  // Standarddeck: gemerktes Setup -> neuestes eigenes Deck -> "Peer Through Time"
  const fallback = useMemo<DeckSpec | null>(() => {
    if (decks.length) {
      const newest = decks.reduce((a, b) => (b.createdAt > a.createdAt ? b : a))
      return { type: 'user', id: newest.id }
    }
    const s = samples.find((x) => x.name === DEFAULT_SAMPLE) ?? samples[0]
    return s ? { type: 'sample', id: s.id } : null
  }, [decks, samples])
  const myDeck: DeckSpec | null = chosen && (!loaded || describe(chosen)) ? chosen : fallback
  const me = describe(myDeck)
  const myStored = myDeck?.type === 'user' ? decks.find((d) => d.id === myDeck.id) : undefined
  const botInfo = bots.map((b) => describe(b) ?? describe({ type: 'random' }))

  // jede Aenderung entschaerft die Rueckfrage "Laufende Partie beenden?"
  const setupKey = [specKey(myDeck), ...bots.map(specKey), tempo].join('|')
  useEffect(() => setArmed(false), [setupKey, running])

  const start = async () => {
    if (!myDeck || busy) return
    if (running && !armed) {
      setArmed(true)
      return
    }
    setBusy(true)
    const setup = { deck: myDeck, bots, tempo }
    try {
      const res = await api.post<{ gameId: string }>('/api/games', setup)
      setLastSetup(setup)
      connect(res.gameId)
      go('game')
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
      setArmed(false)
    }
  }

  useHotkey(
    'Enter',
    (e) => {
      // global, auch mit Fokus auf einem Knopf (preventDefault verhindert dessen eigenes Enter)
      e.preventDefault()
      void start()
    },
    { enabled: !picker },
  )

  const randomMine = () => {
    const own = decks.filter((d) => specKey({ type: 'user', id: d.id }) !== specKey(myDeck))
    const d = pickOne(own.length ? own : decks)
    if (d) return setChosen({ type: 'user', id: d.id })
    const s = pickOne(samples.filter((x) => specKey({ type: 'sample', id: x.id }) !== specKey(myDeck)))
    if (s) setChosen({ type: 'sample', id: s.id })
  }

  /** konkretes vorgefertigtes Deck, das kein anderer Platz (und nicht du) spielt */
  const shuffleSeat = (i: number) => {
    const used = new Set([specKey(myDeck), ...bots.filter((_, j) => j !== i).map(specKey), specKey(bots[i])])
    const s = pickOne(samples.filter((x) => !used.has(specKey({ type: 'sample', id: x.id }))))
    if (s) setBots(bots.map((b, j) => (j === i ? { type: 'sample', id: s.id } : b)))
  }

  const sectionLabel = { letterSpacing: '.14em' }
  return (
    <>
      <div className="h-full overflow-y-auto scrollbar-thin">
        <div className="grid min-h-full grid-cols-[minmax(0,1fr)_290px] gap-[18px] px-9 py-7 board:grid-cols-[minmax(0,1fr)_360px] board:gap-7 board:px-16 board:py-12">
          <div className="flex min-w-0 flex-col gap-[18px] board:gap-7">
            <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
              <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Spielen</h1>
              <span className="text-[14px] text-fg-3">Commander · Free-for-All · 40 Leben · gegen drei Bots</span>
            </div>

            <section className="flex flex-col gap-3">
              <span className="label" style={sectionLabel}>
                Dein Deck
              </span>
              <MyDeckCard info={me} mastery={myStored ? deckMastery(myStored) : null} onChange={() => setPicker({ target: 'me' })} onRandom={randomMine} />
            </section>

            <section className="flex flex-col gap-3">
              <span className="label" style={sectionLabel}>
                Bot-Tempo
              </span>
              <TempoCards value={tempo} onChange={setTempo} />
            </section>

            <section className="flex flex-1 flex-col gap-3">
              <span className="label" style={sectionLabel}>
                Gegner
              </span>
              <div className="grid flex-1 grid-cols-3 grid-rows-[1fr] gap-3 board:gap-4">
                {bots.map((_, i) => (
                  <SeatCard key={i} seat={i + 2} info={botInfo[i]} onChange={() => setPicker({ target: i })} onShuffle={() => shuffleSeat(i)} />
                ))}
              </div>
            </section>
          </div>

          <MatchSummary
            seats={[{ k: 'Du', v: me?.name ?? '–' }, ...botInfo.map((b, i) => ({ k: `Platz ${i + 2}`, v: b?.name ?? 'Zufälliges Deck' }))]}
            tempo={tempoLabel(tempo)}
            busy={busy}
            armed={armed}
            disabled={!myDeck}
            onStart={() => void start()}
          />
        </div>
      </div>

      <AnimatePresence>
        {picker && (
          <DeckPicker
            key="picker"
            decks={decks}
            samples={samples}
            allowRandom={picker.target !== 'me'}
            forLabel={picker.target === 'me' ? 'Dein Deck' : `Platz ${picker.target + 2}`}
            current={picker.target === 'me' ? myDeck : bots[picker.target]}
            onClose={() => setPicker(null)}
            onPick={(spec) => {
              if (picker.target === 'me') setChosen(spec)
              else setBots(bots.map((b, j) => (j === picker.target ? spec : b)))
              setPicker(null)
            }}
          />
        )}
      </AnimatePresence>
    </>
  )
}
