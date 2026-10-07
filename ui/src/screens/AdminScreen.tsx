import { useCallback, useEffect, useState } from 'react'
import { adminApi, type AdminUser, type ServerInfo } from '../api/admin'
import { Tabs } from '../components/ui'
import { useAuth } from '../store/auth'
import { pushToast } from '../store/ui'
import { InvitesTab } from './admin/InvitesTab'
import { ServerTab } from './admin/ServerTab'
import { errText, useWide } from './admin/shared'
import { UserDetailPanel } from './admin/UserDetailPanel'
import { UsersTab } from './admin/UsersTab'

type AdminTab = 'users' | 'invites' | 'server'

const KEY = 'magelite.adminTab'
/** Nutzerliste (Status) alle 10 s, Server-Uebersicht alle 5 s - nur solange der Tab offen ist */
const USERS_POLL_MS = 10_000
const SERVER_POLL_MS = 5_000

function loadTab(): AdminTab {
  try {
    const t = localStorage.getItem(KEY)
    return t === 'invites' || t === 'server' ? t : 'users'
  } catch {
    return 'users'
  }
}

/** Pollt `fn`, solange `on` gilt und das Fenster sichtbar ist; sofort beim Einschalten. */
function usePoll(on: boolean, ms: number, fn: () => Promise<void>) {
  useEffect(() => {
    if (!on) return
    void fn()
    const t = window.setInterval(() => {
      if (!document.hidden) void fn()
    }, ms)
    return () => window.clearInterval(t)
  }, [on, ms, fn])
}

/**
 * Admin-Bereich (nur Server-Modus, nur Admins): Tabs Nutzer (Liste + Detail rechts), Einladungen (Codes) und Server
 * (laufende Spiele, Tische, Speicher). Der Server prueft die Rolle selbst (403).
 */
export function AdminScreen() {
  const me = useAuth((s) => s.me)
  const [tab, setTabState] = useState<AdminTab>(loadTab)
  const [users, setUsers] = useState<AdminUser[] | null>(null)
  const [server, setServer] = useState<ServerInfo | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const wide = useWide()

  const setTab = (t: AdminTab) => {
    setTabState(t)
    try {
      localStorage.setItem(KEY, t)
    } catch {
      /* egal */
    }
  }

  const loadUsers = useCallback(async () => {
    try {
      setUsers(await adminApi.users())
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    }
  }, [])
  const loadServer = useCallback(async () => {
    try {
      setServer(await adminApi.server())
    } catch (e) {
      pushToast({ kind: 'error', text: errText(e) })
    }
  }, [])

  usePoll(tab === 'users', USERS_POLL_MS, loadUsers)
  usePoll(tab === 'server', SERVER_POLL_MS, loadServer)

  const closeDetail = useCallback(() => setSelected(null), [])

  return (
    <div className="flex h-full min-h-0" data-testid="admin-screen">
      <div className="h-full min-w-0 flex-1 overflow-y-auto scrollbar-thin">
        <div className="flex flex-col gap-4 px-8 py-[26px] board:gap-[26px] board:px-14 board:py-11">
          <div className="flex items-baseline gap-4">
            <h1 className="m-0 font-display text-[36px] font-semibold uppercase leading-none tracking-[.03em] text-fg-1">Admin</h1>
            <span className="text-[14px] text-fg-3">Nur für dich als Admin sichtbar</span>
          </div>
          <Tabs
            items={[
              { id: 'users', label: 'Nutzer', testId: 'admin-tab-users' },
              { id: 'invites', label: 'Einladungen', testId: 'admin-tab-invites' },
              { id: 'server', label: 'Server', testId: 'admin-tab-server' },
            ]}
            value={tab}
            onChange={setTab}
            className="max-w-[1200px]"
          />
          {tab === 'users' && <UsersTab users={users} meId={me?.id} selected={selected} onSelect={setSelected} compact={selected !== null && !wide} />}
          {tab === 'invites' && <InvitesTab onShowUsers={() => setTab('users')} />}
          {tab === 'server' && <ServerTab info={server} onChanged={() => void loadServer()} />}
        </div>
      </div>
      {tab === 'users' && selected !== null && <UserDetailPanel userId={selected} meId={me?.id} onClose={closeDetail} onChanged={() => void loadUsers()} />}
    </div>
  )
}
