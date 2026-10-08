import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { ApiError, cardImageUrl } from '../api/client'
import { tableLink, tablesApi, type Table, type TableSeat } from '../api/tables'
import type { DeckSpec, Tempo } from '../api/types'
import { Button, Chip, EmptyState, Segmented } from '../components/ui'
import { useDeckCatalog, type DeckInfo } from '../decks/catalog'
import { DeckPicker } from '../decks/DeckPicker'
import { Icon, type IconName } from '../lib/icons'
import { TEMPOS } from '../lib/tempo'
import { useHotkey } from '../lib/useHotkey'
import { ChatInput } from '../social/ChatInput'
import { ChatMessages, type ChatMessage } from '../social/ChatMessages'
import { InviteFriendsList } from '../social/InviteFriendsList'
import { useAuth } from '../store/auth'
import { useConn } from '../store/conn'
import { useGame } from '../store/game'
import { useNav } from '../store/nav'
import { useTable } from '../store/table'
import { pushToast } from '../store/ui'
import { pollPaused } from '../store/social'

const POLL_MS = 1500

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** Ein Tisch: 4 Plaetze, Deckwahl, Gastgeber-Steuerung (Bots, Entfernen, Tempo), Einladen, Chat, Start. Polling. */
export function TableScreen() {
  const tableId = useTable((s) => s.tableId)
  const setTableId = useTable((s) => s.setTableId)
  const go = useNav((s) => s.go)
  const connect = useGame((s) => s.connect)
  const gameId = useGame((s) => s.gameId)
  const me = useAuth((s) => s.me)
  const offline = useConn((s) => s.offline)
  const { decks, samples, describe } = useDeckCatalog()
  const [table, setTable] = useState<Table | null>(null)
  const [busy, setBusy] = useState(false)
  const [starting, setStarting] = useState(false)
  const [picker, setPicker] = useState<null | { seat: number; bot: boolean }>(null)
  const [nameEdit, setNameEdit] = useState<string | null>(null)
  const joinedGame = useRef<string | null>(null)

  /** frischer Stand: anzeigen und zentral pruefen, ob ich noch sitze (Entfernen durch den Gastgeber) */
  const accept = useCallback((t: Table, stamp?: number) => {
    if (stamp !== undefined && stamp !== useTable.getState().stamp) return
    if (useTable.getState().observe(t, stamp)) return
    setTable(t)
  }, [])

  const load = useCallback(async () => {
    if (!tableId) return
    const stamp = useTable.getState().stamp
    try {
      accept(await tablesApi.get(tableId), stamp)
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0
      // Netz/Server-Neustart/Rate-Limit: weiter pollen (Verbindungsleiste); 401 zeigt ohnehin den Login
      if (status === 0 || status >= 500 || status === 429 || status === 401) return
      if (stamp !== useTable.getState().stamp) return
      // Tisch weg (Gastgeber hat geschlossen) -> zurueck in die Lobby
      setTableId(null)
      setTable(null)
      pushToast({ kind: 'info', text: errText(e) })
      go('play')
    }
  }, [tableId, setTableId, go, accept])

  useEffect(() => {
    void load()
    const iv = window.setInterval(() => {
      if (!pollPaused()) void load()
    }, POLL_MS)
    return () => window.clearInterval(iv)
  }, [load])

  // Spiel gestartet -> an den Tisch setzen (einmal pro Spiel)
  useEffect(() => {
    if (!table || table.state !== 'RUNNING' || !table.gameId || table.mySeat === null) return
    if (joinedGame.current === table.gameId || gameId === table.gameId) return
    joinedGame.current = table.gameId
    connect(table.gameId)
    go('game')
  }, [table, gameId, connect, go])

  const run = async (fn: () => Promise<Table | unknown>, done?: string) => {
    setBusy(true)
    const stamp = useTable.getState().stamp
    try {
      const t = await fn()
      if (t && typeof t === 'object' && 'seats' in (t as Table)) accept(t as Table, stamp)
      if (done) pushToast({ kind: 'success', text: done })
      return true
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
      return false
    } finally {
      setBusy(false)
    }
  }

  const isHost = !!table?.host
  const lobby = table?.state === 'LOBBY'
  const seats = table?.seats ?? []
  const humans = seats.filter((s) => s.kind === 'HUMAN')
  const players = seats.filter((s) => s.kind !== 'OPEN').length
  const free = seats.filter((s) => s.kind === 'OPEN').length
  const unready = humans.filter((s) => !s.ready)
  const remote = table?.hosting === 'REMOTE'
  const hostLinkOk = !remote || table?.hostLinkOk !== false
  const canStart = !!table && isHost && lobby && players >= 2 && unready.length === 0 && hostLinkOk

  const start = async () => {
    if (!table || !canStart || busy) return
    setStarting(true)
    await run(() => tablesApi.start(table.id))
    setStarting(false)
  }

  useHotkey('Enter', () => void start(), { enabled: canStart && !busy && !picker && nameEdit === null })

  if (!tableId) {
    return (
      <EmptyState
        className="h-full px-8"
        icon="lobby"
        title="Du sitzt an keinem Tisch"
        text="Setz dich in der Lobby an einen Tisch oder eröffne einen eigenen."
        primary={
          <Button variant="primary" icon="lobby" onClick={() => go('play')}>
            Zur Lobby
          </Button>
        }
      />
    )
  }
  if (!table) {
    return <div className="flex h-full items-center justify-center text-[14px] text-fg-3">Lade Tisch …</div>
  }

  const mine = table.mySeat !== null ? table.seats[table.mySeat] : null
  const others = humans.filter((s) => !s.me).length
  const link = tableLink(table.id)

  const whose = isHost ? 'deinem' : `${table.hostName}s`
  let hint: string
  if (!lobby) hint = remote ? `Das Spiel läuft auf ${whose} Rechner.` : 'Das Spiel läuft.'
  else if (table.starting) hint = `Start auf ${whose} Rechner …`
  else if (!hostLinkOk) hint = isHost ? 'Deine MageLite-App ist nicht verbunden – App starten und dort anmelden.' : `${table.hostName}s MageLite-App ist gerade nicht verbunden.`
  else if (mine && !mine.ready) hint = 'Wähle zuerst dein Deck.'
  else if (!isHost) hint = `Warten auf ${table.hostName} …${free > 0 ? ' · freie Plätze bleiben leer' : ''}`
  else if (players < 2) hint = 'Mindestens zwei Spieler – setze einen Bot oder lade Freunde ein.'
  else if (unready.length === 1) hint = `${unready[0].name ?? 'Jemand'} wählt noch ein Deck${free > 0 ? ' · freie Plätze bleiben leer' : ''}`
  else if (unready.length > 1) hint = `Noch nicht alle haben ein Deck gewählt${free > 0 ? ' · freie Plätze bleiben leer' : ''}`
  else hint = free > 0 ? 'Freie Plätze bleiben leer' : 'Alle bereit'

  const leave = () =>
    run(async () => {
      // laufende Tisch-Polls verwerfen: sonst haelt observe() mein Verlassen fuer ein Entfernen
      useTable.setState((s) => ({ stamp: s.stamp + 1 }))
      await tablesApi.leave(table.id)
      setTableId(null)
      go('play')
    }, isHost ? 'Tisch geschlossen' : 'Tisch verlassen')

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link)
      pushToast({ kind: 'success', text: 'Einladungslink kopiert' })
    } catch {
      pushToast({ kind: 'error', text: `Kopieren nicht möglich – Link: ${link}` })
    }
  }

  const sendChat = async (text: string): Promise<string | null> => {
    const stamp = useTable.getState().stamp
    try {
      accept(await tablesApi.chat(table.id, text), stamp)
      return null
    } catch (e) {
      const msg = errText(e)
      pushToast({ kind: 'error', text: msg })
      return msg
    }
  }

  const chatMsgs: ChatMessage[] = (table.chat ?? []).map((c, i) => ({ id: `${c.ts}-${i}`, ts: c.ts, authorId: c.userId, name: c.name, text: c.text }))

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto px-8 py-[26px] scrollbar-thin board:gap-[26px] board:px-14 board:py-11">
      {/* Kopf: Label + Titel, Einladungslink */}
      <div className="flex items-center gap-4">
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="label">
            {isHost ? 'Tisch · Du bist Gastgeber' : `Tisch · Gastgeber ${table.hostName}`}
            {remote ? ` · auf ${whose} Rechner` : ''}
          </span>
          {isHost && nameEdit !== null ? (
            <input
              autoFocus
              value={nameEdit}
              maxLength={40}
              aria-label="Name des Tisches"
              onChange={(e) => setNameEdit(e.target.value)}
              onBlur={() => {
                const v = nameEdit.trim()
                setNameEdit(null)
                if (v && v !== table.name) void run(() => tablesApi.update(table.id, { name: v }))
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') e.currentTarget.blur()
                else if (e.key === 'Escape') setNameEdit(null)
              }}
              className="field font-display text-[30px] font-semibold"
              style={{ height: 44, letterSpacing: '.02em' }}
            />
          ) : (
            <h1
              className={`m-0 truncate font-display text-[36px] font-semibold leading-none tracking-[.02em] text-fg-1 ${isHost && lobby ? 'cursor-text' : ''}`}
              onClick={() => isHost && lobby && setNameEdit(table.name)}
              title={isHost && lobby ? 'Klicken zum Umbenennen' : undefined}
            >
              {table.name}
            </h1>
          )}
        </div>
        {!lobby && (
          <Chip tone="outline" icon="play">
            {table.turn ? `Spiel läuft · Zug ${table.turn}` : 'Spiel läuft'}
          </Chip>
        )}
        {table.locked && (
          <Chip tone="outline" icon="lock" title="Privater Tisch – Beitritt nur mit Passwort oder Einladung">
            Privat
          </Chip>
        )}
        {remote && !hostLinkOk && lobby && (
          <Chip tone="outline" icon="disconnected" title="Die MageLite-App des Gastgebers ist nicht verbunden">
            App nicht verbunden
          </Chip>
        )}
        <span className="flex-1" />
        <div className="flex h-10 min-w-0 items-center gap-2 rounded-sm bg-bg-0 pr-1.5 pl-3 font-mono text-[12.5px] font-medium text-fg-3" style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }}>
          <span className="truncate" title={link}>
            {link.replace(/^https?:\/\//, '')}
          </span>
          <button
            type="button"
            className="flex h-[30px] flex-none items-center gap-1.5 rounded-xs bg-bg-4 px-[9px] font-display text-[13px] font-semibold uppercase leading-none tracking-[.06em] text-fg-1 transition-colors duration-1 hover:bg-line-3"
            onClick={() => void copy()}
            data-testid="table-copy-link"
          >
            <Icon name="copy" size={14} />
            Kopieren
          </button>
        </div>
      </div>

      {/* Plaetze */}
      <div className="grid grid-cols-4 gap-3">
        {table.seats.map((s, i) => (
          <SeatCard
            key={i}
            n={i}
            seat={s}
            table={table}
            info={describe(s.deck)}
            busy={busy}
            onPickDeck={() => setPicker({ seat: i, bot: s.kind === 'BOT' })}
            onSetBot={() => void run(() => tablesApi.setSeat(table.id, i, 'BOT', null))}
            onSetOpen={() => void run(() => tablesApi.setSeat(table.id, i, 'OPEN'))}
            onKick={() => void run(() => tablesApi.setSeat(table.id, i, 'OPEN'), `${s.name ?? 'Spieler'} wurde vom Tisch entfernt`)}
          />
        ))}
      </div>

      {/* Einladen + Tempo | Tisch-Chat */}
      <div className="grid min-h-[220px] flex-1 grid-cols-2 gap-4 board:gap-[26px]">
        <div className="flex min-h-0 flex-col">
          <div className="flex items-baseline justify-between border-b border-line-3 pb-2.5">
            <span className="label" style={{ fontSize: 14, letterSpacing: '.14em' }}>
              Freunde einladen
            </span>
            <span className="text-[12px] text-fg-3">Einladung gilt 10 Minuten</span>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto scrollbar-thin">
            {lobby && mine && free > 0 ? (
              <InviteFriendsList tableId={table.id} seatedIds={humans.map((s) => s.userId ?? 0)} variant="panel" />
            ) : (
              <div className="py-3 text-[13px] text-fg-3">{!lobby ? 'Das Spiel läuft – Einladen geht wieder nach der Partie.' : free === 0 ? 'Alle Plätze sind besetzt.' : 'Setz dich an den Tisch, um Freunde einzuladen.'}</div>
            )}
          </div>
          <div className="mt-auto flex flex-col gap-2 pt-4" title={isHost ? undefined : 'Das Tempo stellt der Gastgeber'}>
            <span className="label">Bot-Tempo</span>
            <Segmented<Tempo>
              variant="boxed"
              ariaLabel="Bot-Tempo"
              className="self-start"
              itemStyle={{ padding: '8px 12px' }}
              value={table.tempo}
              disabled={!isHost || !lobby || busy}
              onChange={(t) => void run(() => tablesApi.update(table.id, { tempo: t }))}
              items={TEMPOS.map((t) => ({ id: t.key, label: t.label, title: `${t.desc} – ${t.title}`, testId: `table-tempo-${t.key.toLowerCase()}` }))}
            />
          </div>
        </div>

        <div className="flex min-h-0 flex-col rounded-md bg-bg-2" style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }}>
          <div className="label border-b border-line-2 px-4 py-3">Tisch-Chat</div>
          <ChatMessages className="flex-1 px-4 py-3" msgs={chatMsgs} meId={me?.id} empty={<span className="text-[13px] text-fg-4">Noch keine Nachrichten.</span>} />
          <div className="px-4 pt-2.5 pb-3.5">
            <ChatInput placeholder={offline ? 'Keine Verbindung' : 'Nachricht an den Tisch'} disabled={table.mySeat === null || offline} onSend={sendChat} />
          </div>
        </div>
      </div>

      {/* Fuss */}
      <div className="flex items-center gap-3 border-t border-line-2 pt-3.5">
        <Button
          variant="secondary"
          icon="logout"
          style={{ height: 44, padding: '0 18px', fontSize: 16 }}
          disabled={busy}
          confirm={isHost && others > 0 ? 'Wirklich schließen?' : undefined}
          onClick={() => void leave()}
          testId="table-leave"
          title={isHost ? 'Schließt den Tisch für alle – er bleibt sonst auch nach dem Spiel bestehen' : undefined}
        >
          {isHost ? 'Tisch schließen' : 'Tisch verlassen'}
        </Button>
        <span className="flex-1" />
        <span className="text-[13px] text-fg-3" data-testid="table-hint">
          {hint}
        </span>
        {isHost && (
          <Button variant="primary" icon="start" kbd="Enter" disabled={!canStart || busy} onClick={() => void start()} testId="table-start" style={{ height: 48, padding: '0 22px', fontSize: 19 }}>
            {starting ? (remote ? 'Starte auf deinem Rechner …' : 'Mische Decks …') : 'Spiel starten'}
          </Button>
        )}
      </div>

      {picker && (
        <DeckPicker
          decks={decks}
          samples={samples}
          allowRandom={picker.bot}
          forLabel={picker.bot ? `Platz ${picker.seat + 1}` : 'Dein Deck'}
          current={table.seats[picker.seat]?.deck ?? null}
          onClose={() => setPicker(null)}
          onPick={(spec: DeckSpec) => {
            const p = picker
            setPicker(null)
            if (p.bot) void run(() => tablesApi.setSeat(table.id, p.seat, 'BOT', spec))
            else void run(() => tablesApi.setMyDeck(table.id, spec))
          }}
        />
      )}
    </div>
  )
}

