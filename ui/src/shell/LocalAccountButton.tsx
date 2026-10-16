import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { Button, Popover, StatusDot } from '../components/ui'
import { Icon } from '../lib/icons'
import { useAuth } from '../store/auth'
import { useHostLink } from '../store/hostLink'

/**
 * Konto-Button der lokalen Desktop-App (NavRail unten, nur Electron mit `window.magelite.openOnline`), Gegenstueck
 * zu "Konto" im Server-Modus.
 * - Konto-Modus (Engine mit --account): Name sofort aus /api/me.account. Angemeldet: Initiale + gruener Punkt, Menue
 *   mit Konto, offenem Tisch ("Zum Tisch"/"Tisch schliessen"), "Zur Lobby", "Abmelden" (danach das Gate). Offline:
 *   Abzeichen "Offline"; Klick meldet wieder an (bzw. zeigt das Gate).
 * - Ohne Konto-Modus (alte Engines/Tests): Stand des Host-Links wie bisher, "Anmelden" laedt den Server.
 * Den Host-Link-Stand (Tisch, Spiele) fragt der Button im Konto-Modus nur bei offenem Menue ab - jede Abfrage
 * erreicht fly.
 */
export function LocalAccountButton({ openOnline }: { openOnline: () => void }) {
  const st = useHostLink((s) => s.st)
  const busy = useHostLink((s) => s.busy)
  const account = useAuth((s) => s.account)
  const acc = account && account.state !== 'DISABLED' ? account : null
  const [open, setOpen] = useState(false)
  const [retrying, setRetrying] = useState(false)

  // Konto-Modus: nur bei offenem Menue pollen; sonst (Host-Link-Anzeige) immer
  const poll = !acc || open
  useEffect(() => {
    if (!poll) return
    const { start, stop } = useHostLink.getState()
    start()
    return stop
  }, [poll])

  const offline = acc?.state === 'OFFLINE'
  const connected = acc ? acc.state === 'OK' : !!st?.connected
  const table = connected && st?.connected ? st?.table ?? null : null
  const game = st?.games?.[0]
  const name = (acc?.user?.name ?? st?.userName ?? '').trim()
  const serverHost = (acc?.server || window.magelite?.serverUrl || 'https://magelite.fly.dev').replace(/^https?:\/\//, '')
  const error = !acc && !connected ? st?.error ?? null : null

  useEffect(() => {
    if (!connected) setOpen(false)
  }, [connected])

  const title = connected
    ? `Online angemeldet als ${name || '…'} · ${serverHost}${table ? ` · Tisch „${table.name}“ offen` : ''}`
    : offline
      ? 'Offline – lokale Decks, Spiele zählen nicht fürs Konto. Klicken zum Anmelden.'
      : error
        ? `Online: ${error}`
        : `Online anmelden (${serverHost}) – mit Freunden spielen und Tische auf diesem Rechner hosten`

  const go = () => {
    setOpen(false)
    openOnline()
  }

  // Offline -> wieder anmelden: Engine prueft die gemerkte Session, ohne Session zeigt die App das Gate
  const reconnect = async () => {
    if (retrying) return
    setRetrying(true)
    try {
      // ueber Electron (hebt dort auch die Offline-Wahl auf); ohne Electron direkt an der Engine
      const online = window.magelite?.goOnline
      if (online) await online()
      else await api.post('/api/account/retry')
      await useAuth.getState().refreshAccount()
    } catch {
      /* Gate/Boot zeigt den Stand */
    } finally {
      setRetrying(false)
    }
  }

  const onClick = connected ? () => setOpen((o) => !o) : offline ? () => void reconnect() : () => openOnline()
  const label = connected ? 'Konto' : offline ? 'Offline' : 'Anmelden'

  return (
    <div className="relative flex w-full justify-center">
      <button
        type="button"
        aria-expanded={connected ? open : undefined}
        className={`flex cursor-pointer flex-col items-center gap-1.5 transition-colors duration-1 ${open ? 'text-fg-1' : 'text-fg-tab hover:text-fg-2'}`}
        onClick={onClick}
        disabled={retrying}
        title={open ? undefined : title}
        data-testid="nav-account"
        data-connected={connected ? 'true' : undefined}
        data-offline={offline ? 'true' : undefined}
      >
        <span className="relative flex">
          <span
            className="flex h-[34px] w-[34px] items-center justify-center rounded-sm bg-bg-4 font-display text-[16px] font-semibold uppercase leading-none"
            style={{ boxShadow: open ? '0 0 0 1px var(--color-ember)' : offline ? 'inset 0 0 0 1px var(--color-line-1)' : 'none' }}
          >
            {connected ? name.charAt(0) || '?' : <Icon name={offline ? 'disconnected' : 'account'} size={19} />}
          </span>
          {connected && !table && <StatusDot status="online" title="Online verbunden" className="absolute -right-1 -top-1 ring-2 ring-bg-1" />}
          {table && <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-ember ring-2 ring-bg-1" aria-label="Online-Tisch offen" role="img" />}
          {error && <span className="absolute -right-1 -top-1 h-2 w-2 rounded-full bg-attack ring-2 ring-bg-1" aria-label="Verbindungsproblem" role="img" />}
        </span>
        <span className="font-display text-[11px] font-semibold uppercase leading-none tracking-[.1em]">{retrying ? '…' : label}</span>
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
          {acc && (acc.queued ?? 0) > 0 && (
            <span className="text-[12px] leading-[1.3] text-fg-3" data-testid="account-queued">
              {acc.queued === 1 ? '1 Spiel wartet' : `${acc.queued} Spiele warten`} noch auf die Buchung
            </span>
          )}
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
