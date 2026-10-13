import { useEffect, useState } from 'react'
import { Button, Popover, StatusDot } from '../components/ui'
import { Icon } from '../lib/icons'
import { useHostLink } from '../store/hostLink'

/**
 * Konto-Button der lokalen Desktop-App (NavRail unten, nur Electron mit `window.magelite.openOnline`), Gegenstueck
 * zu "Konto" im Server-Modus. Nicht angemeldet: "Anmelden" laedt den Online-Server im selben Fenster (dessen
 * Login-Seite). Angemeldet (Host-Link verbunden): Initiale + gruener Punkt, Ember-Punkt bei offenem Online-Tisch;
 * Klick oeffnet das Menue rechts neben der Leiste (Konto, offener Tisch mit "Zum Tisch"/"Tisch schliessen",
 * "Zur Lobby", "Abmelden").
 */
export function LocalAccountButton({ openOnline }: { openOnline: () => void }) {
  const st = useHostLink((s) => s.st)
  const busy = useHostLink((s) => s.busy)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const { start, stop } = useHostLink.getState()
    start()
    return stop
  }, [])

  const connected = !!st?.connected
  const table = connected ? st?.table ?? null : null
  const game = st?.games?.[0]
  const name = st?.userName?.trim() || ''
  const serverHost = (window.magelite?.serverUrl || 'https://magelite.fly.dev').replace(/^https?:\/\//, '')
  const error = !connected ? st?.error ?? null : null

  useEffect(() => {
    if (!connected) setOpen(false)
  }, [connected])

  const title = connected
    ? `Online angemeldet als ${name || '…'} · ${serverHost}${table ? ` · Tisch „${table.name}“ offen` : ''}`
    : error
      ? `Online: ${error}`
      : `Online anmelden (${serverHost}) – mit Freunden spielen und Tische auf diesem Rechner hosten`

  const go = () => {
    setOpen(false)
    openOnline()
  }

  return (
    <div className="relative flex w-full justify-center">
      <button
        type="button"
        aria-expanded={connected ? open : undefined}
        className={`flex cursor-pointer flex-col items-center gap-1.5 transition-colors duration-1 ${open ? 'text-fg-1' : 'text-fg-tab hover:text-fg-2'}`}
        onClick={connected ? () => setOpen((o) => !o) : openOnline}
        title={open ? undefined : title}
        data-testid="nav-account"
        data-connected={connected ? 'true' : undefined}
      >
        <span className="relative flex">
          <span
            className="flex h-[34px] w-[34px] items-center justify-center rounded-sm bg-bg-4 font-display text-[16px] font-semibold uppercase leading-none"
            style={{ boxShadow: open ? '0 0 0 1px var(--color-ember)' : 'none' }}
          >
            {connected ? name.charAt(0) || '?' : <Icon name="account" size={19} />}
          </span>
          {connected && !table && <StatusDot status="online" title="Online verbunden" className="absolute -right-1 -top-1 ring-2 ring-bg-1" />}
          {table && <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-ember ring-2 ring-bg-1" aria-label="Online-Tisch offen" role="img" />}
          {error && <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-attack ring-2 ring-bg-1" aria-label="Verbindungsproblem" role="img" />}
        </span>
        <span className="font-display text-[11px] font-semibold uppercase leading-none tracking-[.1em]">{connected ? 'Konto' : 'Anmelden'}</span>
      </button>
      <Popover
        open={open && connected}
        onClose={() => setOpen(false)}
        width={290}
        variant="menu"
        testId="account-menu"
        style={{ top: 'auto', bottom: 0, left: 'calc(100% + 8px)' }}
      >
        <div className="mb-1 flex flex-col gap-[3px] border-b border-line-3 px-2.5 py-2">
          <span className="label" style={{ fontSize: 11 }}>
            Online angemeldet
          </span>
          <span className="truncate text-[15px] font-semibold leading-[1.25] text-fg-1">{name || '…'}</span>
          <span className="flex items-center gap-1.5 text-[12px] leading-[1.3] text-fg-3">
            <StatusDot status="online" />
            {serverHost}
          </span>
        </div>
        {table && (
          <div className="mb-1 flex flex-col gap-2 border-b border-line-3 px-2.5 pb-2.5 pt-1.5" data-testid="account-online-table">
            <span className="text-[13px] leading-[1.4] text-fg-2">
              <Icon name={table.locked ? 'lock' : 'lobby'} size={12} className="mr-1 inline-block align-[-1px] text-fg-3" />
              Dein Tisch <span className="font-semibold text-fg-1">„{table.name}“</span> ist noch offen · {table.humans}/4 Spieler
              {table.state === 'RUNNING' ? ` · Spiel läuft${game?.turn ? ` (Zug ${game.turn})` : ''}` : ''}
              {table.hosting === 'REMOTE' ? ' · auf diesem Rechner' : ''}
            </span>
            <div className="flex gap-2">
              <Button variant="primary" size="xs" icon="toTable" onClick={go} testId="account-online-open">
                Zum Tisch
              </Button>
              {table.host && (
                <Button
                  variant="ghost"
                  size="xs"
                  icon="close"
                  disabled={busy}
                  confirm={table.humans > 1 || table.state === 'RUNNING' ? 'Wirklich schließen?' : undefined}
                  onClick={() => void useHostLink.getState().closeTable()}
                  testId="account-online-close"
                >
                  Tisch schließen
                </Button>
              )}
            </div>
          </div>
        )}
        {!table && (
          <button
            type="button"
            className="flex w-full items-center gap-2 rounded-xs px-2.5 py-[9px] text-left text-[13.5px] leading-[1.2] text-fg-1 transition-colors duration-1 hover:bg-line-3"
            onClick={go}
            data-testid="account-online-open"
          >
            <Icon name="lobby" size={16} />
            Zur Lobby
          </button>
        )}
        {window.magelite?.logoutOnline && <LogoutRow running={(st?.games?.length ?? 0) > 0} busy={busy} onDone={() => setOpen(false)} />}
      </Popover>
    </div>
  )
}

/** "Abmelden" mit Zwei-Klick-Bestaetigung, solange auf diesem Rechner eine Online-Partie laeuft (sie endet dann). */
function LogoutRow({ running, busy, onDone }: { running: boolean; busy: boolean; onDone: () => void }) {
  const [armed, setArmed] = useState(false)
  const click = () => {
    if (running && !armed) {
      setArmed(true)
      return
    }
    onDone()
    void useHostLink.getState().logout()
  }
  return (
    <button
      type="button"
      className={`flex w-full items-center gap-2 rounded-xs px-2.5 py-[9px] text-left text-[13.5px] leading-[1.2] transition-colors duration-1 hover:bg-line-3 ${armed ? 'text-attack' : 'text-fg-1'}`}
      disabled={busy}
      onClick={click}
      onBlur={() => setArmed(false)}
      data-testid="account-logout"
    >
      <Icon name="logout" size={16} />
      {armed ? 'Partie endet – wirklich abmelden?' : 'Abmelden'}
    </button>
  )
}
