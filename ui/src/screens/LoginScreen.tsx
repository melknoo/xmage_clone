import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import { Button, Segmented, TextField, Wordmark } from '../components/ui'
import { PasswordInput } from '../components/PasswordInput'
import { Icon } from '../lib/icons'
import { useAuth } from '../store/auth'
import { useConn } from '../store/conn'

/** Commander-Art rechts: direkt von Scryfall (kein Binary im Repo, /img ist vor dem Login gesperrt) */
const LOGIN_ART = 'https://api.scryfall.com/cards/named?exact=Kaalia%20of%20the%20Vast&format=image&version=art_crop'

/** Code zur Anzeige gruppieren: XXXX-XXXX-XXXX-XXXX (Eingabe bleibt tolerant, der Server normalisiert). */
function prettify(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
  return clean.replace(/(.{4})(?=.)/g, '$1-')
}

type Tab = 'code' | 'email'

/** GET /api/download/info (oeffentlich): neuestes Setup auf dem Server */
interface DownloadInfo {
  available: boolean
  version?: string
  bytes?: number
}

function loadTab(): Tab {
  try {
    return localStorage.getItem('magelite.loginTab') === 'email' ? 'email' : 'code'
  } catch {
    return 'code'
  }
}

/** Login (Server-Modus): links Formular 400 breit, rechts Commander-Art mit Verlauf. */
export function LoginScreen() {
  const login = useAuth((s) => s.login)
  const loginEmail = useAuth((s) => s.loginEmail)
  const busy = useAuth((s) => s.busy)
  const error = useAuth((s) => s.error)
  const version = useConn((s) => s.version)
  const [tab, setTabState] = useState<Tab>(loadTab)
  const [code, setCode] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [artOk, setArtOk] = useState(true)
  const [artLoaded, setArtLoaded] = useState(false)
  const [download, setDownload] = useState<DownloadInfo | null>(null)
  const inDesktop = typeof window !== 'undefined' && !!window.mageliteDesktop

  useEffect(() => {
    let alive = true
    api.get<DownloadInfo>('/api/download/info').then((d) => alive && setDownload(d)).catch(() => undefined)
    return () => {
      alive = false
    }
  }, [])

  const setTab = (t: Tab) => {
    setTabState(t)
    try {
      localStorage.setItem('magelite.loginTab', t)
    } catch {
      /* egal */
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    if (tab === 'code') {
      if (code.trim()) await login(code)
    } else if (email.trim() && password) {
      await loginEmail(email.trim(), password)
    }
  }

  const canSubmit = tab === 'code' ? code.replace(/-/g, '').length >= 4 : email.includes('@') && password.length > 0

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(0,1fr)] bg-bg-1">
      <div className="flex items-center justify-center overflow-y-auto p-10 scrollbar-thin">
        <form onSubmit={submit} className="flex w-[400px] max-w-full flex-col gap-[22px]">
          <div className="flex flex-col gap-2.5">
            <Wordmark size={46} />
            <span className="text-[15px] leading-normal text-fg-3">Commander gegen Bots oder mit Freunden an einem Tisch.</span>
          </div>

          <Segmented<Tab>
            variant="boxed"
            ariaLabel="Anmeldeweg"
            className="w-full"
            itemStyle={{ flex: 1, padding: '9px 0' }}
            value={tab}
            onChange={setTab}
            items={[
              { id: 'code', label: 'Einladungscode', testId: 'login-tab-code' },
              { id: 'email', label: 'E-Mail & Passwort', testId: 'login-tab-mail' },
            ]}
          />

          {tab === 'code' ? (
            <div className="flex flex-col gap-[7px]">
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
              />
              <span className="text-[12.5px] leading-[1.45] text-fg-3">
                Den Code bekommst du vom Gastgeber deiner Runde. Danach spielst du als Gast und kannst später ein Konto sichern.
              </span>
            </div>
          ) : (
            <>
              <TextField key="email" label="E-Mail" type="email" fieldHeight={44} autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@beispiel.de" />
              <div className="flex flex-col gap-[7px]">
                <PasswordInput
                  label="Passwort"
                  fieldHeight={44}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  error={error ?? undefined}
                />
                <span className="text-[12.5px] leading-[1.45] text-fg-3">Passwort vergessen? Melde dich mit deinem Einladungscode an und setze im Konto ein neues.</span>
              </div>
            </>
          )}

          <Button type="submit" variant="primary" kbd="Enter" disabled={busy || !canSubmit} testId="login-submit" style={{ width: '100%', height: 48, fontSize: 19 }}>
            {busy ? 'Anmelden …' : 'Anmelden'}
          </Button>

          <span className="text-[12.5px] text-fg-4">
            Server: {window.location.host}
            {version ? ` · v${version}` : ''}
          </span>

          {download?.available && !inDesktop && (
            <a
              href="/api/download/file"
              className="flex items-center gap-3.5 rounded-sm bg-bg-2 px-4 py-3.5 text-fg-1 no-underline transition-colors duration-1 hover:bg-bg-3"
              style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }}
              data-testid="login-download"
            >
              <Icon name="desktop" size={22} className="flex-none text-fg-2" />
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="font-display text-[16px] font-semibold uppercase leading-none tracking-[.04em]">
                  MageLite für Windows herunterladen
                  {download.version ? ` · v${download.version}` : ''}
                  {download.bytes ? ` · ${Math.round(download.bytes / 1048576)} MB` : ''}
                </span>
                <span className="text-[12.5px] leading-[1.45] text-fg-3">Offline gegen Bots spielen und mit der App eigene Tische auf deinem PC hosten – der Server reicht dann nur durch.</span>
              </span>
              <Icon name="import" size={18} className="flex-none text-fg-3" />
            </a>
          )}
        </form>
      </div>

      <div className="relative overflow-hidden bg-bg-3" aria-hidden>
        {artOk && (
          <img
            src={LOGIN_ART}
            alt=""
            draggable={false}
            className={`absolute inset-0 h-full w-full object-cover object-center transition-opacity duration-3 ${artLoaded ? 'opacity-100' : 'opacity-0'}`}
            onLoad={() => setArtLoaded(true)}
            onError={() => setArtOk(false)}
          />
        )}
        <div className="absolute inset-0" style={{ background: 'linear-gradient(90deg, var(--color-bg-1), rgba(18,17,16,.35) 40%, rgba(18,17,16,.2))' }} />
      </div>
    </div>
  )
}
