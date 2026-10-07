import { useState, type FormEvent, type ReactNode } from 'react'
import { PasswordInput } from '../components/PasswordInput'
import { Avatar, Button, TextField, Toggle } from '../components/ui'
import { Icon } from '../lib/icons'
import { relDay } from '../lib/format'
import { useAuth } from '../store/auth'
import { useSocial } from '../store/social'
import { pushToast } from '../store/ui'

const PW_MIN = 8

/** Abschnittslabel (Barlow 14 .14em, Versalien per CSS) */
function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <span className="label" style={{ fontSize: 14, letterSpacing: '.14em' }}>
      {children}
    </span>
  )
}

/**
 * Konto (Server-Modus): links Identitaet + Formular (Gast: "Konto sichern", sonst E-Mail/Passwort aendern mit aktuellem
 * Passwort), rechts Lobby-Chat-Sichtbarkeit und Sitzung mit "Abmelden". Der Einladungscode bleibt immer gueltig.
 */
export function AccountScreen() {
  const me = useAuth((s) => s.me)
  const busy = useAuth((s) => s.busy)
  const register = useAuth((s) => s.register)
  const updateAccount = useAuth((s) => s.updateAccount)
  const logout = useAuth((s) => s.logout)
  const chatIn = useSocial((s) => s.chatIn)
  const setIn = useSocial((s) => s.setIn)
  const [email, setEmail] = useState(() => me?.email ?? '')
  const [pw, setPw] = useState('')
  const [current, setCurrent] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [chatBusy, setChatBusy] = useState(false)

  if (!me) return null
  const secured = me.hasPassword

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!secured) {
      if (!email.includes('@')) return setError('Bitte eine gültige E-Mail eingeben.')
      if (pw.length < PW_MIN) return setError(`Das Passwort braucht mindestens ${PW_MIN} Zeichen.`)
      const err = await register(email.trim(), pw)
      if (err) setError(err)
      else {
        pushToast({ kind: 'success', text: 'Konto gesichert' })
        setPw('')
      }
      return
    }
    const patch: { current: string; email?: string; password?: string } = { current }
    if (email.trim() && email.trim().toLowerCase() !== (me.email ?? '').toLowerCase()) patch.email = email.trim()
    if (pw) {
      if (pw.length < PW_MIN) return setError(`Das Passwort braucht mindestens ${PW_MIN} Zeichen.`)
      patch.password = pw
    }
    if (!patch.email && !patch.password) return setError('Nichts zu ändern.')
    if (!current) return setError('Bitte das aktuelle Passwort eingeben.')
    const err = await updateAccount(patch)
    if (err) setError(err)
    else {
      pushToast({ kind: 'success', text: patch.password ? 'Gespeichert · andere Anmeldungen wurden abgemeldet' : 'Gespeichert' })
      setPw('')
      setCurrent('')
    }
  }

  const toggleChat = async (on: boolean) => {
    setChatBusy(true)
    const err = await setIn(on)
    setChatBusy(false)
    if (err) pushToast({ kind: 'error', text: err })
  }

  const session = me.session
  const host = window.location.host

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto px-8 py-[26px] scrollbar-thin board:gap-[26px] board:px-14 board:py-11">
      <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Konto</h1>
      <div className="grid max-w-[1100px] grid-cols-2 gap-12">
        {/* links: Identitaet + Formular */}
        <div className="flex min-w-0 flex-col gap-[22px]">
          <div className="flex items-center gap-4 border-b border-line-2 pb-5">
            <Avatar name={me.name} size={56} />
            <div className="flex min-w-0 flex-col gap-[5px]">
              <span className="truncate font-display text-[26px] font-semibold leading-none text-fg-1">{me.name}</span>
              <span className="text-[13px] text-fg-3">
                {secured ? 'Konto gesichert' : 'Gast'}
                {me.admin ? ' · Admin dieses Servers' : ''}
              </span>
            </div>
          </div>

          <form className="flex flex-col gap-3.5" onSubmit={submit} data-testid="account-form">
            <SectionLabel>{secured ? 'E-Mail und Passwort ändern' : 'Konto sichern'}</SectionLabel>
            <span className="text-[13.5px] leading-normal text-fg-2">
              {secured ? 'Anmeldung mit E-Mail und Passwort ist aktiv.' : 'Mit E-Mail und Passwort kannst du dich auf jedem Gerät anmelden. Level, Decks und Freunde bleiben erhalten.'}
            </span>
            <TextField fieldHeight={42} label="E-Mail" type="email" autoComplete="username" required={!secured} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="deine@mail.de" />
            <PasswordInput fieldHeight={42} label="Neues Passwort" autoComplete="new-password" minLength={PW_MIN} required={!secured} value={pw} onChange={(e) => setPw(e.target.value)} placeholder={`mindestens ${PW_MIN} Zeichen`} />
            {secured && <PasswordInput fieldHeight={42} label="Aktuelles Passwort" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} placeholder="zur Bestätigung" />}
            {error && (
              <span className="field-error" role="alert">
                <Icon name="error" size={14} />
                {error}
              </span>
            )}
            <Button type="submit" variant="primary" className="self-start" disabled={busy} testId="account-save">
              {busy ? 'Speichern …' : secured ? 'Speichern' : 'Konto sichern'}
            </Button>
            {secured && <span className="text-[12.5px] leading-[1.45] text-fg-3">Passwort vergessen? Mit deinem Einladungscode kommst du immer wieder rein.</span>}
          </form>
        </div>

        {/* rechts: Lobby-Chat + Sitzung */}
        <div className="flex min-w-0 flex-col gap-[22px]">
          <div className="flex flex-col gap-3 border-b border-line-2 pb-5">
            <SectionLabel>Lobby-Chat</SectionLabel>
            <Toggle checked={chatIn} disabled={chatBusy} onChange={(on) => void toggleChat(on)} label="In der Mitgliederliste sichtbar und Nachrichten empfangen" testId="account-chat-toggle" />
            <span className="text-[12.5px] leading-[1.45] text-fg-3">Wird für dein Konto gespeichert. Gleich wie „Verlassen“ und „Beitreten“ in der Startseite.</span>
          </div>
          <div className="flex flex-col gap-3">
            <SectionLabel>Sitzung</SectionLabel>
            <span className="text-[13.5px] text-fg-2" data-testid="account-session">
              Angemeldet auf {host}
              {session ? ` seit ${relDay(session.since)}` : ''}
            </span>
            <Button variant="danger" icon="logout" className="self-start" onClick={() => void logout()} testId="account-logout">
              Abmelden
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
