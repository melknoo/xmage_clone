import { useState, type FormEvent } from 'react'
import { useAuth } from '../store/auth'

/** Code zur Anzeige gruppieren: XXXX-XXXX-XXXX-XXXX (Eingabe bleibt tolerant, der Server normalisiert). */
function prettify(raw: string): string {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16)
  return clean.replace(/(.{4})(?=.)/g, '$1-')
}

export function LoginScreen() {
  const login = useAuth((s) => s.login)
  const busy = useAuth((s) => s.busy)
  const error = useAuth((s) => s.error)
  const [code, setCode] = useState('')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!code.trim()) return
    await login(code)
  }

  return (
    <div className="bg-table flex h-full flex-col items-center justify-center gap-6 p-6">
      <div className="font-display text-5xl font-bold tracking-[0.2em] text-gold-300">MAGELITE</div>
      <form onSubmit={submit} className="glass flex w-full max-w-md flex-col gap-4 rounded-3xl p-8">
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
          className="w-full rounded-xl border border-white/10 bg-ink-950/60 px-4 py-3 text-center font-mono text-lg tracking-[0.2em] text-gold-200 outline-none placeholder:text-ink-500 focus:border-gold-400/60"
        />
        {error && <div className="rounded-lg bg-blood-500/15 px-3 py-2 text-sm text-blood-300">{error}</div>}
        <button
          type="submit"
          disabled={busy || code.replace(/-/g, '').length < 4}
          className="rounded-xl bg-linear-to-br from-gold-300 to-gold-500 px-4 py-3 font-semibold text-ink-950 shadow-lg shadow-gold-500/20 transition hover:brightness-110 disabled:opacity-40"
        >
          {busy ? 'Anmelden …' : 'Anmelden'}
        </button>
      </form>
    </div>
  )
}
