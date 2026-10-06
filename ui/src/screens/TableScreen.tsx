import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { tableLink, tablesApi, type Table, type TableSeat } from '../api/tables'
import type { DeckSpec, Tempo } from '../api/types'
import { ColorPips } from '../lib/mana'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { FriendsPanel } from '../social/FriendsPanel'
import { DeckPicker, TEMPOS, useDeckCatalog, type DeckInfo } from './PlaySetupScreen'

const POLL_MS = 1500

/** Ein Tisch: 4 Plätze, Deckwahl, Gastgeber-Steuerung, Start. Synchronisiert per Polling. */
export function TableScreen() {
  const tableId = useTable((s) => s.tableId)
  const setTableId = useTable((s) => s.setTableId)
  const go = useNav((s) => s.go)
  const connect = useGame((s) => s.connect)
  const gameId = useGame((s) => s.gameId)
  const { decks, samples, describe } = useDeckCatalog()
  const [table, setTable] = useState<Table | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** letzte Abfrage fehlgeschlagen (Netz, Neustart) - wir pollen weiter */
  const [shaky, setShaky] = useState(false)
  const [busy, setBusy] = useState(false)
  const [picker, setPicker] = useState<null | { seat: number; bot: boolean }>(null)
  const [copied, setCopied] = useState(false)
  const [nameEdit, setNameEdit] = useState<string | null>(null)
  const [chatText, setChatText] = useState('')
  const chatRef = useRef<HTMLDivElement>(null)
  const joinedGame = useRef<string | null>(null)

  const load = useCallback(async () => {
    if (!tableId) return
    try {
      const t = await tablesApi.get(tableId)
      setTable(t)
      setShaky(false)
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0
      if (status === 0 || status >= 500 || status === 429 || status === 401) {
        // Netz/Server-Neustart/Rate-Limit: weiter pollen statt den Tisch zu verlassen (401 zeigt ohnehin den Login)
        setShaky(true)
        return
      }
      // Tisch weg (Gastgeber hat geschlossen) -> zurueck in die Lobby
      setTableId(null)
      setTable(null)
      setError(e instanceof Error ? e.message : String(e))
      go('play')
    }
  }, [tableId, setTableId, go])

  useEffect(() => {
    load()
    const iv = window.setInterval(load, POLL_MS)
    return () => window.clearInterval(iv)
  }, [load])

  const chatLen = table?.chat?.length ?? 0
  useEffect(() => {
    if (chatRef.current) chatRef.current.scrollTop = chatRef.current.scrollHeight
  }, [chatLen])

  // Spiel gestartet -> an den Tisch setzen (einmal pro Spiel)
  useEffect(() => {
    if (!table || table.state !== 'RUNNING' || !table.gameId || table.mySeat === null) return
    if (joinedGame.current === table.gameId || gameId === table.gameId) return
    joinedGame.current = table.gameId
    connect(table.gameId)
    go('game')
  }, [table, gameId, connect, go])

  const run = async (fn: () => Promise<Table | unknown>) => {
    setBusy(true)
    setError(null)
    try {
      const t = await fn()
      if (t && typeof t === 'object' && 'seats' in (t as Table)) setTable(t as Table)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!tableId) {
    return (
      <div className="flex h-full items-center justify-center text-ink-300">
        Du sitzt an keinem Tisch.{' '}
        <button className="btn-ghost ml-3 !text-xs" onClick={() => go('play')}>
          Zur Lobby
        </button>
      </div>
    )
  }
  if (!table) {
    return <div className="flex h-full items-center justify-center text-ink-400">{error ?? 'Lade Tisch …'}</div>
  }

  const isHost = table.host
  const humans = table.seats.filter((s) => s.kind === 'HUMAN')
  const players = table.seats.filter((s) => s.kind !== 'OPEN').length
  const allReady = humans.every((s) => s.ready)
  const canStart = isHost && table.state === 'LOBBY' && players >= 2 && allReady
  const startHint = !isHost ? `Warten auf ${table.hostName} …` : players < 2 ? 'Mindestens zwei Spieler – lade jemanden ein oder setz einen Bot.' : !allReady ? 'Noch nicht alle haben ein Deck gewählt.' : ''

  const leave = () =>
    run(async () => {
      await tablesApi.leave(table.id)
      setTableId(null)
      go('play')
    })

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(tableLink(table.id))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      setError('Kopieren nicht möglich – Link: ' + tableLink(table.id))
    }
  }

  return (
    <div className="h-full overflow-y-auto p-8 scrollbar-thin">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          {isHost && nameEdit !== null ? (
            <input
              autoFocus
              value={nameEdit}
              maxLength={40}
              onChange={(e) => setNameEdit(e.target.value)}
              onBlur={() => {
                const v = nameEdit.trim()
                setNameEdit(null)
                if (v && v !== table.name) run(() => tablesApi.update(table.id, { name: v }))
              }}
              onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
              className="rounded-lg border border-white/10 bg-ink-950/60 px-2 py-1 font-display text-3xl font-bold text-gold-300 outline-none"
            />
          ) : (
            <h1 className="font-display text-3xl font-bold tracking-wide text-gold-300" onClick={() => isHost && setNameEdit(table.name)} title={isHost ? 'Klicken zum Umbenennen' : undefined}>
              {table.name}
            </h1>
          )}
          <p className="mt-1 text-ink-300">
            Gastgeber {table.hostName} · Code <span className="font-mono text-gold-200">{table.id}</span>
            {table.state === 'RUNNING' && <span className="ml-2 rounded bg-arcane-500/20 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-arcane-400">Spiel läuft</span>}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button className="btn-ghost !text-xs" onClick={copy}>
            {copied ? 'Link kopiert ✓' : 'Einladungslink kopieren'}
          </button>
          <button className="btn-ghost !text-xs" disabled={busy} onClick={leave}>
            {isHost ? 'Tisch schließen' : 'Tisch verlassen'}
          </button>
        </div>
      </div>

      <div className="mt-8 grid max-w-5xl gap-6 lg:grid-cols-[1.2fr_1fr]">
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">Plätze</h2>
          <div className="flex flex-col gap-2">
            {table.seats.map((s, i) => (
              <SeatRow
                key={i}
                n={i}
                seat={s}
                table={table}
                info={describe(s.deck)}
                busy={busy}
                onPickDeck={() => setPicker({ seat: i, bot: s.kind === 'BOT' })}
                onSetBot={() => run(() => tablesApi.setSeat(table.id, i, 'BOT', null))}
                onSetOpen={() => run(() => tablesApi.setSeat(table.id, i, 'OPEN'))}
              />
            ))}
          </div>
          <h2 className="mb-3 mt-8 text-sm font-semibold uppercase tracking-wider text-ink-300">Tisch-Chat</h2>
          <div className="glass flex h-[220px] flex-col overflow-hidden rounded-xl">
            <div ref={chatRef} className="min-h-0 flex-1 overflow-y-auto px-3 py-2 text-sm scrollbar-thin">
              {(table.chat ?? []).length === 0 && <div className="py-2 text-xs italic text-ink-400">Noch keine Nachrichten.</div>}
              {(table.chat ?? []).map((c, i) => (
                <div key={`${c.ts}-${i}`} className="py-0.5">
                  <span className="mr-1.5 tabular-nums text-[10px] text-ink-500">{new Date(c.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
                  <span className={`font-semibold ${c.userId === table.hostUserId ? 'text-gold-300' : 'text-arcane-400'}`}>{c.name}</span>
                  <span className="text-ink-400">: </span>
                  <span className="text-ink-100">{c.text}</span>
                </div>
              ))}
            </div>
            <div className="shrink-0 border-t border-white/10 p-2">
              <input
                className="w-full rounded-lg bg-ink-950/70 px-3 py-1.5 text-sm ring-1 ring-white/15 outline-none placeholder:text-ink-500 focus:ring-gold-400/60"
                placeholder="Nachricht an den Tisch … (Enter)"
                maxLength={300}
                value={chatText}
                disabled={table.mySeat === null}
                onChange={(e) => setChatText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key !== 'Enter') return
                  e.preventDefault()
                  const t = chatText.trim()
                  if (!t) return
                  setChatText('')
                  run(() => tablesApi.chat(table.id, t))
                }}
              />
            </div>
          </div>
        </section>
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-ink-300">Bot-Tempo</h2>
          <div className="grid grid-cols-2 gap-2">
            {TEMPOS.map((t) => (
              <button
                key={t.key}
                disabled={!isHost || busy}
                className={`rounded-xl p-3 text-left ring-1 transition disabled:cursor-default ${table.tempo === t.key ? 'bg-arcane-500/15 ring-arcane-400/60' : 'bg-ink-900/60 ring-white/10'} ${isHost ? 'hover:ring-white/25' : 'opacity-70'}`}
                onClick={() => run(() => tablesApi.update(table.id, { tempo: t.key as Tempo }))}
              >
                <div className={`font-semibold ${table.tempo === t.key ? 'text-arcane-400' : 'text-ink-100'}`}>{t.label}</div>
                <div className="text-xs text-ink-400">{t.desc}</div>
              </button>
            ))}
          </div>
          {!isHost && <div className="mt-2 text-xs text-ink-400">Das Tempo stellt der Gastgeber.</div>}

          <div className="mt-8 flex flex-col gap-3">
            {isHost ? (
              <button className="btn-primary !px-8 !py-3 !text-base" disabled={!canStart || busy} onClick={() => run(() => tablesApi.start(table.id))}>
                {busy ? 'Mische Decks …' : table.state === 'RUNNING' ? 'Spiel läuft' : 'Spiel starten'}
              </button>
            ) : (
              <div className="rounded-xl bg-ink-900/60 px-4 py-3 text-sm text-ink-300 ring-1 ring-white/10">
                {table.mySeat !== null && !table.seats[table.mySeat].ready ? 'Wähle zuerst dein Deck.' : startHint}
              </div>
            )}
            {isHost && startHint && <div className="text-xs text-ink-400">{startHint}</div>}
            {shaky && <div className="rounded-lg bg-amber-500/15 px-3 py-2 text-sm text-amber-300">Verbindung wackelt – versuche es weiter …</div>}
            {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}
          </div>

          {table.state === 'LOBBY' && table.seats.some((s) => s.kind === 'OPEN') && table.mySeat !== null && (
            <>
              <h2 className="mb-3 mt-8 text-sm font-semibold uppercase tracking-wider text-ink-300">Freunde einladen</h2>
              <FriendsPanel inviteTableId={table.id} seatedIds={humans.map((s) => s.userId ?? 0)} compact />
            </>
          )}
        </section>
      </div>

      {picker && (
        <DeckPicker
          decks={decks}
          samples={samples}
          allowRandom={picker.bot}
          onClose={() => setPicker(null)}
          onPick={(spec: DeckSpec) => {
            const p = picker
            setPicker(null)
            if (p.bot) run(() => tablesApi.setSeat(table.id, p.seat, 'BOT', spec))
            else run(() => tablesApi.setMyDeck(table.id, spec))
          }}
        />
      )}
    </div>
  )
}