/** Aktionstext unter einem Platz (Barlow 12 .08em, Versalien per CSS) */
function SeatAction({ children, onClick, tone = 'ember', disabled, testId, title, onBlur }: { children: ReactNode; onClick: () => void; tone?: 'ember' | 'muted' | 'danger'; disabled?: boolean; testId?: string; title?: string; onBlur?: () => void }) {
  const color = tone === 'ember' ? 'text-ember hover:text-ember-hover' : tone === 'danger' ? 'text-attack' : 'text-fg-3 hover:text-fg-1'
  return (
    <button
      type="button"
      className={`font-display text-[12px] font-semibold uppercase leading-none tracking-[.08em] transition-colors duration-1 disabled:opacity-40 ${color}`}
      disabled={disabled}
      onClick={onClick}
      onBlur={onBlur}
      data-testid={testId}
      title={title}
    >
      {children}
    </button>
  )
}

function SeatCard({
  n,
  seat,
  table,
  info,
  busy,
  onPickDeck,
  onSetBot,
  onSetOpen,
  onKick,
}: {
  n: number
  seat: TableSeat
  table: Table
  info: DeckInfo | null
  busy: boolean
  onPickDeck: () => void
  onSetBot: () => void
  onSetOpen: () => void
  onKick: () => void
}) {
  const [kickArmed, setKickArmed] = useState(false)
  useEffect(() => setKickArmed(false), [seat.kind, seat.userId])
  const isHost = table.host
  const lobby = table.state === 'LOBBY'
  const open = seat.kind === 'OPEN'
  const bot = seat.kind === 'BOT'
  const mine = seat.kind === 'HUMAN' && !!seat.me
  const hostSeat = seat.kind === 'HUMAN' && seat.userId === table.hostUserId

  const art = !open ? (seat.commanderSet && seat.commanderNum ? cardImageUrl({ set: seat.commanderSet, num: seat.commanderNum }, { size: 'art_crop' }) : (info?.art ?? null)) : null

  const kindIcon: IconName = bot ? 'bot' : open ? 'addFriend' : 'human'
  const kindLabel = bot ? 'Bot' : open ? 'Offen' : 'Mensch'
  const ready = open ? null : bot || seat.ready ? 'ready' : 'choosing'

  const name = open ? 'Freier Platz' : bot ? 'Bot' : (seat.name ?? '?')
  let deck: string
  if (open) deck = isHost ? 'Bot setzen oder Freund einladen' : 'Freunde einladen oder Link teilen'
  else if (seat.deckTitle && seat.commander) deck = `${seat.deckTitle} · ${seat.commander}`
  else if (seat.deckTitle) deck = seat.deckTitle
  else if (info) deck = info.sub ? `${info.name} · ${info.sub}` : info.name
  else if (seat.deckName) deck = seat.deckName
  else deck = bot ? 'Zufälliges Deck' : 'Wählt noch ein Deck …'

  const cardStyle: CSSProperties = open
    ? { boxShadow: 'inset 0 0 0 1px var(--color-line-3)' }
    : mine
      ? { background: 'var(--color-bg-3)', boxShadow: 'inset 0 0 0 1px var(--color-ember)' }
      : { background: 'var(--color-bg-3)' }

  const actions: ReactNode[] = []
  if (lobby) {
    if (mine) {
      actions.push(
        <SeatAction key="deck" disabled={busy} onClick={onPickDeck} testId="seat-deck">
          {seat.ready ? 'Deck ändern' : 'Deck wählen'}
        </SeatAction>,
      )
    } else if (isHost && open) {
      actions.push(
        <SeatAction key="bot" disabled={busy} onClick={onSetBot} testId="seat-bot">
          Bot setzen
        </SeatAction>,
      )
    } else if (isHost && bot) {
      actions.push(
        <SeatAction key="deck" disabled={busy} onClick={onPickDeck} testId="seat-deck">
          Deck ändern
        </SeatAction>,
        <SeatAction key="free" tone="muted" disabled={busy} onClick={onSetOpen} testId="seat-free" title="Platz wieder freigeben">
          Freigeben
        </SeatAction>,
      )
    } else if (isHost && seat.kind === 'HUMAN') {
      actions.push(
        <SeatAction
          key="kick"
          tone={kickArmed ? 'danger' : 'muted'}
          disabled={busy}
          testId="seat-kick"
          title={kickArmed ? undefined : `${name} vom Tisch entfernen`}
          onBlur={() => setKickArmed(false)}
          onClick={() => {
            if (!kickArmed) {
              setKickArmed(true)
              return
            }
            setKickArmed(false)
            onKick()
          }}
        >
          {kickArmed ? 'Wirklich entfernen?' : 'Entfernen'}
        </SeatAction>,
      )
    }
  }

  return (
    <div className="flex min-w-0 flex-col overflow-hidden rounded-md" style={cardStyle} data-testid="table-seat" data-seat={n} data-kind={seat.kind}>
      <div className="relative h-[84px] bg-bg-4 bg-cover bg-center board:h-[130px]" style={art ? { backgroundImage: `url("${art}")` } : undefined}>
        <span className="chip absolute top-2.5 left-2.5" style={{ padding: '4px 6px', fontSize: 12, background: 'rgba(18,17,16,.88)', color: 'var(--color-fg-2)' }}>
          <Icon name={kindIcon} size={12} />
          {kindLabel}
        </span>
        {ready && (
          <Chip tone={ready === 'ready' ? 'chosen' : 'target'} fill size="sm" className="absolute top-2.5 right-2.5">
            {ready === 'ready' ? 'Bereit' : 'Wählt Deck'}
          </Chip>
        )}
      </div>
      <div className="flex min-w-0 flex-col gap-1.5 px-3.5 py-3">
        <div className="flex min-w-0 items-center gap-2">
          <span className={`truncate font-display text-[20px] font-semibold leading-none ${open ? 'text-fg-2' : 'text-fg-1'}`}>{name}</span>
          {hostSeat && <Icon name="commander" size={14} className="flex-none text-target" title="Gastgeber" />}
        </div>
        <span className="truncate text-[12.5px] text-fg-3" title={deck}>
          {deck}
        </span>
        <div className="mt-1 flex min-h-3 items-center gap-3.5">{actions}</div>
      </div>
    </div>
  )
}
