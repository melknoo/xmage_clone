import { useCallback, useEffect, useRef, useState } from 'react'
import { adminApi, type AdminUserDetail } from '../../api/admin'
import { Button, StatusDot } from '../../components/ui'
import { duration, placeColor, relDay } from '../../lib/format'
import { pushToast } from '../../store/ui'
import { ago, AdminSection, copyInviteLink, created, errText, sessionCodes, STATUS_LABEL } from './shared'

type Confirm = 'logout' | 'delete' | null

/**
 * Nutzer-Detail (rechte Spalte, 420 px): Kennzahlen, Aktionen (Abmelden, Code rotieren, Loeschen - nicht fuers eigene
 * Konto), letzte Partien, Decks und Sessions. Laedt bei jedem Wechsel und nach jeder Aktion neu.
 */
export function UserDetailPanel({ userId, meId, onClose, onChanged }: { userId: number; meId?: number; onClose: () => void; onChanged: () => void }) {
  const [d, setD] = useState<AdminUserDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState<Confirm>(null)
  const [freshCode, setFreshCode] = useState<string | null>(null)
  // per Ref: ein neues onClose des Elternteils (Polling) soll nicht neu laden
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  const load = useCallback(async () => {
    try {
      setD(await adminApi.user(userId))
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
      closeRef.current()
    }
  }, [userId])

  useEffect(() => {
    setD(null)
    setConfirm(null)
    setFreshCode(null)
    void load()
  }, [load])

  const run = async (fn: () => Promise<void>, reload = true) => {
    setBusy(true)
    try {
      await fn()
      if (reload) await load()
      onChanged()
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    } finally {
      setBusy(false)
      setConfirm(null)
    }
  }

  const u = d?.user
  const own = userId === meId

  return (
    <aside className="flex h-full min-h-0 w-[420px] flex-none flex-col border-l border-line-2 bg-bg-2" data-testid="admin-user-detail">
      <div className="flex flex-none items-start gap-3 px-[22px] pb-4 pt-[22px]">
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <span className="truncate font-display text-[28px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">{u?.name ?? '…'}</span>
          {u && (
            <span className="flex min-w-0 items-center gap-[7px] text-[13px] text-fg-2">
              <StatusDot status={u.status} />
              <span className="truncate">
                {STATUS_LABEL[u.status]}
                {u.tableName ? ` · ${u.tableName}` : ''} · zuletzt {ago(u.lastSeen)}
              </span>
            </span>
          )}
        </div>
        <Button variant="icon" icon="close" title="Schließen" aria-label="Schließen" onClick={onClose} testId="admin-detail-close" />
      </div>

      {u && d && (
        <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-[22px] pb-6 scrollbar-thin">
          <div className="grid grid-cols-3 gap-3">
            <Kpi label="Level" value={String(u.level)} sub={u.title} />
            <Kpi label="Spiele" value={String(u.games)} sub={`${u.wins} ${u.wins === 1 ? 'Sieg' : 'Siege'}`} />
            <Kpi label="Decks" value={String(u.decks)} sub={`${u.xp} XP`} />
          </div>
          <div className="flex flex-col gap-1 text-[13px] text-fg-3">
            <span>
              Konto seit {created(u.createdAt)} · {u.hasPassword && u.email ? <span className="text-fg-2">{u.email}</span> : 'Gast (nur Code)'}
              {u.tier === 'public' ? (u.verified ? ' · selbst registriert' : ' · selbst registriert, unbestätigt') : ''}
            </span>
            {u.lastGameAt && <span>Letzte Partie {relDay(u.lastGameAt)}</span>}
          </div>

          {!own && (
            <div className="flex flex-wrap gap-2" data-testid="admin-detail-actions">
              {confirm === 'logout' ? (
                <Button variant="dangerConfirm" size="sm" autoFocus disabled={busy} onBlur={() => setConfirm(null)} onClick={() => void run(async () => { await adminApi.logout(userId); pushToast({ kind: 'success', text: `${u.name} abgemeldet` }) })} testId="admin-logout-confirm">
                  Wirklich abmelden?
                </Button>
              ) : (
                <Button variant="secondary" size="sm" icon="logout" disabled={busy || u.sessions === 0} title={u.sessions === 0 ? 'Keine aktive Session' : 'Beendet alle Sessions und ein laufendes Spiel; der Code bleibt gültig'} onClick={() => setConfirm('logout')} testId="admin-logout">
                  Abmelden
                </Button>
              )}
              {!u.admin && (
                <Button
                  variant="secondary"
                  size="sm"
                  icon={u.tier === 'public' ? 'friend' : 'human'}
                  disabled={busy}
                  title={u.tier === 'public' ? 'Darf dann Server-Spiele starten und ist nicht vom Monatsbudget begrenzt' : 'Wie selbst registriert: keine Server-Spiele, fällt unters Monatsbudget'}
                  onClick={() =>
                    void run(async () => {
                      const tier = u.tier === 'public' ? 'friend' : 'public'
                      await adminApi.setTier(userId, tier)
                      pushToast({ kind: 'success', text: tier === 'friend' ? `${u.name} ist jetzt Freund` : `${u.name} ist jetzt öffentlich` })
                    })
                  }
                  testId="admin-detail-tier"
                >
                  {u.tier === 'public' ? 'Zum Freund machen' : 'Nur öffentlich'}
                </Button>
              )}
              <Button
                variant="secondary"
                size="sm"
                icon="rotate"
                disabled={busy}
                title="Neuer Einladungscode; der alte gilt nicht mehr, alle Sessions enden"
                onClick={() =>
                  void run(async () => {
                    const r = await adminApi.rotate(userId)
                    sessionCodes.set(userId, r.code)
                    setFreshCode(r.code)
                  })
                }
                testId="admin-detail-rotate"
              >
                Code rotieren
              </Button>
              {confirm === 'delete' ? (
                <Button
                  variant="dangerConfirm"
                  size="sm"
                  autoFocus
                  disabled={busy}
                  onBlur={() => setConfirm(null)}
                  onClick={() =>
                    void run(async () => {
                      await adminApi.remove(userId)
                      sessionCodes.delete(userId)
                      pushToast({ kind: 'success', text: `${u.name} gelöscht` })
                      onClose()
                    }, false)
                  }
                  testId="admin-detail-delete-confirm"
                >
                  Wirklich löschen?
                </Button>
              ) : (
                <Button variant="ghost" size="sm" icon="delete" disabled={busy} title="Konto, Decks und Profil löschen (Statistik bleibt)" onClick={() => setConfirm('delete')} testId="admin-detail-delete">
                  Löschen
                </Button>
              )}
            </div>
          )}

          {freshCode && (
            <div className="flex items-center gap-3 rounded-md bg-bg-3 px-3.5 py-3" style={{ boxShadow: 'inset 0 0 0 1px var(--color-target)' }} data-testid="admin-detail-code">
              <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                <span className="label" style={{ color: 'var(--color-target)' }}>
                  Neuer Code · nur jetzt sichtbar
                </span>
                <span className="font-mono text-[19px] font-medium leading-none tracking-[.1em] text-fg-1 select-all">{freshCode}</span>
              </div>
              <Button variant="secondary" size="sm" icon="copy" onClick={() => void copyInviteLink(freshCode)}>
                Link
              </Button>
            </div>
          )}

          <AdminSection title="Letzte Partien" testId="admin-detail-games">
            {d.games.length === 0 && <span className="text-[13px] text-fg-3">Noch keine Partie.</span>}
            {d.games.map((g) => (
              <div key={g.id} className="flex items-baseline gap-3 text-[13px]">
                <span className="w-5 flex-none text-right font-display text-[17px] font-semibold tabular-nums" style={{ color: placeColor(g.placement) }}>
                  {g.placement ?? '–'}
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-fg-1">{g.deckName ?? g.commander ?? 'Deck'}</span>
                  <span className="truncate text-[12px] text-fg-3">
                    {relDay(g.startedAt)}
                    {g.turns ? ` · ${g.turns} Züge` : ''}
                    {g.durationMs ? ` · ${duration(g.durationMs)}` : ''}
                    {g.tempo ? ` · ${g.tempo.toLowerCase()}` : ''}
                  </span>
                </span>
                {g.xp > 0 && <span className="flex-none text-[12px] text-target">+{g.xp} XP</span>}
              </div>
            ))}
          </AdminSection>

          <AdminSection title={`Decks (${d.decks.length})`} testId="admin-detail-decks">
            {d.decks.length === 0 && <span className="text-[13px] text-fg-3">Keine eigenen Decks.</span>}
            {d.decks.map((k) => (
              <div key={k.id} className="flex items-baseline gap-3 text-[13px]">
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-fg-1">{k.name}</span>
                  <span className="truncate text-[12px] text-fg-3">{k.commanders}</span>
                </span>
                <span className="flex-none text-[12px] text-fg-3">
                  {k.cards} Karten{k.valid ? '' : ' · ungültig'}
                </span>
              </div>
            ))}
          </AdminSection>

          <AdminSection title={`Sessions (${d.sessions.length})`} testId="admin-detail-sessions">
            {d.sessions.length === 0 && <span className="text-[13px] text-fg-3">Nicht angemeldet.</span>}
            {d.sessions.map((s) => (
              <div key={s.id} className="flex items-baseline gap-3 text-[13px]">
                <span className="flex-1 text-fg-2">{s.via === 'password' ? 'E-Mail + Passwort' : s.via === 'verify' ? 'E-Mail bestätigt' : s.via === 'reset' ? 'Passwort zurückgesetzt' : 'Einladungscode'}</span>
                <span className="flex-none text-[12px] text-fg-3">
                  seit {created(s.createdAt)} · aktiv {ago(s.lastSeen)}
                </span>
              </div>
            ))}
          </AdminSection>
        </div>
      )}
    </aside>
  )
}

function Kpi({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5 border-l border-line-2 pl-3">
      <span className="label">{label}</span>
      <span className="num text-[34px] leading-[.85] text-fg-1">{value}</span>
      <span className="truncate text-[12px] text-fg-3" title={sub}>
        {sub}
      </span>
    </div>
  )
}
