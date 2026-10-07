import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import { api, endpoint } from './api/client'
import { takeTableFromUrl, tablesApi } from './api/tables'
import { Toaster } from './components/Toaster'
import { useCatalogStore } from './decks/catalog'
import { GameScreen } from './game/GameScreen'
import { screenFade } from './lib/motion'
import { AccountScreen } from './screens/AccountScreen'
import { AdminScreen } from './screens/AdminScreen'
import { DecksScreen } from './screens/DecksScreen'
import { HomeScreen } from './screens/HomeScreen'
import { LobbyScreen } from './screens/LobbyScreen'
import { LoginScreen } from './screens/LoginScreen'
import { PlaySetupScreen } from './screens/PlaySetupScreen'
import { StatsScreen } from './screens/StatsScreen'
import { TableScreen } from './screens/TableScreen'
import { BootScreen, type BootPhase } from './shell/BootScreen'
import { ConnectionBarSlot } from './shell/ConnectionBarSlot'
import { NavRail } from './shell/NavRail'
import { InviteCard } from './social/InviteCard'
import { takeInviteFromUrl, useAuth } from './store/auth'
import { onConnectionRecovered, useConn } from './store/conn'
import { useGame } from './store/game'
import { useNav } from './store/nav'
import { useSocial } from './store/social'
import { useTable } from './store/table'
import { pushToast } from './store/ui'

// Lokal startet die Engine in Sekunden; auf fly kann der Kaltstart (Maschine + Karten-DB) deutlich laenger dauern.
const HEALTH_TRIES = endpoint.mode === 'local' ? 40 : 360

type EngineState = 'wait' | 'account' | 'ok' | 'down'

/** Online: Tisch aus dem Link (#table=…) betreten oder an den eigenen Tisch zurueck. */
async function resumeTable(stopped: () => boolean) {
  if (useAuth.getState().mode !== 'server') return
  const fromUrl = takeTableFromUrl()
  try {
    const t = fromUrl ? await tablesApi.join(fromUrl) : await tablesApi.mine()
    if (stopped()) return
    useTable.getState().setTableId(t.id)
    useNav.getState().go('table')
  } catch (e) {
    if (!fromUrl) useTable.getState().setTableId(null)
    // Link-Tisch nicht betretbar (voll, laeuft, entfernt, geschlossen): sagen warum
    else if (!stopped()) pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
  }
}

/** Nach dem Anmelden: laufendes Spiel wieder aufnehmen, sonst an den eigenen Tisch (Server). */
async function resumeSession(stopped: () => boolean) {
  try {
    const cur = await api.get<{ gameId: string }>('/api/games/current')
    if (!stopped() && cur?.gameId && !useGame.getState().gameId) {
      useGame.getState().connect(cur.gameId)
      useNav.getState().go('game')
      return
    }
  } catch {
    /* kein laufendes Spiel */
  }
  if (!stopped()) await resumeTable(stopped)
}

/** Social-Snapshot: eigener Tisch (Feld kommt von O1; undefined = noch nicht bekannt/unterstuetzt) */
function selectMyTableId(s: unknown): string | null | undefined {
  const m = (s as { loaded?: boolean; myTable?: { id: string } | null }).myTable
  if (!(s as { loaded?: boolean }).loaded || m === undefined) return undefined
  return m?.id ?? null
}

