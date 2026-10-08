import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { Button, EmptyState, Wordmark } from '../components/ui'
import { useAuth } from '../store/auth'

/**
 * Oeffentliches (selbst registriertes) Konto, Server-Kontingent fuer diesen Monat aufgebraucht: der Server nimmt bis
 * Monatsende keine Anfragen dieses Kontos an (503 budget). Statt der App nur dieser Hinweis, Download und Abmelden.
 */
export function BudgetScreen() {
  const resetsAt = useAuth((s) => s.budgetResetsAt)
  const logout = useAuth((s) => s.logout)
  const [download, setDownload] = useState<string | null>(null)
  const inDesktop = typeof window !== 'undefined' && !!window.mageliteDesktop

  useEffect(() => {
    let alive = true
    api
      .get<{ available: boolean; url?: string }>('/api/download/info')
      .then((d) => alive && setDownload(d.available && d.url ? d.url : null))
      .catch(() => undefined)
    return () => {
      alive = false
    }
  }, [])

  const day = resetsAt ? new Date(resetsAt).toLocaleDateString('de-DE', { day: 'numeric', month: 'long', timeZone: 'UTC' }) : null

  return (
    <div className="flex h-full flex-col items-center justify-center gap-10 bg-bg-1 p-10" data-testid="budget-screen">
      <Wordmark size={40} />
      <EmptyState
        icon="tempo"
        title="Kontingent aufgebraucht"
        text={
          <>
            Der Server hat sein Laufzeit-Budget für diesen Monat erreicht. Registrierte Konten können {day ? `ab dem ${day}` : 'ab dem nächsten Monat'} wieder online
            spielen. Bis dahin läuft MageLite als App auf deinem PC – gegen Bots, mit deinen Decks.
          </>
        }
        primary={
          download && !inDesktop ? (
            <Button variant="primary" size="md" icon="desktop" onClick={() => (window.location.href = download)} testId="budget-download">
              App für Windows laden
            </Button>
          ) : undefined
        }
        secondary={
          <Button variant="secondary" onClick={() => void logout()} testId="budget-logout">
            Abmelden
          </Button>
        }
      />
    </div>
  )
}
