import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { api } from '../api/client'
import { Button, Segmented, TextField, Wordmark } from '../components/ui'
import { PasswordInput } from '../components/PasswordInput'
import { Turnstile } from '../components/Turnstile'
import { Icon } from '../lib/icons'
import { useAuth, type AuthOptions } from '../store/auth'
import { useConn } from '../store/conn'

/** Commander-Art rechts: direkt von Scryfall (kein Binary im Repo, /img ist vor dem Login gesperrt) */
const LOGIN_ART = 'https://api.scryfall.com/cards/named?exact=Kaalia%20of%20the%20Vast&format=image&version=art_crop'

/** Code zur Anzeige gruppieren: XXXX-XXXX-XXXX-XXXX (Eingabe bleibt tolerant, der Server normalisiert). */
function prettify(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
  return clean.replace(/(.{4})(?=.)/g, '$1-')
}

type Tab = 'code' | 'email' | 'signup'
/** Unteransichten: Formular, "Passwort vergessen", "Mail ist unterwegs" */
type View = 'form' | 'forgot' | 'sent'

/** GET /api/download/info (oeffentlich): Link zum Setup der Server-Version (GitHub-Release) */
interface DownloadInfo {
  available: boolean
  version?: string
  url?: string
}

const SIGNUP_CLOSED_TEXT: Record<Exclude<AuthOptions['signup'], 'open' | 'closed'>, string> = {
  full: 'Alle Plätze sind vergeben. Wer einen Einladungscode hat, kommt trotzdem rein.',
  daily: 'Für heute sind alle Plätze vergeben – bitte morgen nochmal versuchen.',
  budget: 'Das Server-Kontingent für diesen Monat ist aufgebraucht. Ab dem 1. geht es weiter – bis dahin läuft MageLite als App auf deinem PC.',
}

function loadTab(): Tab {
  try {
    const t = localStorage.getItem('magelite.loginTab')
    return t === 'email' || t === 'signup' ? t : 'code'
  } catch {
    return 'code'
  }
}

const hint = 'text-[12.5px] leading-[1.45] text-fg-3'

