// Protokoll Engine <-> UI (siehe docs/architecture.md, Abschnitt c)

export type UUID = string

export interface Counter {
  name: string
  count: number
}

export interface TargetRef {
  id: UUID
  name: string
  kind: 'player' | 'permanent' | 'spell' | 'card'
  /** Zone bei kind=card, z.B. GRAVEYARD */
  zone?: string
  owner?: string
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
  /** Ziele mit Namen (nur Stapelobjekte) */
  targetRefs?: TargetRef[]
  kind?: 'spell' | 'ability'
  /**
   * nur Stapel-Faehigkeiten (kind=ability): vereinfachter XMage-AbilityType. Engine sendet heute
   * triggered | activated | static | special | spell | land (Fallback: Enum-Name klein). Fehlt bei Zaubern/alten Engines.
   */
  abilityType?: AbilityType
  sourceId?: UUID
  controllerId?: UUID
  /** angesagtes X (nur Stapelobjekte) */
  x?: number
}

/** Siehe Card.abilityType; offene Liste (string), damit neue Engine-Werte nicht brechen. */
export type AbilityType = 'triggered' | 'activated' | 'static' | 'mana' | 'special' | 'spell' | 'land' | (string & {})

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
  /** P/T weicht vom Grundwert ab (Zaehler, Boni, "wird zu X/X"); bei verdeckten Permanents nie gesetzt. Fehlt = false. */
  ptModified?: boolean
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
  /** oberste Bibliothekskarte, falls sichtbar */
  topCard?: Card
  /** topCard ist nur fuer mich sichtbar (nicht aufgedeckt) */
  topCardPrivate?: boolean
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
  /**
   * Eigener Spieler. ACHTUNG Zuschauer (spectator=true): fehlt zur Laufzeit; der Blickwinkel-Spieler ist dann
   * players[0] (einziger mit me=true). Typ bewusst optional.
   */
  myPlayerId?: UUID
  /** Zuschauer-Sicht (?spectate=1): hand=[], kein playable/actions/lookedAt/replDeclines, kein myPlayerId */
  spectator?: boolean
  players: PlayerState[]
  hand: Card[]
  stack: Card[]
  combat: CombatGroup[]
  revealed?: NamedCards[]
  lookedAt?: NamedCards[]
  playable?: Record<UUID, number>
  actions?: UUID[]
  /** Ersatzeffekte, die fuer dieses Spiel automatisch abgelehnt werden (Kurznamen) */
  replDeclines?: string[]
}

/** Chat-Zeile im Spiel (WS "chat") */
export interface ChatEntry {
  ts: number
  playerId: UUID
  name: string
  text: string
}

