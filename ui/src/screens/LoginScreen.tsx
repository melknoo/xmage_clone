import { useState, type FormEvent } from 'react'
import { PasswordInput } from '../components/PasswordInput'
import { useAuth } from '../store/auth'

/** Code zur Anzeige gruppieren: XXXX-XXXX-XXXX-XXXX (Eingabe bleibt tolerant, der Server normalisiert). */
function prettify(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
  return clean.replace(/(.{4})(?=.)/g, '$1-')
}

type Tab = 'code' | 'email'

function loadTab(): Tab {
  try {
    return localStorage.getItem('magelite.loginTab') === 'email' ? 'email' : 'code'
  } catch {
    return 'code'
  }
}

const INPUT = 'w-full rounded-xl border border-white/10 bg-ink-950/60 px-4 py-3 text-ink-100 outline-none placeholder:text-ink-500 focus:border-gold-400/60'

export function LoginScreen() {
  const login = useAuth((s) => s.login)
  const loginEmail = useAuth((s) => s.loginEmail)
  const busy = useAuth((s) => s.busy)
  const error = useAuth((s) => s.error)
  const [tab, setTabState] = useState<Tab>(loadTab)
  const [code, setCode] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

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
    if (tab === 'code') {
      if (code.trim()) await login(code)
    } else if (email.trim() && password) {
      await loginEmail(email.trim(), password)
    }
  }

  const canSubmit = tab === 'code' ? code.replace(/-/g, '').length >= 4 : email.includes('@') && password.length > 0

  return (
    <div className="bg-table flex h-full flex-col items-center justify-center gap-6 p-6">
      <div className="font-display text-5xl font-bold tracking-[0.2em] text-gold-300">MAGELITE</div>
      <form onSubmit={submit} className="glass flex w-full max-w-md flex-col gap-4 rounded-3xl p-8">
        <div className="flex rounded-xl bg-ink-950/60 p-1 ring-1 ring-white/10">
          {(['code', 'email'] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={`flex-1 rounded-lg px-3 py-1.5 text-sm font-semibold transition ${tab === t ? 'bg-gold-400/15 text-gold-300 ring-1 ring-gold-400/40' : 'text-ink-300 hover:text-ink-100'}`}
              onClick={() => setTab(t)}
            >
              {t === 'code' ? 'Einladungscode' : 'E-Mail & Passwort'}
            </button>
          ))}
        </div>
        {tab === 'code' ? (
          <>
            <div>
              <div className="text-lg font-semibold text-ink-100">Einladungscode</div>
              <div className="mt-1 text-sm text-ink-300">Du brauchst einen Code vom Gastgeber. Groß-/Kleinschreibung und Bindestriche sind egal.</div>
            </div>
            <input
              autoFocus
              value={code}
              onChange={(e) => setCode(prettify(e.target.value))}
              placeholder="XXXX-XXXX-XXXX-XXXX"
              spellCheck={false}
              autoComplete="off"
              className={`${INPUT} text-center font-mono text-lg tracking-[0.2em] !text-gold-200`}
            />
          </>
        ) : (
          <>
            <div>
              <div className="text-lg font-semibold text-ink-100">Mit Konto anmelden</div>
              <div className="mt-1 text-sm text-ink-300">Für Konten, die mit E-Mail und Passwort gesichert wurden. Passwort vergessen? Bitte den Gastgeber um einen neuen Code.</div>
            </div>
            <input autoFocus type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="E-Mail" className={INPUT} />
            <PasswordInput autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Passwort" className={INPUT} />
          </>
        )}
        {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}
        <button
          type="submit"
          disabled={busy || !canSubmit}
          className="rounded-xl bg-linear-to-br from-gold-300 to-gold-500 px-4 py-3 font-semibold text-ink-950 shadow-lg shadow-gold-500/20 transition hover:brightness-110 disabled:opacity-40"
        >
          {busy ? 'Anmelden …' : 'Anmelden'}
        </button>
      </form>
    </div>
  )
}
