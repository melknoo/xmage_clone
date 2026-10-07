import type { CSSProperties } from 'react'
import type { FxEvent } from '../api/types'
import {
  Bot,
  BookCopy,
  ChartColumn,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronsUp,
  CirclePlus,
  CircleUser,
  Copy,
  Cpu,
  Crosshair,
  Crown,
  Download,
  Eye,
  EyeOff,
  FastForward,
  Flag,
  Flame,
  Gauge,
  Hand,
  Heart,
  History,
  Info,
  KeyRound,
  LayoutGrid,
  Layers,
  Layers2,
  Link,
  LogIn,
  LogOut,
  Mail,
  Menu,
  MessageSquare,
  Minimize2,
  Minus,
  Moon,
  Orbit,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  ScanEye,
  Search,
  SendHorizontal,
  Shield,
  Shuffle,
  Skull,
  Star,
  Swords,
  Ticket,
  Trash2,
  TriangleAlert,
  Trophy,
  Undo2,
  User,
  UserCheck,
  UserPlus,
  UserX,
  Users,
  Volume2,
  VolumeX,
  WifiOff,
  X,
  Zap,
  type LucideIcon,
} from 'lucide-react'

/** Semantische Icon-Namen (Mapping laut Handoff-README "Icons" und Designsystem). */
export type IconName =
  // Navigation
  | 'held'
  | 'play'
  | 'decks'
  | 'stats'
  | 'invites'
  | 'toTable'
  | 'lobby'
  | 'account'
  // Zonen und Brett
  | 'library'
  | 'hand'
  | 'graveyard'
  | 'exile'
  | 'commander'
  | 'cmdDamage'
  | 'sick'
  | 'stack'
  | 'autoMana'
  | 'autoPass'
  | 'sound'
  | 'muted'
  | 'menu'
  | 'bot'
  | 'thinking'
  | 'human'
  | 'disconnected'
  | 'chat'
  | 'history'
  | 'target'
  | 'attacker'
  | 'blocker'
  | 'chosen'
  | 'tempo'
  | 'revealed'
  // Zustaende
  | 'error'
  | 'warning'
  | 'info'
  | 'success'
  // Aktionen
  | 'search'
  | 'random'
  | 'import'
  | 'link'
  | 'edit'
  | 'delete'
  | 'copy'
  | 'rotate'
  | 'code'
  | 'minimize'
  | 'concede'
  | 'spectate'
  | 'logout'
  | 'close'
  | 'start'
  | 'plus'
  | 'minus'
  | 'kick'
  // Belohnung
  | 'win'
  | 'streak'
  | 'mastery'
  | 'levelUp'
  // Social
  | 'addFriend'
  | 'friend'
  | 'friends'
  | 'invite'
  | 'send'
  | 'invisible'
  // Aufklappen
  | 'chevronDown'
  | 'chevronRight'
  // FX / Ereignisse
  | 'life'
  | 'damage'
  | 'counter'
  | 'countered'
  | 'resolved'
  | 'bounced'
  | 'tucked'
  | 'milled'
  | 'discarded'

export const ICONS: Record<IconName, LucideIcon> = {
  held: Shield,
  play: Swords,
  decks: Layers,
  stats: ChartColumn,
  invites: Ticket,
  toTable: LogIn,
  lobby: LayoutGrid,
  account: CircleUser,

  library: BookCopy,
  hand: Hand,
  graveyard: Skull,
  exile: Orbit,
  commander: Crown,
  cmdDamage: Swords,
  sick: Moon,
  stack: Layers2,
  autoMana: Zap,
  autoPass: FastForward,
  sound: Volume2,
  muted: VolumeX,
  menu: Menu,
  bot: Bot,
  thinking: Cpu,
  human: User,
  disconnected: WifiOff,
  chat: MessageSquare,
  history: History,
  target: Crosshair,
  attacker: Swords,
  blocker: Shield,
  chosen: Check,
  tempo: Gauge,
  revealed: ScanEye,

  error: TriangleAlert,
  warning: TriangleAlert,
  info: Info,
  success: Check,

  search: Search,
  random: Shuffle,
  import: Download,
  link: Link,
  edit: Pencil,
  delete: Trash2,
  copy: Copy,
  rotate: RefreshCw,
  code: KeyRound,
  minimize: Minimize2,
  concede: Flag,
  spectate: Eye,
  logout: LogOut,
  close: X,
  start: Play,
  plus: Plus,
  minus: Minus,
  kick: UserX,

  win: Trophy,
  streak: Flame,
  mastery: Star,
  levelUp: ChevronsUp,

  addFriend: UserPlus,
  friend: UserCheck,
  friends: Users,
  invite: Mail,
  send: SendHorizontal,
  invisible: EyeOff,

  chevronDown: ChevronDown,
  chevronRight: ChevronRight,

  life: Heart,
  damage: Swords,
  counter: CirclePlus,
  countered: X,
  resolved: Check,
  bounced: Undo2,
  tucked: BookCopy,
  milled: Skull,
  discarded: Skull,
}

export interface IconProps {
  name: IconName
  /** px; Raster 14/16/20/24 */
  size?: number
  /** Strich 1,5 (Karten-Abzeichen 2,4, Mond 2) */
  strokeWidth?: number
  className?: string
  /** Tooltip und zugaenglicher Name; ohne title ist das Icon aria-hidden */
  title?: string
  style?: CSSProperties
}

/** Lucide-Icon mit den Design-Defaults (Strich 1,5, currentColor, runde Enden). */
export function Icon({ name, size = 16, strokeWidth = 1.5, className, title, style }: IconProps) {
  const C = ICONS[name]
  return (
    <C
      size={size}
      strokeWidth={strokeWidth}
      className={className ? `shrink-0 ${className}` : 'shrink-0'}
      style={style}
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      aria-label={title}
    >
      {title ? <title>{title}</title> : null}
    </C>
  )
}

/** FX-Arten (api/types.ts FxEvent['kind']) -> Icon */
export const FX_ICON: Record<FxEvent['kind'], IconName> = {
  died: 'graveyard',
  tokenDied: 'graveyard',
  discarded: 'discarded',
  milled: 'milled',
  exiled: 'exile',
  bounced: 'bounced',
  tucked: 'tucked',
  command: 'commander',
  resolved: 'resolved',
  countered: 'countered',
  damage: 'damage',
  life: 'life',
  counter: 'counter',
}

/** wie FX_ICON, aber tolerant fuer neue Arten aus der Engine */
export function fxIcon(kind: string): IconName {
  return (FX_ICON as Record<string, IconName>)[kind] ?? 'info'
}
