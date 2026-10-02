// Protokoll Engine <-> UI (siehe docs/architecture.md, Abschnitt c)

export type UUID = string

export interface Counter {
  name: string
  count: number
}

export interface Card {
  id: UUID
  name: string
  set?: string
  num?: string
  image?: string
  imageNum?: number
  manaCost?: string
  mv?: number
  typeLine?: string
  types?: string[]
  colors?: string
  power?: string
  toughness?: string
  loyalty?: string
  defense?: string
  rarity?: string
  rules?: string[]
  counters?: Counter[]
  token?: boolean
  faceDown?: boolean
  transformable?: boolean
  transformed?: boolean
  back?: Card
  targets?: UUID[]
  kind?: 'spell' | 'ability'
  sourceId?: UUID
  controllerId?: UUID
}

export interface Permanent extends Card {
  tapped?: boolean
  damage?: number
  sick?: boolean
  copy?: boolean
  phasedOut?: boolean
  flipped?: boolean
  attachments?: UUID[]
  attachedTo?: UUID
  ownerId?: UUID
  row?: 'land' | 'creature' | 'other'
  attacking?: boolean
  blocking?: boolean
  canAttack?: boolean
  canBlock?: boolean
}

export interface CommandObject {
  id: UUID
  kind: 'commander' | 'commander-away' | 'emblem' | 'plane' | 'dungeon' | 'other'
  name: string
  set?: string
  num?: string
  image?: string
  imageNum?: number
  rules?: string[]
  card?: Card
  casts?: number
  tax?: number
}

export interface PlayerState {
  id: UUID
  name: string
  me: boolean
  human: boolean
  life: number
  counters?: Counter[]
  library: number
  handCount: number
  graveyard: Card[]
  exile: Card[]
  mana?: Record<string, number>
  command: CommandObject[]
  battlefield: Permanent[]
  active: boolean
  priority: boolean
  lost: boolean
  won: boolean
  monarch: boolean
  initiative: boolean
  commanderDamage?: Record<string, number>
  skips?: string[]
  thinking: boolean
  deckName?: string
}

export interface CombatGroup {
  defenderId: UUID
  defenderName: string
  attackers: UUID[]
  blockers: UUID[]
  blocked: boolean
}

export interface NamedCards {
  name: string
  cards: Card[]
}

export interface GameState {
  t: 'state'
  seq: number
  turn: number
  phase?: string
  step?: string
  activePlayerId?: UUID
  priorityPlayerId?: UUID
  myPlayerId: UUID
  players: PlayerState[]
  hand: Card[]
  stack: Card[]
  combat: CombatGroup[]
  revealed?: NamedCards[]
  lookedAt?: NamedCards[]
  playable?: Record<UUID, number>
  actions?: UUID[]
}

export type RichSeg = { text?: string; obj?: string; color?: string; i?: boolean; b?: boolean; br?: boolean }

export type PromptKind =
  | 'ASK'
  | 'SELECT'
  | 'PICK_TARGET'
  | 'PICK_ABILITY'
  | 'CHOOSE_ABILITY'
  | 'CHOOSE_MODE'
  | 'CHOOSE_CHOICE'
  | 'PLAY_MANA'
  | 'PLAY_X_MANA'
  | 'AMOUNT'
  | 'MULTI_AMOUNT'
  | 'CHOOSE_PILE'

export interface PromptItem {
  id: string
  text: string
  sourceId?: UUID
  set?: string
  num?: string
}

export interface ChoiceItem {
  key: string
  value: string
  sort?: number
  hints?: string[]
}

export interface Prompt {
  t: 'prompt'
  id: number
  stateSeq?: number
  kind: PromptKind
  playerId: UUID
  message?: RichSeg[]
  messageText?: string
  secondMessage?: RichSeg[]
  required?: boolean
  leftBtn?: string
  rightBtn?: string
  specialBtn?: string
  mode?: 'priority' | 'attackers' | 'blockers'
  possibleAttackers?: UUID[]
  possibleBlockers?: UUID[]
  mulligan?: boolean
  autoAnswer?: string
  targets?: UUID[]
  chosen?: UUID[]
  cards?: Card[]
  defenderPick?: boolean
  choices?: PromptItem[]
  choice?: {
    message?: string
    subMessage?: string
    required?: boolean
    keyed?: boolean
    search?: boolean
    manaColor?: boolean
    items?: ChoiceItem[]
    specialText?: string
  }
  min?: number
  max?: number
  items?: { message: string; min: number; max: number; value: number }[]
  pile1?: Card[]
  pile2?: Card[]
}

export interface Seat {
  playerId: UUID
  name: string
  human: boolean
  deckName?: string
  commanders?: string[]
}

export interface Hello {
  t: 'hello'
  protocol: number
  gameId: UUID
  myPlayerId: UUID
  seats: Seat[]
  tempo: string
}

export interface LogEntry {
  ts: number
  turn: number
  kind: string
  rich: RichSeg[]
}

export interface Placement {
  playerId: UUID
  name: string
  place: number
  human: boolean
  life: number
  eliminatedTurn?: number
  mulligans: number
}

export interface Reward {
  xpGained: number
  breakdown: { source: string; label: string; amount: number }[]
  level: number
  levelBefore: number
  xpTotal: number
  xpIntoLevel: number
  xpForNext: number
  title: string
  levelUp: boolean
  deckId?: number
  deckName?: string
  masteryGained?: number
  masteryLevel?: number
  masteryLevelBefore?: number
  masteryXp?: number
  masteryNext?: number
}

export interface GameOver {
  t: 'gameOver'
  winnerId?: UUID
  result: string
  placements: Placement[]
  turns: number
  durationMs: number
  reward?: Reward
  error?: string
}

export type ServerMessage =
  | GameState
  | Prompt
  | Hello
  | GameOver
  | { t: 'promptClosed'; id: number }
  | { t: 'log'; entries: LogEntry[] }
  | { t: 'status'; thinking?: UUID; autoPassed?: boolean; waitingFor?: string }
  | { t: 'toast'; level: string; rich: RichSeg[] }
  | { t: 'error'; message: string; fatal: boolean }
  | { t: 'pong' }

export type Answer =
  | { uuid: UUID }
  | { bool: boolean }
  | { int: number }
  | { str: string }
  | { mana: { playerId?: UUID; type: string } }

export interface SampleDeck {
  id: string
  name: string
  group: string
  commanders: string[]
  colors: string
  commanderSet?: string
  commanderNum?: string
  cards: number
}

export interface StoredDeck {
  id: number
  name: string
  commanders: string[]
  colors: string
  commanderSet?: string
  commanderNum?: string
  source: string
  sourceUrl?: string
  cardCount: number
  valid: boolean
  validation?: string
  masteryXp: number
  createdAt: number
  updatedAt: number
}

export type Tempo = 'BLITZ' | 'NORMAL' | 'BEDACHT' | 'MAX'

export type DeckSpec = { type: 'user'; id: number } | { type: 'sample'; id: string } | { type: 'random' }