export function App() {
  const screen = useNav((s) => s.screen)
  const gameId = useGame((s) => s.gameId)
  const authStatus = useAuth((s) => s.status)
  const mode = useAuth((s) => s.mode)
  const version = useConn((s) => s.version)
  /** wait: Engine/Server noch nicht erreichbar · account: erreichbar, Anmeldung/Spiel/Tisch wird geprueft */
  const [engine, setEngine] = useState<EngineState>('wait')
  const engineRef = useRef<EngineState>(engine)
  engineRef.current = engine
  const inGame = screen === 'game' && !!gameId
  const socialOn = engine === 'ok' && authStatus === 'ok' && mode === 'server' && !inGame

  // Lobby-Chat/Freunde/Einladungen: Polling außerhalb des Spiels (Server-Modus)
  useEffect(() => {
    const social = useSocial.getState()
    if (socialOn) social.start()
    else if (authStatus !== 'ok') {
      social.reset()
      useCatalogStore.getState().reset()
    } else social.stop()
  }, [socialOn, authStatus])

  // Verbindungsleiste erst nach dem Boot (vorher zeigt der Boot-Screen den Zustand)
  useEffect(() => {
    useConn.getState().setEnabled(engine === 'ok')
  }, [engine])

  // Verbindung wieder da: Social sofort neu laden, eigenen Tisch pruefen
  useEffect(
    () =>
      onConnectionRecovered(() => {
        const social = useSocial.getState()
        if (social.running) void social.refresh()
        if (useTable.getState().tableId) void useTable.getState().verify()
      }),
    [],
  )

  // Engine erreichbar? Angemeldet? Laufendes Spiel oder Tisch wieder aufnehmen?
  const retryBoot = useRef<() => void>(() => window.location.reload())
  useEffect(() => {
    let stop = false
    let tries = 0
    let timer: number | undefined
    let running = false
    const check = async () => {
      running = true
      try {
        const health = await api.get<{ version?: string } | null>('/api/health')
        if (stop) return
        if (health?.version) useConn.getState().setVersion(health.version)
        setEngine('account')
        const auth = useAuth.getState()
        const invite = takeInviteFromUrl()
        if (invite) {
          await auth.login(invite)
        }
        if (useAuth.getState().status !== 'ok') {
          await auth.load()
        }
        if (stop) return
        if (useAuth.getState().status === 'ok') await resumeSession(() => stop)
        if (!stop) setEngine('ok')
      } catch {
        tries++
        if (!stop) {
          setEngine(tries > HEALTH_TRIES ? 'down' : 'wait')
          timer = window.setTimeout(check, 500)
        }
      } finally {
        running = false
      }
    }
    // "Jetzt versuchen" im Boot-Screen: Zaehler zuruecksetzen und sofort neu pruefen
    retryBoot.current = () => {
      if (stop) return
      tries = 0
      setEngine('wait')
      if (running) return
      window.clearTimeout(timer)
      void check()
    }
    void check()
    return () => {
      stop = true
      window.clearTimeout(timer)
    }
  }, [])

  // Spaeterer Login (Login-Screen, Cookie abgelaufen): kurz "Lade Konto und Decks …", dann Spiel/Tisch aufnehmen
  const prevAuth = useRef(authStatus)
  useEffect(() => {
    const prev = prevAuth.current
    prevAuth.current = authStatus
    if (authStatus !== 'ok' || prev !== 'login' || engineRef.current !== 'ok') return
    setEngine('account')
    void resumeSession(() => false).finally(() => setEngine('ok'))
  }, [authStatus])

  // Entfernt-Erkennung ausserhalb von Lobby/Tisch: meldet der Social-Poll keinen eigenen Tisch mehr, nachfragen
  const myTableId = useSocial(selectMyTableId)
  const prevMyTable = useRef(myTableId)
  useEffect(() => {
    const prev = prevMyTable.current
    prevMyTable.current = myTableId
    const tableId = useTable.getState().tableId
    if (myTableId === null && prev && prev === tableId) void useTable.getState().verify()
  }, [myTableId])

  let body
  if (engine !== 'ok') {
    const local = endpoint.mode === 'local'
    const phase: BootPhase = engine === 'down' ? 'down' : engine === 'account' ? 'account' : local ? 'engine' : 'server'
    body = <BootScreen phase={phase} mode={local ? 'local' : 'server'} version={version ?? undefined} onRetry={() => retryBoot.current()} />
  } else if (authStatus === 'login') {
    body = <LoginScreen />
  } else if (screen === 'game' && gameId) {
    body = <GameScreen />
  } else {
    body = (
      <div className="flex h-full flex-col bg-bg-1">
        <ConnectionBarSlot />
        <div className="flex min-h-0 flex-1">
          <NavRail />
          {/* relative: Meta-Overlays (ui/Overlay) liegen innerhalb von <main> */}
          <main className="relative min-w-0 flex-1 overflow-hidden">
            {/* Screenwechsel: reine Ueberblendung <= 120 ms, alter und neuer Screen liegen kurz uebereinander */}
            <AnimatePresence initial={false}>
              <motion.div key={screen} className="absolute inset-0" {...screenFade}>
                {screen === 'home' && <HomeScreen />}
                {screen === 'play' && (mode === 'server' ? <LobbyScreen /> : <PlaySetupScreen />)}
                {screen === 'solo' && <PlaySetupScreen />}
                {screen === 'table' && <TableScreen />}
                {screen === 'decks' && <DecksScreen />}
                {screen === 'stats' && <StatsScreen />}
                {screen === 'admin' && <AdminScreen />}
                {screen === 'account' && <AccountScreen />}
              </motion.div>
            </AnimatePresence>
          </main>
        </div>
        {mode === 'server' && <InviteCard />}
      </div>
    )
  }

  return (
    <>
      {body}
      {/* ein Toast-System fuer alle Screens, auch im Spiel */}
      <Toaster />
    </>
  )
}
