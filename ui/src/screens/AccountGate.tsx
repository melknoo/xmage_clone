import { useState, type FormEvent, type ReactNode } from 'react'
import { api } from '../api/client'
import { Button, Segmented, TextField, Wordmark } from '../components/ui'
import { PasswordInput } from '../components/PasswordInput'
import { Icon } from '../lib/icons'
import { useAuth, type AccountStatus } from '../store/auth'
import { useConn } from '../store/conn'
import { LoginArt } from './LoginScreen'

type Tab = 'code' | 'email'

const hint = 'text-[12.5px] leading-[1.45] text-fg-3'

/** Code zur Anzeige gruppieren: XXXX-XXXX-XXXX-XXXX (wie im Login des Servers) */
function prettify(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
  return clean.replace(/(.{4})(?=.)/g, '$1-')
}

function loadTab(): Tab {
  try {
    return localStorage.getItem('magelite.loginTab') === 'email' ? 'email' : 'code'
  } catch {
    return 'code'
  }
}

/** "Offline spielen": Electron merkt es sich fuer die Sitzung; ohne Electron (Vite) direkt an der Engine. */
async function goOffline() {
  const fn = window.magelite?.goOffline
  if (fn) await fn()
  else await api.post('/api/account/offline')
  await useAuth.getState().refreshAccount()
}

/**
 * Konto-Gate der Desktop-App: vor der App steht entweder das angemeldete Online-Konto oder diese Seite.
 * NONE/EXPIRED: Login (Code oder E-Mail; Electron meldet am Server an), Registrieren/Passwort vergessen auf der
 * Server-Seite; UNREACHABLE: erneut versuchen; LIMITED: Kontingent aufgebraucht. Immer: "Offline spielen"
 * (lokale Decks, zaehlt nicht fuers Konto).
 */
