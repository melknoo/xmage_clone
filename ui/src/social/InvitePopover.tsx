import { Popover } from '../components/ui'
import { InviteFriendsList } from './InviteFriendsList'

export interface InvitePopoverProps {
  open: boolean
  onClose: () => void
  tableId: string
  seatedIds: number[]
  /** Ausrichtung am Ausloeser (Standard rechts) */
  align?: 'left' | 'right'
}

/**
 * "Freunde einladen · gilt 10 min": Popover (300 px) 8 px unter dem Einladen-Knopf. Der Elternknoten muss relative sein.
 * Esc und Klick ausserhalb schliessen (Popover).
 */
export function InvitePopover({ open, onClose, tableId, seatedIds, align = 'right' }: InvitePopoverProps) {
  return (
    <Popover
      open={open}
      onClose={onClose}
      width={300}
      align={align}
      title="Freunde einladen · gilt 10 min"
      testId="invite-popover"
      offset={8}
      style={{ zIndex: 15 }}
      className="max-h-[min(420px,60vh)] overflow-y-auto scrollbar-thin"
    >
      <InviteFriendsList tableId={tableId} seatedIds={seatedIds} variant="popover" />
    </Popover>
  )
}