/** Spielereignis fuer Animationen/Ereignisleiste (WS "events") */
export interface FxEvent {
  kind: 'died' | 'tokenDied' | 'exiled' | 'bounced' | 'tucked' | 'discarded' | 'milled' | 'resolved' | 'command' | 'countered' | 'damage' | 'life' | 'counter'
  objectId?: UUID
  name?: string
  card?: Card
  from?: string
  to?: string
  playerId?: UUID
  ownerId?: UUID
  sourceId?: UUID
  sourceName?: string
  amount?: number
  token?: boolean
  combat?: boolean
  hidden?: boolean
  ts: number
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

export interface ReplGroup {
  rule: string
  /** Kurzname, z.B. "Dredge 2" */
  label: string
  /** Effekt fragt selbst nach ("you may") -> 1-Klick und "Keinen anwenden" moeglich */
  optional?: boolean
  sources: { key: string; name: string; objectId?: UUID }[]
  /** ersetztes Ereignis aus dem Regeltext, deutsch (z.B. "Karte ziehen", "Abwerfen"); fehlt, wenn nicht ableitbar */
  cause?: string
  /** alle Quellen heissen gleich -> nur dann ist "Gruppe annehmen" (ReplacementMode 'acceptGroup') erlaubt */
  uniform?: boolean
}

/** Antwort-Modus der Ersatzeffekt-Wahl (WS replacement): 'acceptGroup' braucht key = ReplGroup.rule und uniform=true */
export type ReplacementMode = 'accept' | 'decline' | 'acceptGroup'

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
  /** PLAY_MANA: Kreaturen, die per Klick eingeberufen werden koennen (Convoke) */
  specialTargets?: UUID[]
  mode?: 'priority' | 'attackers' | 'blockers'
  /** Prioritaet im eigenen Zug bei leerem Stapel: wohin "Weiter" fuehrt */
  nextStop?: 'main1' | 'combat' | 'main2' | 'end'
  possibleAttackers?: UUID[]
  possibleBlockers?: UUID[]
  mulligan?: boolean
  /** nur Mulligan-Frage: bisher genommene Mulligans (0 wird weggelassen -> fehlt = 0) */
  mulligans?: number
  /** nur Mulligan-Frage: der naechste Mulligan ist gratis (erster im Commander-Mehrspieler) */
  freeMulligan?: boolean
  autoAnswer?: string
  targets?: UUID[]
  chosen?: UUID[]
  cards?: Card[]
  defenderPick?: boolean
  choices?: PromptItem[]
  /**
   * CHOOSE_ABILITY: Objekt, dessen Faehigkeiten zur Wahl stehen (fuer "N-mal aktivieren").
   * PICK_TARGET / PLAY_MANA / PLAY_X_MANA: Stapelobjekt (id wie state.stack[].id), fuer das gewaehlt bzw. bezahlt wird;
   * nur wenn ableitbar, sonst fehlt es.
   */
  sourceId?: UUID
  choice?: {
    message?: string
    subMessage?: string
    required?: boolean
    keyed?: boolean
    search?: boolean
    manaColor?: boolean
    /** "card": Kartennamen (Bildvorschau per Name) */
    hint?: 'text' | 'card' | 'card_dungeon' | 'game_object'
    items?: ChoiceItem[]
    specialText?: string
    /** nur bei der Ersatzeffekt-Wahl: gleiche Effekte (Regeltext) zusammengefasst */
    groups?: ReplGroup[]
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

/** Verbindungszustand eines menschlichen Sitzes (nur bei mehreren Menschen gesendet) */
export interface SeatConn {
  playerId: UUID
  connected: boolean
  disconnectedMs: number
  conceded: boolean
}

export interface Hello {
  t: 'hello'
  protocol: number
  gameId: UUID
  /** fehlt bei Zuschauern (spectator=true) zur Laufzeit - Typ bewusst optional */
  myPlayerId?: UUID
  seats: Seat[]
  tempo: string
  /** Gastgeber (erster Mensch): darf das Tempo stellen; fehlt bei alten Engines; Zuschauer: false */
  host?: boolean
  /** Zuschauer-Verbindung (?spectate=1) */
  spectator?: boolean
  /** Zuschauer: Spieler, aus dessen Blickwinkel gezeigt wird (= state.players[0]) */
  viewpointId?: UUID
  /** Zuschauer: Name des Tisches ("Du schaust {tableName} zu") */
  tableName?: string
}

/** WS "seats": Verbindungszustand der Menschen (+ Zuschauernamen) */
export interface SeatsStatus {
  t: 'seats'
  seats: SeatConn[]
  kickAfterMs: number
  /** Namen der Zuschauer (fuer "N schauen zu"); fehlt = keine / alte Engine */
  spectators?: string[]
}

/**
 * WS-Close-Codes beim Zuschauen (/ws/game/{id}?spectate=1). Alle sind endgueltig: kein Reconnect,
 * deutscher Toast (SPECTATE_CLOSE_TEXT), zurueck zur Lobby.
 */
export const SPECTATE_CLOSE = {
  /** lokaler Modus bzw. ohne ?spectate kein eigener Sitz */
  notAllowed: 4403,
  /** Tisch laeuft nicht (mehr) / Spiel beendet / Solo-Spiel */
  notRunning: 4404,
  /** Nutzer sitzt selbst an diesem Tisch */
  seated: 4409,
  /** mehr als 8 Zuschauer */
  full: 4429,
  /** Zuschauer liest zu langsam (Queue uebergelaufen) */
  tooSlow: 4408,
  /** neue Zuschauer-Verbindung desselben Nutzers hat diese ersetzt */
  replaced: 4000,
} as const

export type SpectateCloseCode = (typeof SPECTATE_CLOSE)[keyof typeof SPECTATE_CLOSE]

export const SPECTATE_CLOSE_TEXT: Record<SpectateCloseCode, string> = {
  4403: 'Zuschauen ist hier nicht möglich.',
  4404: 'Dieses Spiel läuft nicht mehr.',
  4409: 'Du sitzt selbst an diesem Tisch.',
  4429: 'Es schauen schon zu viele zu.',
  4408: 'Die Verbindung war zu langsam. Zuschauen beendet.',
  4000: 'Du schaust in einem anderen Fenster zu.',
}

export function isSpectateClose(code: number): code is SpectateCloseCode {
  return code in SPECTATE_CLOSE_TEXT
}

export interface LogEntry {
  ts: number
  turn: number
  /** Name des aktiven Spielers */
  active?: string
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
  /** XP-Ring vor dem Spiel (fuer die Animation ueber den Level-Aufstieg) */
  xpIntoLevelBefore?: number
  xpForNextBefore?: number
  /** naechster Titel nach dem aktuellen Level; null = hoechster Titel erreicht */
  nextTitle?: { level: number; title: string } | null
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

/** Herzschlag der Engine (1/s): was gerade passiert, CPU-Last in % eines Kerns (-1 = unbekannt). */
export interface Activity {
  t: 'activity'
  mode: 'you' | 'bot' | 'human' | 'engine' | 'idle' | 'stuck'
  who?: string
  cpu: number
  idleMs: number
  recovered: number
}

export type ServerMessage =
  | GameState
  | Prompt
  | Hello
  | GameOver
  | { t: 'promptClosed'; id: number }
  | { t: 'log'; entries: LogEntry[] }
  | { t: 'status'; thinking?: UUID; autoPassed?: boolean; waitingFor?: string }
  | { t: 'seat'; conceded: boolean }
  | SeatsStatus
  | Activity
  | { t: 'toast'; level: string; rich: RichSeg[] }
  | { t: 'error'; message: string; fatal: boolean }
  | { t: 'pong' }
  | { t: 'events'; items: FxEvent[] }
  | { t: 'chat'; entries: ChatEntry[] }

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

/** verschoben nach api/decks.ts (Re-Export fuer bestehende Importe) */
export type { StoredDeck } from './decks'

export type Tempo = 'BLITZ' | 'NORMAL' | 'BEDACHT' | 'MAX'

export type DeckSpec = { type: 'user'; id: number } | { type: 'sample'; id: string } | { type: 'random' }
