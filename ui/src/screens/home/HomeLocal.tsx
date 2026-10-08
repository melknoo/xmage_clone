import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { Button } from '../../components/ui'
import { Icon } from '../../lib/icons'
import { useNav } from '../../store/nav'
import { pushToast } from '../../store/ui'
import { HeroHeader } from './HeroHeader'
import { MasteryList } from './MasteryList'
import { QuickStartCard } from './QuickStartCard'
import { RecentGames } from './RecentGames'

/**
 * Held-Screen im lokalen Modus (Meta-Prototyp "Held"): Held-Kopf, Schnellstart + "Neues Spiel" (1.5fr/1fr),
 * Karte "Online spielen" (Electron: Server im selben Fenster, zeigt Host-Link-Stand und offenen Online-Tisch),
 * darunter "Letzte Partien" und "Deck-Meisterschaft". 1680: Rand 48/64, Abstand 28; unter 1440 px: 28/36, 18.
 */
export function HomeLocal() {
  const go = useNav((s) => s.go)
  const online = typeof window !== 'undefined' ? window.magelite?.openOnline : undefined

  return (
    <div className="h-full overflow-y-auto scrollbar-thin" data-testid="home-local">
      <div className="flex min-h-full flex-col gap-[18px] px-9 py-7 board:gap-7 board:px-16 board:py-12">
        <HeroHeader variant="local" />
        <div className="grid gap-[18px] board:gap-7" style={{ gridTemplateColumns: 'minmax(0,1.5fr) minmax(0,1fr)' }}>
          <QuickStartCard />
          <button
            type="button"
            className="outline-panel flex h-[170px] cursor-pointer flex-col justify-between px-6 py-5 text-left text-fg-1 transition-colors duration-1 hover:bg-bg-3 board:h-[230px] board:px-8 board:py-7"
            onClick={() => go('play')}
            data-testid="home-new-game"
          >
            <Icon name="play" size={28} />
            <span className="flex w-full items-end justify-between gap-4">
              <span className="flex min-w-0 flex-col gap-2">
                <span className="font-display text-[36px] font-semibold uppercase leading-none tracking-[.02em]">Neues Spiel</span>
                <span className="text-[14px] leading-[1.4] text-fg-3">Deck und Gegner wählen</span>
              </span>
              <Icon name="chevronRight" size={24} className="flex-none text-fg-3" />
            </span>
          </button>
        </div>
        {online && <OnlineCard open={online} />}
        <div className="grid min-h-[180px] flex-1 gap-[18px] board:gap-7" style={{ gridTemplateColumns: 'minmax(0,1.5fr) minmax(0,1fr)' }}>
          <RecentGames limit={3} withArt variant="local" />
          <MasteryList limit={5} />
        </div>
      </div>
    </div>
  )
}

/** GET /api/host/link (nur lokale Engine): Host-Link-Stand plus Konto/Tisch auf dem Server */
interface HostLinkStatus {
  enabled: boolean
  connected: boolean
  server: string | null
  error?: string | null
  games: { gameId: string; tableName: string | null; humans: number; bots: number; turn: number }[]
  userName?: string | null
  table?: { id: string; name: string; state: 'LOBBY' | 'RUNNING'; humans: number; host: boolean; hosting: string; locked: boolean } | null
}

const POLL_MS = 5000

/**
 * Karte "Online spielen": nicht verbunden -> ein Klick laedt den Server im Fenster. Verbunden (App hat sich nach dem
 * Login angebunden): zeigt Konto und - wichtig nach einem Neustart der App - den noch offenen Online-Tisch mit
 * "Zum Tisch" und "Tisch schließen" (Tische bleiben online bestehen, bis der Gastgeber sie schließt).
 */
function OnlineCard({ open }: { open: () => void }) {
  const serverHost = (typeof window !== 'undefined' && window.magelite?.serverUrl ? window.magelite.serverUrl : 'https://magelite.fly.dev').replace(/^https?:\/\//, '')
  const [st, setSt] = useState<HostLinkStatus | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    const load = () => {
      if (document.hidden) return
      api
        .get<HostLinkStatus>('/api/host/link')
        .then((s) => alive && setSt(s))
        .catch(() => alive && setSt(null))
    }
    load()
    const iv = window.setInterval(load, POLL_MS)
    return () => {
      alive = false
      window.clearInterval(iv)
    }
  }, [])

  const closeTable = async () => {
    setBusy(true)
    try {
      setSt(await api.post<HostLinkStatus>('/api/host/table/leave'))
      pushToast({ kind: 'success', text: 'Online-Tisch geschlossen' })
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
    }
  }

  const connected = !!st?.connected
  const table = connected ? st?.table ?? null : null
  const game = st?.games?.[0]

  if (!connected) {
    return (
      <button
        type="button"
        className="outline-panel flex cursor-pointer items-center gap-4 px-6 py-4 text-left text-fg-1 transition-colors duration-1 hover:bg-bg-3 board:px-8"
        onClick={open}
        data-testid="home-online"
      >
        <Icon name="lobby" size={24} className="flex-none" />
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="font-display text-[22px] font-semibold uppercase leading-none tracking-[.02em]">Online spielen · {serverHost}</span>
          <span className="text-[13px] leading-[1.4] text-fg-3">
            {st?.enabled && st.error
              ? `Verbindung zum Server: ${st.error}`
              : 'Mit Freunden an einem Tisch. Einmal angemeldet, kannst du dort Tische auf diesem Rechner hosten – deine Decks hier bleiben lokal, online nutzt du deine Server-Bibliothek.'}
          </span>
        </span>
        <Icon name="chevronRight" size={22} className="flex-none text-fg-3" />
      </button>
    )
  }

  return (
    <div className="outline-panel flex items-center gap-4 px-6 py-4 text-fg-1 board:px-8" data-testid="home-online" data-connected="true">
      <Icon name="lobby" size={24} className="flex-none" />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="font-display text-[22px] font-semibold uppercase leading-none tracking-[.02em]">
          Online · {serverHost}
          {st?.userName ? ` · als ${st.userName}` : ''}
        </span>
        {table ? (
          <span className="text-[13px] leading-[1.4] text-fg-2" data-testid="home-online-table">
            <Icon name={table.locked ? 'lock' : 'lobby'} size={12} className="mr-1 inline-block align-[-1px] text-fg-3" />
            Dein Tisch <span className="font-semibold text-fg-1">„{table.name}“</span> ist noch offen · {table.humans}/4 Spieler
            {table.state === 'RUNNING' ? ` · Spiel läuft${game?.turn ? ` (Zug ${game.turn})` : ''}` : ''}
            {table.hosting === 'REMOTE' ? ' · auf diesem Rechner' : ''}
          </span>
        ) : (
          <span className="text-[13px] leading-[1.4] text-fg-3">Verbunden – du kannst in der Lobby Tische auf diesem Rechner hosten.</span>
        )}
      </div>
      {table && table.host && (
        <Button variant="ghost" icon="logout" disabled={busy} confirm={table.humans > 1 || table.state === 'RUNNING' ? 'Wirklich schließen?' : undefined} onClick={() => void closeTable()} testId="home-online-close">
          Tisch schließen
        </Button>
      )}
      <Button variant="primary" icon="toTable" onClick={open} testId="home-online-open">
        {table ? 'Zum Tisch' : 'Zur Lobby'}
      </Button>
    </div>
  )
}
