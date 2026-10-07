import type { CSSProperties } from 'react'
import type { Friend, FriendStatus } from '../api/social'
import { Button, StatusDot } from '../components/ui'
import { Icon, type IconName } from '../lib/icons'
import { useCountdown } from './useCountdown'

/** Zustand des Einladen-Knopfs je Freund */
export type InviteState =
  | { kind: 'none' }
  /** einladbar ("Einladen"; offline ausgegraut) */
  | { kind: 'can' }
  /** schon eingeladen ("Eingeladen · m:ss", inert; nach Ablauf wieder "Einladen") */
  | { kind: 'sent'; expiresAt: number; serverNow?: number }
  /** sitzt schon am Tisch ("Am Tisch", inert) */
  | { kind: 'seated' }

export interface FriendRowProps {
  friend: Friend
  /** popover: Einladen-Popover · list: Freunde-Tab · table: Tisch-Screen */
  variant: 'popover' | 'list' | 'table'
  invite?: InviteState
  onInvite?: () => void
  /** Freund entfernen (Hover-Icon, nur list) */
  onRemove?: () => void
  /** Statuszeile statt der Standardtexte ("an deinem Tisch" ...) */
  statusText?: string
}

export const FRIEND_STATUS_TEXT: Record<FriendStatus, string> = { online: 'online', table: 'am Tisch', game: 'im Spiel', offline: 'offline' }
const STATUS_ICON: Record<FriendStatus, IconName | null> = { online: 'human', table: 'toTable', game: 'play', offline: null }

/** Masse je Variante (Prototyp Online: Popover, Freunde-Tab, Tisch) */
const V = {
  popover: { row: 'gap-2.5 rounded-sm p-2', name: 'text-[13.5px]', status: 'text-[11.5px]', icon: false, btn: { height: 30, padding: '0 10px', fontSize: 13 } },
  list: { row: 'gap-2.5 border-b border-line-1 py-[9px]', name: 'text-[14px]', status: 'text-[12px]', icon: true, btn: { height: 30, padding: '0 10px', fontSize: 13 } },
  table: { row: 'gap-2.5 border-b border-line-1 py-2.5', name: 'text-[14px]', status: 'text-[12px]', icon: true, btn: { height: 32, padding: '0 12px', fontSize: 14 } },
} as const

const inertBtn = 'inline-flex flex-none items-center whitespace-nowrap rounded-sm font-display font-semibold uppercase leading-none tracking-[.06em]'

/**
 * Eine Freundeszeile: Status-Punkt, Name, Statuszeile (mit Icon), optional Einladen-Knopf
 * ("Einladen" | "Eingeladen · m:ss" | "Am Tisch"; offline nicht einladbar) und Entfernen als Hover-Icon.
 */
export function FriendRow({ friend, variant, invite = { kind: 'none' }, onInvite, onRemove, statusText }: FriendRowProps) {
  const v = V[variant]
  const cd = useCountdown(invite.kind === 'sent' ? invite.expiresAt : null, invite.kind === 'sent' ? invite.serverNow : null)
  const offline = friend.status === 'offline'
  const kind = invite.kind === 'sent' && cd.expired ? 'can' : invite.kind
  const icon = STATUS_ICON[friend.status]
  const text = statusText ?? FRIEND_STATUS_TEXT[friend.status]
  const btnStyle: CSSProperties = v.btn

  return (
    <div className={`group flex items-center ${v.row}`} data-testid="friend-row" data-friend={friend.id} data-name={friend.name} data-status={friend.status}>
      <StatusDot status={friend.status} />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={`truncate font-semibold leading-[1.25] ${v.name} ${offline ? 'text-fg-3' : 'text-fg-1'}`}>{friend.name}</span>
        <span
          className={`flex min-w-0 items-center gap-[5px] leading-[1.3] text-fg-3 ${v.status}`}
          title={friend.status === 'table' && friend.tableName ? `am Tisch „${friend.tableName}“` : undefined}
        >
          {v.icon && icon && <Icon name={icon} size={12} className="flex-none" />}
          <span className={variant === 'popover' ? '' : 'truncate'}>{text}</span>
        </span>
      </div>
      {kind === 'can' && (
        <Button
          variant="secondary"
          size="xs"
          style={btnStyle}
          disabled={offline}
          title={offline ? 'Offline – erst einladbar, wenn er oder sie online ist' : undefined}
          testId="friend-invite"
          onClick={onInvite}
        >
          Einladen
        </Button>
      )}
      {kind === 'sent' && (
        <span
          className={`${inertBtn} text-ember`}
          style={{ ...btnStyle, boxShadow: 'inset 0 0 0 1px color-mix(in oklab, var(--color-ember) 50%, transparent)' }}
          data-testid="friend-invited"
          aria-disabled="true"
        >
          Eingeladen · {cd.text}
        </span>
      )}
      {kind === 'seated' && (
        <span className={`${inertBtn} text-fg-3`} style={btnStyle} data-testid="friend-seated" aria-disabled="true">
          Am Tisch
        </span>
      )}
      {onRemove && variant === 'list' && (
        <Button
          variant="icon"
          size="xs"
          icon="kick"
          title="Freund entfernen"
          aria-label="Freund entfernen"
          confirm="Entfernen?"
          className="-my-1 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 data-[armed=true]:opacity-100"
          onClick={onRemove}
        />
      )}
    </div>
  )
}