/** Login (Server-Modus): links Formular 400 breit, rechts Commander-Art mit Verlauf. */
export function LoginScreen() {
  const login = useAuth((s) => s.login)
  const loginEmail = useAuth((s) => s.loginEmail)
  const busy = useAuth((s) => s.busy)
  const error = useAuth((s) => s.error)
  const options = useAuth((s) => s.options)
  const unverifiedEmail = useAuth((s) => s.unverifiedEmail)
  const resetToken = useAuth((s) => s.resetToken)
  const version = useConn((s) => s.version)
  const [tab, setTabState] = useState<Tab>(loadTab)
  const [view, setView] = useState<View>('form')
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [captcha, setCaptcha] = useState('')
  /** neu einhaengen nach jedem Versuch (Turnstile-Tokens gelten einmal) */
  const [captchaKey, setCaptchaKey] = useState(0)
  const [formError, setFormError] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState('')
  const [artOk, setArtOk] = useState(true)
  const [artLoaded, setArtLoaded] = useState(false)
  const [download, setDownload] = useState<DownloadInfo | null>(null)
  const inDesktop = typeof window !== 'undefined' && !!window.mageliteDesktop

  useEffect(() => {
    let alive = true
    api.get<DownloadInfo>('/api/download/info').then((d) => alive && setDownload(d)).catch(() => undefined)
    void useAuth.getState().loadOptions()
    return () => {
      alive = false
    }
  }, [])

  const signupShown = !!options && options.signup !== 'closed'
  const siteKey = options?.turnstileSiteKey ?? null
  const captchaOk = !siteKey || captcha.length > 0
  const activeTab: Tab = tab === 'signup' && !signupShown ? 'code' : tab

  const setTab = (t: Tab) => {
    setTabState(t)
    setView('form')
    setFormError(null)
    try {
      localStorage.setItem('magelite.loginTab', t)
    } catch {
      /* egal */
    }
  }

  const freshCaptcha = () => {
    setCaptcha('')
    setCaptchaKey((k) => k + 1)
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    setFormError(null)
    if (activeTab === 'code') {
      if (code.trim()) await login(code)
    } else if (activeTab === 'email') {
      if (email.trim() && password) await loginEmail(email.trim(), password)
    } else {
      const err = await useAuth.getState().signup({ name: name.trim(), email: email.trim(), password, captcha })
      freshCaptcha()
      if (err) setFormError(err)
      else {
        setSentTo(email.trim())
        setView('sent')
      }
    }
  }

  const resend = async (to: string) => {
    const err = await useAuth.getState().resend(to, captcha)
    freshCaptcha()
    setFormError(err ?? null)
    if (!err) {
      setSentTo(to)
      setView('sent')
    }
  }

  const sendForgot = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    const err = await useAuth.getState().forgot(email.trim(), captcha)
    freshCaptcha()
    setFormError(err ?? null)
    if (!err) {
      setSentTo(email.trim())
      setView('sent')
    }
  }

  const canSubmit =
    activeTab === 'code'
      ? code.replace(/-/g, '').length >= 4
      : activeTab === 'email'
        ? email.includes('@') && password.length > 0
        : options?.signup === 'open' && name.trim().length > 0 && email.includes('@') && password.length >= 8 && captchaOk

  let content: ReactNode
  if (resetToken) {
    content = <ResetForm />
  } else if (view === 'sent') {
    content = (
      <div className="flex flex-col gap-3" data-testid="signup-sent">
        <span className="font-display text-[20px] font-semibold uppercase tracking-[.04em] text-fg-1">Schau in dein Postfach</span>
        <span className="text-[14px] leading-normal text-fg-2">
          Wir haben eine Mail an <span className="text-fg-1">{sentTo}</span> geschickt (sofern dazu ein Konto passt). Der Link darin meldet dich direkt an.
        </span>
        <span className={hint}>Keine Mail? Spam-Ordner prüfen; nach 5 Minuten kannst du sie erneut anfordern.</span>
        <Button variant="secondary" onClick={() => setView('form')} testId="signup-back">
          Zurück zur Anmeldung
        </Button>
      </div>
    )
  } else if (view === 'forgot') {
    content = (
      <form onSubmit={sendForgot} className="flex flex-col gap-[22px]">
        <span className="text-[14px] leading-normal text-fg-2">Gib die E-Mail deines Kontos ein. Du bekommst einen Link, mit dem du ein neues Passwort setzt (gilt eine Stunde).</span>
        <TextField label="E-Mail" type="email" fieldHeight={44} autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@beispiel.de" error={formError ?? undefined} data-testid="forgot-email" />
        <Turnstile key={captchaKey} siteKey={siteKey} onToken={setCaptcha} />
        <Button type="submit" variant="primary" disabled={busy || !email.includes('@') || !captchaOk} testId="forgot-submit" style={{ width: '100%', height: 48, fontSize: 19 }}>
          {busy ? 'Senden …' : 'Link senden'}
        </Button>
        <Button variant="ghost" onClick={() => setView('form')}>
          Zurück
        </Button>
      </form>
    )
  } else {
    content = (
      <form onSubmit={submit} className="flex flex-col gap-[22px]">
        <Segmented<Tab>
          variant="boxed"
          ariaLabel="Anmeldeweg"
          className="w-full"
          itemStyle={{ flex: 1, padding: '9px 0' }}
          value={activeTab}
          onChange={setTab}
          items={[
            { id: 'code', label: 'Einladungscode', testId: 'login-tab-code' },
            { id: 'email', label: 'E-Mail', testId: 'login-tab-mail' },
            ...(signupShown ? [{ id: 'signup' as const, label: 'Registrieren', testId: 'login-tab-signup' }] : []),
          ]}
        />

        {activeTab === 'code' && (
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
            <span className={hint}>Den Code bekommst du vom Gastgeber deiner Runde. Danach spielst du als Gast und kannst später ein Konto sichern.</span>
          </div>
        )}

        {activeTab === 'email' && (
          <>
            <TextField key="email" label="E-Mail" type="email" fieldHeight={44} autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@beispiel.de" />
            <div className="flex flex-col gap-[7px]">
              <PasswordInput label="Passwort" fieldHeight={44} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={error ?? undefined} />
              {options?.forgot ? (
                <button type="button" className="self-start text-[12.5px] text-fg-3 underline-offset-2 hover:text-fg-1 hover:underline" onClick={() => setView('forgot')} data-testid="login-forgot">
                  Passwort vergessen?
                </button>
              ) : (
                <span className={hint}>Passwort vergessen? Melde dich mit deinem Einladungscode an und setze im Konto ein neues.</span>
              )}
            </div>
            {unverifiedEmail && (
              <div className="flex flex-col gap-2.5 rounded-sm bg-bg-2 p-3.5" style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }} data-testid="login-unverified">
                <span className="text-[13px] leading-normal text-fg-2">Die Mail mit dem Bestätigungslink nicht bekommen?</span>
                <Turnstile key={captchaKey} siteKey={siteKey} onToken={setCaptcha} />
                <Button variant="secondary" size="sm" disabled={busy || !captchaOk} onClick={() => void resend(unverifiedEmail)} testId="login-resend">
                  Mail erneut senden
                </Button>
                {formError && <span className="text-[12.5px] text-fg-3">{formError}</span>}
              </div>
            )}
          </>
        )}

        {activeTab === 'signup' && options && (
          <>
            {options.signup !== 'open' ? (
              <span className="text-[14px] leading-normal text-fg-2" data-testid="signup-closed">
                {SIGNUP_CLOSED_TEXT[options.signup as keyof typeof SIGNUP_CLOSED_TEXT]}
              </span>
            ) : (
              <>
                <TextField key="name" label="Name" fieldHeight={44} autoFocus maxLength={24} autoComplete="nickname" value={name} onChange={(e) => setName(e.target.value)} placeholder="So sehen dich die anderen" data-testid="signup-name" />
                <TextField label="E-Mail" type="email" fieldHeight={44} autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@beispiel.de" data-testid="signup-email" />
                <div className="flex flex-col gap-[7px]">
                  <PasswordInput label="Passwort" fieldHeight={44} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} error={formError ?? undefined} data-testid="signup-password" />
                  <span className={hint}>
                    Mindestens 8 Zeichen. Du bekommst eine Mail mit Bestätigungslink. Spiele gegen Bots laufen in der App auf deinem PC; online spielst du an Tischen von Freunden oder an deinem eigenen.
                  </span>
                </div>
                <Turnstile key={captchaKey} siteKey={siteKey} onToken={setCaptcha} />
              </>
            )}
          </>
        )}

        {!(activeTab === 'signup' && options?.signup !== 'open') && (
          <Button type="submit" variant="primary" kbd="Enter" disabled={busy || !canSubmit} testId={activeTab === 'signup' ? 'signup-submit' : 'login-submit'} style={{ width: '100%', height: 48, fontSize: 19 }}>
            {activeTab === 'signup' ? (busy ? 'Registrieren …' : 'Konto anlegen') : busy ? 'Anmelden …' : 'Anmelden'}
          </Button>
        )}
      </form>
    )
  }

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(0,1fr)] bg-bg-1">
      <div className="flex items-center justify-center overflow-y-auto p-10 scrollbar-thin">
        <div className="flex w-[400px] max-w-full flex-col gap-[22px]">
          <div className="flex flex-col gap-2.5">
            <Wordmark size={46} />
            <span className="text-[15px] leading-normal text-fg-3">Commander gegen Bots oder mit Freunden an einem Tisch.</span>
          </div>

          {content}

          <span className="text-[12.5px] text-fg-4">
            Server: {window.location.host}
            {version ? ` · v${version}` : ''}
            {' · '}
            <a href="/impressum.html" target="_blank" rel="noopener" className="text-fg-4 hover:text-fg-2">
              Impressum
            </a>
            {' · '}
            <a href="/datenschutz.html" target="_blank" rel="noopener" className="text-fg-4 hover:text-fg-2">
              Datenschutz
            </a>
          </span>

          {download?.available && download.url && !inDesktop && (
            <a
              href={download.url}
              rel="noopener"
              className="flex items-center gap-3.5 rounded-sm bg-bg-2 px-4 py-3.5 text-fg-1 no-underline transition-colors duration-1 hover:bg-bg-3"
              style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }}
              data-testid="login-download"
            >
              <Icon name="desktop" size={22} className="flex-none text-fg-2" />
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="font-display text-[16px] font-semibold uppercase leading-none tracking-[.04em]">
                  MageLite für Windows herunterladen
                  {download.version ? ` · v${download.version}` : ''}
                </span>
                <span className={hint}>Offline gegen Bots spielen und mit der App eigene Tische auf deinem PC hosten – der Server reicht dann nur durch.</span>
              </span>
              <Icon name="import" size={18} className="flex-none text-fg-3" />
            </a>
          )}
        </div>
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

/** Neues Passwort per Link aus der Mail (#reset=…); danach angemeldet. */
function ResetForm() {
  const busy = useAuth((s) => s.busy)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    setError(await useAuth.getState().resetPassword(password))
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-[22px]" data-testid="reset-form">
      <span className="text-[14px] leading-normal text-fg-2">Neues Passwort für dein Konto setzen. Danach bist du angemeldet, andere Geräte werden abgemeldet.</span>
      <PasswordInput label="Neues Passwort" fieldHeight={44} autoFocus autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} error={error ?? undefined} data-testid="reset-password" />
      <Button type="submit" variant="primary" disabled={busy || password.length < 8} testId="reset-submit" style={{ width: '100%', height: 48, fontSize: 19 }}>
        {busy ? 'Speichern …' : 'Passwort speichern'}
      </Button>
      <Button variant="ghost" onClick={() => useAuth.getState().setResetToken(null)}>
        Abbrechen
      </Button>
    </form>
  )
}
