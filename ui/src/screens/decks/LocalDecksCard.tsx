import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { Button } from '../../components/ui'
import { invalidateDeckCatalog } from '../../decks/catalog'
import { Icon } from '../../lib/icons'
import { useAuth } from '../../store/auth'
import { pushToast } from '../../store/ui'

/** GET /api/account/local-decks: lokale Decks (Offline-Nutzung), die noch nicht im Online-Konto sind */
interface LocalDecks {
  decks: { id: number; name: string; commanders: string[] }[]
  dismissed: boolean
  imported?: number
  failed?: { id: number; name: string; error: string }[]
  error?: string
}

/**
 * Desktop-App, angemeldet: Hinweis-Karte "N Decks von diesem PC ins Konto uebernehmen". Uebernimmt Text, Ordner und
 * Bracket (nicht Meisterschaft/Verlauf); "Ausblenden" merkt sich die Engine je Konto.
 */
export function LocalDecksCard() {
  const account = useAuth((s) => s.account)
  const accountId = account?.state === 'OK' ? account.user?.id ?? null : null
  const [data, setData] = useState<LocalDecks | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (accountId === null) {
      setData(null)
      return
    }
    let alive = true
    api
      .get<LocalDecks>('/api/account/local-decks')
      .then((d) => alive && setData(d))
      .catch(() => alive && setData(null))
    return () => {
      alive = false
    }
  }, [accountId])

  if (!data || data.dismissed || data.decks.length === 0) return null
  const n = data.decks.length

  const importAll = async () => {
    if (busy) return
    setBusy(true)
    try {
      const res = await api.post<LocalDecks>('/api/account/import-local', {})
      setData(res)
      invalidateDeckCatalog()
      const failed = res.failed?.length ?? 0
      if (res.error) pushToast({ kind: 'error', text: `${res.error} – ${res.imported ?? 0} von ${n} übernommen` })
      else if (failed > 0) pushToast({ kind: 'error', text: `${res.imported ?? 0} übernommen, ${failed} nicht: ${res.failed!.map((f) => f.name).join(', ')}` })
      else pushToast({ kind: 'success', text: res.imported === 1 ? '1 Deck ins Konto übernommen' : `${res.imported ?? n} Decks ins Konto übernommen` })
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
    }
  }

  const dismiss = async () => {
    try {
      setData(await api.post<LocalDecks>('/api/account/import-local/dismiss', {}))
    } catch (e) {
      pushToast({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    }
  }

  const names = data.decks.slice(0, 4).map((d) => d.name)
  return (
    <div
      className="flex flex-none items-center gap-4 rounded-md bg-bg-2 px-5 py-4"
      style={{ boxShadow: 'inset 0 0 0 1px var(--color-line-2)' }}
      data-testid="local-decks-card"
    >
      <Icon name="desktop" size={22} className="flex-none text-fg-2" />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="font-display text-[16px] font-semibold uppercase leading-none tracking-[.04em] text-fg-1">
          {n === 1 ? '1 Deck auf diesem PC' : `${n} Decks auf diesem PC`}
        </span>
        <span className="truncate text-[13px] leading-[1.45] text-fg-3">
          {names.join(' · ')}
          {n > names.length ? ` · +${n - names.length}` : ''} – aus der Offline-Nutzung. Ins Konto übernehmen? Meisterschaft und Verlauf bleiben lokal.
        </span>
      </div>
      <Button variant="ghost" size="sm" disabled={busy} onClick={() => void dismiss()} testId="local-decks-dismiss">
        Ausblenden
      </Button>
      <Button variant="primary" size="sm" icon="import" disabled={busy} onClick={() => void importAll()} testId="local-decks-import">
        {busy ? 'Übernehme …' : 'Übernehmen'}
      </Button>
    </div>
  )
}
