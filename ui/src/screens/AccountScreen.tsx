import { useState, type FormEvent } from 'react'
import { PasswordInput } from '../components/PasswordInput'
import { useAuth } from '../store/auth'

const INPUT = 'w-full rounded-xl border border-white/10 bg-ink-950/60 px-3 py-2 text-ink-100 outline-none placeholder:text-ink-500 focus:border-gold-400/60'

/**
 * Konto (Server-Modus): Gast-Konto mit E-Mail + Passwort sichern bzw. E-Mail/Passwort aendern, abmelden.
 * Der Einladungscode bleibt immer gueltig (Rueckweg, wenn das Passwort vergessen wurde).
 */
export function AccountScreen() {
  const me = useAuth((s) => s.me)
  const busy = useAuth((s) => s.busy)
  const register = useAuth((s) => s.register)
  const updateAccount = useAuth((s) => s.updateAccount)
  const logout = useAuth((s) => s.logout)
  const [email, setEmail] = useState('')
  const [pw1, setPw1] = useState('')
  const [pw2, setPw2] = useState('')
  const [current, setCurrent] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  if (!me) return null
  const secured = me.hasPassword

  const reset = () => {
    setEmail('')
    setPw1('')
    setPw2('')
    setCurrent('')
  }

  const submitRegister = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setDone(null)
    if (pw1 !== pw2) {
      setError('Die Passwörter stimmen nicht überein.')
      return
    }
    const err = await register(email.trim(), pw1)
    if (err) setError(err)
    else {
      setDone('Konto gesichert. Du kannst dich jetzt auch mit E-Mail und Passwort anmelden.')
      reset()
    }
  }

  const submitChange = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setDone(null)
    if (pw1 && pw1 !== pw2) {
      setError('Die Passwörter stimmen nicht überein.')
      return
    }
    const patch: { current: string; email?: string; password?: string } = { current }
    if (email.trim() && email.trim().toLowerCase() !== (me.email ?? '')) patch.email = email.trim()
    if (pw1) patch.password = pw1
    if (!patch.email && !patch.password) {
      setError('Nichts zu ändern.')
      return
    }
    const err = await updateAccount(patch)
    if (err) setError(err)
    else {
      setDone(patch.password ? 'Gespeichert. Andere Anmeldungen dieses Kontos wurden abgemeldet.' : 'Gespeichert.')
      reset()
    }
  }

  return (
    <div className="h-full overflow-y-auto p-10 scrollbar-thin">
      <div className="mx-auto flex max-w-2xl flex-col gap-6">
        <div>
          <h1 className="font-display text-3xl font-bold text-gold-300">Konto</h1>
          <p className="mt-1 text-sm text-ink-300">Dein Name, deine Decks, dein Held und deine Statistik hängen an diesem Konto.</p>
        </div>

        <div className="glass flex flex-wrap items-center gap-4 rounded-2xl p-5">
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-gold-400/15 text-2xl ring-1 ring-gold-400/40">👤</div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-display text-xl font-bold text-ink-100">{me.name}</span>
              {me.admin && <span className="rounded bg-gold-400/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-gold-300">Admin</span>}
              <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${secured ? 'bg-emerald-500/15 text-emerald-300' : 'bg-arcane-500/15 text-arcane-400'}`}>{secured ? 'Gesichert' : 'Gast'}</span>
            </div>
            <div className="mt-0.5 text-sm text-ink-300">{secured ? me.email : 'Angemeldet nur über den Einladungscode'}</div>
          </div>
          <button className="btn-ghost !text-xs" onClick={() => logout()}>
            Abmelden
          </button>
        </div>

        {!secured ? (
          <form onSubmit={submitRegister} className="glass flex flex-col gap-3 rounded-2xl p-5">
            <div className="text-sm font-semibold text-ink-200">Konto sichern</div>
            <div className="text-sm text-ink-300">
              Mit E-Mail und Passwort kannst du dich auch ohne den Code anmelden, z.&nbsp;B. auf einem anderen Gerät. Der Einladungscode bleibt trotzdem gültig. Es werden keine E-Mails verschickt.
            </div>
            <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="E-Mail" className={INPUT} />
            <div className="grid grid-cols-2 gap-2">
              <PasswordInput autoComplete="new-password" required minLength={8} value={pw1} onChange={(e) => setPw1(e.target.value)} placeholder="Passwort (mind. 8 Zeichen)" className={INPUT} />
              <PasswordInput autoComplete="new-password" required value={pw2} onChange={(e) => setPw2(e.target.value)} placeholder="Passwort wiederholen" className={INPUT} />
            </div>
            <Messages error={error} done={done} />
            <div className="flex justify-end">
              <button type="submit" className="btn-primary" disabled={busy || !email.includes('@') || pw1.length < 8}>
                {busy ? 'Speichern …' : 'Konto sichern'}
              </button>
            </div>
          </form>
        ) : (
          <form onSubmit={submitChange} className="glass flex flex-col gap-3 rounded-2xl p-5">
            <div className="text-sm font-semibold text-ink-200">E-Mail oder Passwort ändern</div>
            <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder={`Neue E-Mail (aktuell ${me.email})`} className={INPUT} />
            <div className="grid grid-cols-2 gap-2">
              <PasswordInput autoComplete="new-password" minLength={8} value={pw1} onChange={(e) => setPw1(e.target.value)} placeholder="Neues Passwort (mind. 8 Zeichen)" className={INPUT} />
              <PasswordInput autoComplete="new-password" value={pw2} onChange={(e) => setPw2(e.target.value)} placeholder="Neues Passwort wiederholen" className={INPUT} />
            </div>
            <PasswordInput autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} placeholder="Aktuelles Passwort (zur Bestätigung)" className={INPUT} />
            <Messages error={error} done={done} />
            <div className="flex justify-end">
              <button type="submit" className="btn-primary" disabled={busy || !current}>
                {busy ? 'Speichern …' : 'Speichern'}
              </button>
            </div>
          </form>
        )}

        <div className="rounded-2xl bg-ink-900/50 px-5 py-4 text-sm text-ink-300 ring-1 ring-white/5">
          <b className="text-ink-200">Passwort vergessen?</b> Der Einladungscode bleibt gültig. Bitte den Gastgeber um einen neuen Code, melde dich damit an und setze hier ein neues Passwort.
        </div>
      </div>
    </div>
  )
}

function Messages({ error, done }: { error: string | null; done: string | null }) {
  return (
    <>
      {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}
      {done && <div className="rounded-lg bg-emerald-500/15 px-3 py-2 text-sm text-emerald-300">{done}</div>}
    </>
  )
}