export function AccountGate({ account }: { account: AccountStatus }) {
  const version = useConn((s) => s.version)
  const desktop = typeof window !== 'undefined' ? window.magelite : undefined
  const canLogin = !!desktop?.loginOnline
  const [tab, setTabState] = useState<Tab>(loadTab)
  const [code, setCode] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [unverified, setUnverified] = useState(false)
  const server = (account.server || desktop?.serverUrl || '').replace(/^https?:\/\//, '')

  const setTab = (t: Tab) => {
    setTabState(t)
    setError(null)
    setUnverified(false)
    try {
      localStorage.setItem('magelite.loginTab', t)
    } catch {
      /* egal */
    }
  }

  const run = async (fn: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    try {
      await fn()
    } finally {
      setBusy(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const login = desktop?.loginOnline
    if (!login) return
    void run(async () => {
      setError(null)
      setUnverified(false)
      const res = await login(tab === 'code' ? { code } : { email: email.trim(), password })
      if (!res.ok) {
        setUnverified(!!res.unverified)
        setError(
          res.status === 401 ? (tab === 'code' ? 'Dieser Code ist nicht (mehr) gültig.' : 'E-Mail oder Passwort stimmen nicht.') : res.error || 'Anmeldung fehlgeschlagen',
        )
        return
      }
      setPassword('')
      await useAuth.getState().refreshAccount()
    })
  }

  const retry = () =>
    void run(async () => {
      await api.post('/api/account/retry')
      await useAuth.getState().refreshAccount()
    })

  const canSubmit = canLogin && (tab === 'code' ? code.replace(/-/g, '').length >= 4 : email.includes('@') && password.length > 0)

  let content: ReactNode
  if (account.state === 'UNREACHABLE' || account.state === 'LIMITED') {
    const limited = account.state === 'LIMITED'
    content = (
      <div className="flex flex-col gap-[18px]" data-testid="account-gate-problem" data-state={account.state}>
        <div className="flex items-start gap-2.5 text-[14px] leading-normal text-fg-2" role="alert">
          <Icon name={limited ? 'warning' : 'disconnected'} size={17} className="mt-0.5 flex-none text-attack" />
          <span>
            {limited
              ? `Das Server-Kontingent ist für diesen Monat aufgebraucht${account.resetsAt ? ` – ab ${new Date(account.resetsAt).toLocaleDateString('de-DE')} geht es weiter` : ''}. Bis dahin kannst du offline spielen.`
              : 'Der Server ist gerade nicht erreichbar. Deine Decks und dein Held liegen im Online-Konto.'}
          </span>
        </div>
        {account.error && !limited && <span className={hint}>{account.error}</span>}
        {account.user && (
          <span className={hint}>
            Angemeldet als <span className="text-fg-2">{account.user.name}</span>
          </span>
        )}
        {!limited && (
          <Button variant="primary" icon="rotate" disabled={busy} onClick={retry} testId="account-gate-retry" style={{ width: '100%', height: 48, fontSize: 19 }}>
            {busy ? 'Verbinde …' : 'Erneut versuchen'}
          </Button>
        )}
        {limited && desktop?.logoutOnline && (
          <Button
            variant="secondary"
            icon="logout"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await desktop.logoutOnline?.()
                await useAuth.getState().refreshAccount()
              })
            }
          >
            Anderes Konto
          </Button>
        )}
      </div>
    )
  } else {
    content = (
      <form onSubmit={submit} className="flex flex-col gap-[22px]" data-testid="account-gate-login">
        {account.state === 'EXPIRED' && (
          <div className="flex items-center gap-2.5 text-[13.5px] leading-[1.45] text-fg-2" role="status">
            <Icon name="info" size={16} className="flex-none text-fg-3" />
            Deine Anmeldung ist abgelaufen – bitte neu anmelden.
          </div>
        )}
        <Segmented<Tab>
          variant="boxed"
          ariaLabel="Anmeldeweg"
          className="w-full"
          itemStyle={{ flex: 1, padding: '9px 0' }}
          value={tab}
          onChange={setTab}
          items={[
            { id: 'code', label: 'Einladungscode', testId: 'gate-tab-code' },
            { id: 'email', label: 'E-Mail', testId: 'gate-tab-mail' },
          ]}
        />
        {tab === 'code' ? (
          <TextField
            key="code"
            label="Einladungscode"
            mono
            fieldHeight={44}
            autoFocus
            value={code}
            onChange={(e) => setCode(prettify(e.target.value))}
            placeholder="XXXX-XXXX-XXXX-XXXX"
            spellCheck={false}
            autoComplete="off"
            style={{ fontSize: 16, letterSpacing: '.12em' }}
            error={error ?? undefined}
            data-testid="gate-code"
          />
        ) : (
          <>
            <TextField key="email" label="E-Mail" type="email" fieldHeight={44} autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@beispiel.de" data-testid="gate-email" />
            <div className="flex flex-col gap-[7px]">
              <PasswordInput label="Passwort" fieldHeight={44} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={error ?? undefined} data-testid="gate-password" />
              {desktop?.openOnline && (
                <button type="button" className="self-start text-[12.5px] text-fg-3 underline-offset-2 hover:text-fg-1 hover:underline" onClick={() => desktop.openOnline?.({ hash: 'forgot' })} data-testid="gate-forgot">
                  Passwort vergessen?
                </button>
              )}
            </div>
            {unverified && <span className={hint}>Den Bestätigungslink findest du in der Mail zur Registrierung; danach hier anmelden.</span>}
          </>
        )}
        {!canLogin && <span className={hint}>Anmelden geht nur in der MageLite-App.</span>}
        <Button type="submit" variant="primary" kbd="Enter" disabled={busy || !canSubmit} testId="gate-submit" style={{ width: '100%', height: 48, fontSize: 19 }}>
          {busy ? 'Anmelden …' : 'Anmelden'}
        </Button>
        {desktop?.openOnline && (
          <span className={hint}>
            Noch kein Konto?{' '}
            <button type="button" className="text-fg-2 underline-offset-2 hover:text-fg-1 hover:underline" onClick={() => desktop.openOnline?.({ hash: 'signup' })} data-testid="gate-signup">
              Registrieren
            </button>{' '}
            – nach der Bestätigung per Mail meldest du dich hier an.
          </span>
        )}
      </form>
    )
  }

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(0,1fr)] bg-bg-1" data-testid="account-gate" data-state={account.state}>
      <div className="flex items-center justify-center overflow-y-auto p-10 scrollbar-thin">
        <div className="flex w-[400px] max-w-full flex-col gap-[22px]">
          <div className="flex flex-col gap-2.5">
            <Wordmark size={46} />
            <span className="text-[15px] leading-normal text-fg-3">Melde dich mit deinem Online-Konto an – deine Decks, dein Held und deine Statistik sind dann überall dieselben.</span>
          </div>

          {content}

          <div className="flex flex-col gap-2 border-t border-line-3 pt-[18px]">
            <Button variant="secondary" icon="desktop" disabled={busy} onClick={() => void run(goOffline)} testId="account-gate-offline" style={{ width: '100%' }}>
              Offline spielen
            </Button>
            <span className={hint}>Mit den Decks auf diesem PC; Spiele zählen dann nicht für dein Konto.</span>
          </div>

          <span className="text-[12.5px] text-fg-4">
            {server ? `Server: ${server}` : 'Online-Konto'}
            {version ? ` · v${version}` : ''}
          </span>
        </div>
      </div>
      <LoginArt />
    </div>
  )
}