function SeatRow({ n, seat, table, info, busy, onPickDeck, onSetBot, onSetOpen }: { n: number; seat: TableSeat; table: Table; info: DeckInfo | null; busy: boolean; onPickDeck: () => void; onSetBot: () => void; onSetOpen: () => void }) {
  const isHost = table.host
  const mine = seat.kind === 'HUMAN' && seat.me
  const lobby = table.state === 'LOBBY'
  const base = `relative flex h-[84px] items-center gap-4 overflow-hidden rounded-2xl px-5 ring-1 ${mine ? 'ring-gold-400/40' : 'ring-white/10'} bg-ink-900/70`
  return (
    <div className={base}>
      {info?.art && <div className="absolute inset-0 bg-cover bg-center opacity-30" style={{ backgroundImage: `url(${info.art})` }} />}
      <div className="absolute inset-0 bg-linear-to-r from-ink-950/90 via-ink-950/60 to-transparent" />
      <div className="relative w-6 text-center font-display text-lg text-ink-500">{n + 1}</div>
      <div className="relative min-w-0 flex-1">
        {seat.kind === 'OPEN' && <div className="text-ink-400">Freier Platz{isHost ? ' – wartet auf einen Freund oder einen Bot' : ''}</div>}
        {seat.kind === 'HUMAN' && (
          <>
            <div className="flex items-center gap-2">
              <span className="font-display text-lg font-semibold text-ink-100">{seat.name}</span>
              {mine && <span className="text-xs text-ink-400">(du)</span>}
              {seat.userId === table.hostUserId && <span className="rounded bg-gold-400/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-gold-300">Gastgeber</span>}
            </div>
            <div className="mt-0.5 flex items-center gap-2 text-xs text-ink-300">
              {info ? (
                <>
                  {info.colors && <ColorPips colors={info.colors} size="sm" />}
                  <span className="truncate">{info.name}</span>
                </>
              ) : seat.deckName ? (
                <span className="truncate">{seat.deckName}</span>
              ) : (
                <span className="text-ink-500">wählt noch ein Deck …</span>
              )}
            </div>
          </>
        )}
        {seat.kind === 'BOT' && (
          <>
            <div className="font-display text-lg font-semibold text-ink-100">
              Bot <span className="text-xs font-normal text-ink-400">🤖</span>
            </div>
            <div className="mt-0.5 flex items-center gap-2 text-xs text-ink-300">
              {info?.colors && <ColorPips colors={info.colors} size="sm" />}
              <span className="truncate">{info?.name ?? seat.deckName ?? 'Zufälliges Deck'}</span>
            </div>
          </>
        )}
      </div>
      {lobby && (
        <div className="relative flex shrink-0 items-center gap-1.5">
          {seat.kind === 'HUMAN' && mine && (
            <button className="btn-ghost !px-2 !py-1 !text-xs" disabled={busy} onClick={onPickDeck}>
              {seat.ready ? 'Deck ändern' : 'Deck wählen'}
            </button>
          )}
          {seat.kind === 'OPEN' && isHost && (
            <button className="btn-ghost !px-2 !py-1 !text-xs" disabled={busy} onClick={onSetBot}>
              Bot hinsetzen
            </button>
          )}
          {seat.kind === 'BOT' && isHost && (
            <>
              <button className="btn-ghost !px-2 !py-1 !text-xs" disabled={busy} onClick={onPickDeck}>
                Deck
              </button>
              <button className="btn-ghost !px-2 !py-1 !text-xs" disabled={busy} onClick={onSetOpen} title="Platz wieder freigeben">
                ✕
              </button>
            </>
          )}
        </div>
      )}
    </div>
  )
}
