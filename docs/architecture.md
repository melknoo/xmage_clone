# MageLite implementation plan

## 0. Findings from reading the XMage source

**Use tag `xmage_1.4.60V3` for source, not `master`.** That tag's commit is 2026-07-11T16:10Z, nine minutes after the jar build time in the manifest (`Build-Time: 2026-07-11T16:01:09Z`). `master` is already a different version:
- `ThreadUtils.setGameThreadStatus` and `THREAD_PREFIX_GAME_IDLE` do not exist in the 1.4.60 jar.
- `DataCollector.onGameEndResult` and `GameImpl.endWithTechnicalWinner` are also missing from the jar.
- `GameSessionPlayer.prepareGameView` at the tag still builds views from `game.copy()`, which is very expensive. `master` dropped that.

Path notes for the tag: the AI sources are in `Mage.Server.Plugins/Mage.Player.AI.MA/src/mage/player/ai/` and the human player is in `Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java`. Everything below was checked against the tag source and against the jars with `javap`.

### How the server drives a game (GameController / GameSessionPlayer / GameWorker)

**Listeners** are registered in `GameController.init()`:
- `game.addTableEventListener(...)` handles these event types:
  - `UPDATE` → `updateGame()`, which builds a `GameView` for each session.
  - `INFO` / `STATUS` → chat log.
  - `ERROR`, `END_GAME_INFO`.
  - `INIT_TIMER`, `RESUME_TIMER`, `PAUSE_TIMER`. These only fire when `priorityTime > 0`. With `MatchTimeLimit.NONE` you can ignore them.
- `game.addPlayerQueryEventListener(...)` switches on `QueryType`:

| QueryType | Server callback |
|---|---|
| `ASK` | `GAME_ASK` |
| `PICK_TARGET` | `GAME_TARGET`, built from `CardsView(game, cards.getCards(game), playerId, true)` or from `PermanentView`s |
| `PICK_ABILITY` | `GAME_TARGET`, with `CardsView(abilities, game)`; used for trigger ordering |
| `SELECT` | `GAME_SELECT` |
| `PLAY_MANA` / `PLAY_X_MANA` | `GAME_PLAY_MANA` / `GAME_PLAY_XMANA` |
| `CHOOSE_ABILITY` / `CHOOSE_MODE` | `GAME_CHOOSE_ABILITY`, via `AbilityPickerView(gameView, objectName, abilities, msg)` or `(gameView, modes, msg)` |
| `CHOOSE_CHOICE` | `GAME_CHOOSE_CHOICE`, carries the `Choice` |
| `AMOUNT` | `GAME_GET_AMOUNT` |
| `MULTI_AMOUNT` | `GAME_GET_MULTI_AMOUNT` |
| `CHOOSE_PILE` | `GAME_CHOOSE_PILE` |
| `PERSONAL_MESSAGE` | inform |

**Routing (`perform`).** The real controller is `game.getPlayer(player.getTurnControlledBy())`. Only players that have a session (humans) get a callback. After that, `informOthers` sends "Waiting for X".

**Bots fire `SELECT` too.** `ComputerPlayer7.priorityPlay` calls `game.firePriorityEvent(playerId)` on every priority. Your listener therefore receives `SELECT` events for bots. Filter them out, or use them as a free "bot is thinking" signal.

**Response gating (`sendMessage`).** A response is only applied if `player.isGameUnderControl()` and `game.getPriorityPlayerId()` is null or equals the player. Otherwise it goes to controlled players. `HumanPlayer.prepareForResponse` sets the priority player to the human before every dialog, so this works on bots' turns too.

**Game thread.** In 1.4.60, `GameWorker.call()` does:
1. `Thread.currentThread().setName(ThreadUtils.THREAD_PREFIX_GAME + " " + game.getId())`
2. `game.start(choosingPlayerId)`
3. `game.fireUpdatePlayersEvent()`
4. `endGameWithResult(game.getWinner())`
5. `game.cleanUp()`

Listeners are called synchronously on the game thread. That makes it the only safe place to build a `GameView`.

**Responses go through `setResponse*` (`waitResponseOpen` + `notifyAll`).**
- `HumanPlayer.waitForResponse` blocks the game thread in `response.wait()`.
- `setResponseUUID/String/Boolean/Integer/ManaType` first spin in `waitResponseOpen()`: every 100 ms for up to 30 s (`RESPONSE_WAITING_TIME_SECS = 30`) until `responseOpenedForAnswer` is true.
- **Consequence:** calling `setResponse*` from the game thread (for example inside a listener, for auto-answers) blocks for 30 s and then drops the answer. Every answer must go through a separate "CALL" executor.
- `skip()` (used by the F-key actions) and `signalPlayerConcede` notify directly, without waiting.

**`UserData` defaults to null on `PlayerImpl`.** `ComputerPlayer` sets `UserData.getDefaultUserDataView()`, but `HumanPlayer` does not. `priority()`, `selectAttackers()` and others read `getControllingPlayersUserData(game)`, so you **must** call `human.setUserData(...)` or you get an NPE.

**Default `UserData`:**
- `confirmEmptyManaPool=true`, `manaPoolAutomatic=true`, `manaPoolAutomaticRestricted=true`
- `passPriorityCast=false`, `passPriorityActivation=false`
- `autoOrderTrigger=true`, `useFirstManaAbility=false`

**Default `UserSkipPrioritySteps`:**
- `stopOnDeclareAttackers=true`, `stopOnDeclareBlockersWithAnyPermanents=true`, `stopOnDeclareBlockersWithZeroPermanents=false`
- `stopOnAllMainPhases=true`, `stopOnAllEndPhases=true`, `stopOnStackNewObjects=true`
- `SkipPrioritySteps` for both `yourTurn` and `opponentTurn`: only `main1` and `main2` are true, and true means *stop* (`checkPassStep` passes when `!isPhaseStepSet(step)`). With an empty stack you therefore stop in every player's main phases, including all three bots'.

**How `HumanPlayer` reads answers:**

| Situation | How answers are read |
|---|---|
| `priority()` | Boolean or Integer: pass (`passWithManaPoolCheck`, which may ask "mana will be lost"). UUID: `getPlayableActivatedAbilities` then `activateAbility`, which fires `CHOOSE_ABILITY` if there is more than one ability. `"special"`: special action. |
| `selectAttackers` | `SELECT` with `Constants.Option.POSSIBLE_ATTACKERS` ("possibleAttackers") and `SPECIAL_BUTTON`="All attack". UUID: toggles the attacker; with more than one defender it calls `selectDefender`, which is `chooseTarget(TargetDefender)` and fires a nested `PICK_TARGET`. `"special"`: all attack, then a defender pick. Boolean or Integer: done, if `checkIfAttackersValid`. |
| `selectBlockers` | `SELECT` with "possibleBlockers". UUID: `selectCombatGroup`, which auto-picks the attacker if only one is legal, otherwise fires `PICK_TARGET` "Select attacker to block". Boolean or Integer: done. |
| Targets (`choose` / `chooseTarget` / `chooseTargetAmount`) | Re-fires `PICK_TARGET` after each pick. Options include `chosenTargets`, `possibleTargets`, `targetZone`, and `UI.right.btn.text`="Done" once the minimum is met. UUID toggles a target. Null UUID (send Boolean false) means done or cancel. |
| `playMana` | Boolean: cancel the cast. UUID: pay from that source (`ManaUtil.tryToAutoPay` picks the ability if one fits exactly). `"special"`: convoke/delve. ManaType: unlock that type in the pool (`getManaPlayerId` must equal self). |
| `announceX` / `getAmount` | `AMOUNT`; Integer in [min, max], otherwise re-asked. |
| `getMultiAmount…` | String of space-separated ints (`MultiAmountType.parseAnswer`); Boolean cancels if `canCancel`. |
| `choose(Choice)` | String key or value. `"#"+key` means "remember" (replacement effects). Empty string cancels if not required. |
| `chooseMode` | UUID of the mode, or `Modes.CHOOSE_OPTION_DONE_ID` / `CHOOSE_OPTION_CANCEL_ID`. |
| `choosePile` | Boolean; true = pile 1. |
| `chooseTriggeredAbility` | `PICK_ABILITY`; UUID of the ability. |
| `chooseMulligan` | `ASK` with left button "Mulligan" (true) and right button "Keep" (false). |

**F-key actions** are handled in `PlayerImpl.sendPlayerAction`. Each sets a flag and calls `skip()`:

| Action | Flag |
|---|---|
| `PASS_PRIORITY_UNTIL_MY_NEXT_TURN` | `passedAllTurns` |
| `UNTIL_TURN_END_STEP` | `passedUntilEndOfTurn` |
| `UNTIL_NEXT_TURN` | `passedTurn` |
| `UNTIL_NEXT_TURN_SKIP_STACK` | `passedTurnSkipStack` |
| `UNTIL_NEXT_MAIN_PHASE` | `passedUntilNextMain` |
| `UNTIL_STACK_RESOLVED` | `passedUntilStackResolved` (only if the stack is non-empty) |
| `UNTIL_END_STEP_BEFORE_MY_NEXT_TURN` | `passedUntilEndStepBeforeMyTurn` |
| `CANCEL_ALL_ACTIONS` | resets all of the above |

`HumanPlayer` also handles `HOLD_PRIORITY`/`UNHOLD_PRIORITY`, `TRIGGER_AUTO_ORDER_*` and `REQUEST_AUTO_ANSWER_*`. Server-level actions are separate calls: `CONCEDE` → `game.setConcedingPlayer`, and `MANA_AUTO_PAYMENT_*` → `game.setManaPaymentMode(...)`.

The XMage client's default keys are F2 confirm, F3 cancel skips, F4 next turn, F5 end step, F6 next turn skipping the stack, F7 next main, F9 my turn, F10 stack resolved, F11 end step before my turn. Its buttons send: left = Boolean true, right = Boolean false, Ctrl+right in SELECT = Integer 0, special = String `"special"`.

### AI (ComputerPlayer6 / ComputerPlayer7)

- The `ComputerPlayer6` constructor sets `maxDepth = skill < 4 ? 4 : skill`, `maxThinkTimeSecs = skill * 3` and `maxNodes = 5000`.
- `setMaxThinkTimeSecs(int)` exists. The copy constructor does not copy that field, but `GameState.restore` calls `origPlayer.restore(copy)` and keeps the original object, so the setting is not lost.
- `addActionsTimed` runs the search on a shared pool of 5 "AI-SIM-MAD" threads with `task.get(maxThinkTimeSecs)`. On timeout it logs "thinks too long" and returns 0.
- **This is the main cause of slow games.** `ComputerPlayer7.priorityPlay` runs `calculateActions` in `PRECOMBAT_MAIN`, `DECLARE_ATTACKERS`, `DECLARE_BLOCKERS` and `POSTCOMBAT_MAIN` on every player's turn, not just its own. The code has a comment asking why the old "pass on opponent's turn" was removed. In a 4-player game that means up to 3 bot searches for every priority round.
- `printBattlefieldScore` only does work when INFO logging is on, so set `log4j.logger.mage.player.ai=WARN`.
- "Computer - mad" in `config.xml` is `mage.player.ai.ComputerPlayerControllableProxy` (extends `ComputerPlayer7`). It is not final and has `(String, RangeOfInfluence, int)` and copy constructors, so you can subclass it.

### Card DB (CardScanner / RepositoryUtil / CardRepository)

- The `CardRepository` constructor opens `jdbc:h2:file:./db/cards.h2;AUTO_SERVER=TRUE;IGNORECASE=TRUE;CACHE_SIZE=…`, relative to the current working directory. It runs `isDatabaseObsolete("card", 54)` and `isNewBuildRun("card", CardRepository.class)`. The second check reads `Build-Time` from the jar manifest via `JarVersion`. If either check fails, it drops the cards table.
- **Do not shade or re-jar the XMage jars**, or the manifest check changes.
- `CardScanner.scan()` touches `Sets.getInstance()`. That runs `ClassScanner` over `mage.sets`, which streams the whole 58 MB jar and initialises every set class, which in turn loads about 42,000 card classes. It then runs `findCard(set, number)` once per printing (about 91,000 queries).
- The server calls `scan()` on every start. Your log shows 13 s on the second start.
- `RepositoryUtil.bootstrapLocalDb()` took another 8 s on the second start (09:53:24 → 09:53:32). It loads every `CardInfo` just to log counts.
- **Both can be skipped** when the DB matches. `RepositoryUtil.isDatabaseEmpty()` (checks for set "GRN" and the card "Island") is the cheap sanity check.

### Other verified details

- `MatchOptions` has the setter misspelled as **`setMullgianType(MulliganType)`**.
- `GameOptions.rollbackTurnsAllowed` defaults to true. Set it to false; then `saveRollBackGameState` stops copying state every turn.
- `MatchImpl.initGame(Game)` is protected: `player.init(game)`, `loadCards(deck cards)`, `loadCards(sideboard)`, `addPlayer`, and it shuffles the seats.
- Mulligans: `Mulligan.executeMulliganPhase` fires `CAN_TAKE_MULLIGAN` and the inform messages "keeps hand" / "decides to take mulligan". `LondonMulligan.mulligan(game, playerId)` is overridable.
- Watchers:
  - `GameState.addWatcher(Watcher)` exists.
  - `Watcher.copy()` works by reflection: it requires exactly one constructor and deep-copies the fields.
  - `GameState.restore` replaces the watchers wholesale.
  - Both commander watchers exist in the jar: `CommanderPlaysCountWatcher.getPlaysCount(commanderId)` and `CommanderInfoWatcher.getDamageToPlayer()`.
- `checkIfGameIsOver()` also returns true if the game thread is interrupted. But `HumanPlayer` swallows `InterruptedException`, so interrupting does not reliably stop a game. Stopping must be done by conceding.
- Required JDK modules (jdeps): `java.base`, `java.desktop` (`SwingUtilities`, `java.awt.Color`), `java.sql`, `java.naming`. Add `java.net.http` and `jdk.crypto.ec` for Scryfall, plus whatever Jetty needs.
- Scryfall card URLs are `https://api.scryfall.com/cards/{set.toLowerCase()}/{cn}/{lang}?format=image`.
  - The client converts collector numbers with `ScryfallApiCard.transformCardNumberFromXmageToScryfall`: a trailing `*` becomes `★`, `+` becomes `†`, `Ph` becomes `Φ`.
  - Overrides come from `ScryfallImageSupportCards.getDirectDownloadLinks()`.
  - Token links come from `ScryfallImageSupportTokens.findTokenLink(set, name, imageNumber)`. The key is the token's image file name (or name) with " Token" removed, plus its set code and image number, as in `ImageCache.getKey`.
- Endpoints I probed:
  - `POST https://api.scryfall.com/cards/collection` worked (75 identifiers per request).
  - `https://archidekt.com/api/decks/{id}/` returned public JSON.
  - `api2.moxfield.com` returned 403 to curl. I used a made-up deck id, so this doesn't prove it is blocked, but treat it as unreliable.
- `cards.h2.lock.db` exists in `mage-server\db`. Copy only `cards.h2.mv.db`, and only while the XMage server is stopped.

---

## (a) Repo layout (`d:\xmage_clone`)

```
d:\xmage_clone\
  package.json                 npm workspaces: ui, desktop
  scripts\
    import-xmage.ps1           copy jars/db/decks/sounds from an XMage dist into vendor\, write vendor\xmage\manifest.json (sha256 + Build-Time)
    bootstrap-gradle.ps1       download gradle-8.x-bin.zip to scratch, run `gradle wrapper --gradle-version 8.10.2`, delete the zip
  vendor\xmage\                (gitignored) lib\*.jar  db\cards.h2.mv.db  sample-decks\Commander\**  sounds\*.wav
  engine\                      Gradle wrapper, Java 17 toolchain, `application` plugin
    build.gradle.kts           implementation(fileTree("../vendor/xmage/lib"){include("*.jar")}) + javalin 6.x,
                               jackson-databind 2.17 (records), sqlite-jdbc 3.46 (do not ship XMage's 3.32), junit5;
                               installDist copies jars unchanged; optional typescript-generator plugin -> ui/src/protocol/generated.ts
    src\main\java\dev\magelite\
      Main.java                args: --port=0 --data=<dir> --parent-pid=<pid>; prints `MAGELITE_READY {"port":..,"token":..}`
      boot\     DataDirs, LogConfig, CardDbManager, Warmup
      api\      HttpServer (Javalin), RestRoutes, GameWsEndpoint, Outbox, Auth(token)
      game\     GameHost, MageLiteMatch, TrackingLondonMulligan, MageLiteBot, HumanBridge, CallExecutor,
                PromptRegistry, PromptMapper, ResponseRouter, AutoAnswerQueue (macros), AutoPassPolicy,
                TempoController, PlayableCalculator, GameAbortController
      view\     GameViewMapper, RichText (jsoup), dto\*.java (records)
      deck\     DeckService, TextDeckParser, ArchidektClient, MoxfieldClient, SampleDeckCatalog, DeckValidation
      images\   ImageService, ScryfallClient, RateLimiter, ImageCache, ScryfallMaps (from resource json)
      stats\    StatsWatcher, StatsSink, GameRecorder, XpService, MasteryService, StatsRepository, Db (migrations)
    src\main\resources\  log4j.properties, db\migrations\V1__init.sql, scryfall-maps.json
    src\tools\java\      ExportScryfallMaps (separate sourceSet with mage-client jar; dumps maps to json)
    src\test\java\       mapper/parser tests, fixture tests from gamelogsJson
    run\                 dev working dir (db\ copied here)
  ui\                    Vite + React 19 + TS + Tailwind v4 (@tailwindcss/vite) + Zustand 5
    src\protocol\  types.ts|generated.ts, ws.ts (reconnect, seq), rest.ts
    src\store\     gameStore.ts (normalized objects), promptStore, settingsStore, profileStore
    src\game\      Table, OpponentPod, MyArea, Battlefield, Hand, StackPanel, CommandZone, PromptBar,
                   dialogs\(TargetCards, Choice(virtualized), AbilityPicker, Amount, MultiAmount, Pile, Mulligan),
                   CombatArrows(SVG), Hotkeys, CardImage, CardZoom, GameLog, BotStatus
    src\decks\ src\stats\ (Recharts) src\profile\ src\settings\  src\lib\ (mana symbols via mana-font, sounds via Howler)
  desktop\               Electron + electron-builder (NSIS)
    src\main.ts  src\engine.ts (spawn/ready/health/shutdown/restart)  src\preload.ts (contextBridge)
    electron-builder.yml (extraResources: jre\ (jlink), engine\lib\, engine-seed\db\, sample-decks\, sounds\)
  docs\protocol.md  docs\architecture.md  LICENSES\XMage-MIT.txt
```

**Jars to copy.** Start broad and prune in Phase 0 with `-verbose:class`.
- From `mage-server\lib`: `mage`, `mage-common`, `mage-sets`, `mage-player-ai`, `mage-game-commanderfreeforall`, `gson`, `guava` (+`failureaccess`), `jsoup`, `protobuf-java` (`MatchImpl.toProto` references it), `ormlite-core`/`jdbc`, `h2-1.4.197`, `reload4j`, `slf4j-api`, `slf4j-reload4j`, `commons-lang3`, `commons-io`, `trove`, `unbescape`.
- From `plugins`: `mage-player-ai-ma`, `mage-player-human`, `mage-deck-constructed`.
- Drop: `mage-server`, `jboss-*`, `shiro-*`, `jersey-*`, `jaxb-*`, `mail`, `jspf`, `prettytime`, `sqlite-jdbc-3.32`.

---

## (b) Engine-host components

| Component | Responsibility | XMage classes / methods |
|---|---|---|
| **CardDbManager** | First run: copy the seed `cards.h2.mv.db` to `<data>\engine\db\`. Marker file `db\magelite.json` = {sha256 of mage/sets jars, Build-Time}. On boot, touch `CardRepository.instance` and `ExpansionRepository.instance`, then run `RepositoryUtil.isDatabaseEmpty()`. If the marker matches and the DB is not empty, skip scan and bootstrap. Otherwise run `CardScanner.scan()` with progress events. | `CardRepository`, `ExpansionRepository`, `RepositoryUtil`, `CardScanner` |
| **Warmup** | Low-priority daemon thread, started after READY: `Sets.getInstance()`, `CardRepository.instance.getNames()`, `TokenRepository.instance.getAll()`, `Deck.load` of the decks most recently used. | `Sets`, `TokenRepository` |
| **DeckService** | Import (`.dck` / text / URL), resolve names, load, validate, persist (the `.dck` text in SQLite). | `DeckImporter.importDeckFromFile(path, errors, false)` (for `.dck`), `DeckCardLists`, `DeckCardInfo(name, number, set)`, `CardRepository.findCardWithPreferredSetAndNumber`, `findPreferredCoreExpansionCard`, `Deck.load(lists, false, false)`, `new mage.deck.Commander().validate(deck)`, `getErrorsListSorted()` |
| **MageLiteMatch** `extends MatchImpl` | `startGame()`: `new CommanderFreeForAll(MULTIPLE, RangeOfInfluence.ALL, new TrackingLondonMulligan(1), 40, 7)`, `setNumPlayers(4)`, `setStartMessage(createGameStartMessage())`, `initGame(game)`, `games.add(game)`. | `MatchOptions(name, "Commander Free For All", true)` + `setDeckType("Variant Magic - Commander")`, `setAttackOption(MULTIPLE)`, `setRange(ALL)`, `setFreeMulligans(1)`, `setMullgianType(LONDON)`, `setMatchTimeLimit(NONE)`, `setRollbackTurnsAllowed(false)`, `setWinsNeeded(1)` |
| **TrackingLondonMulligan** `extends LondonMulligan` | Override `mulligan(game, pid)` to count into the StatsSink, then call `super`. | `LondonMulligan` |
| **MageLiteBot** `extends ComputerPlayerControllableProxy` | Same constructors; `copy()` returns `MageLiteBot`. Calls `setMaxThinkTimeSecs` from the preset. Overrides `priority(game)` (see e). Looks up host hooks through a static registry keyed by `game.getId()`; no host fields. | `ComputerPlayer7.priority`, `PlayerImpl.pass` |
| **HumanBridge** | Creates `new HumanPlayer(name, ALL, 0)` and calls `setUserData(customised UserData.getDefaultUserDataView())`. Applies settings live (`getUserData().setUserSkipPrioritySteps`, `setPassPriorityCast`, …). | `HumanPlayer`, `UserData`, `UserSkipPrioritySteps`, `SkipPrioritySteps` |
| **GameHost** | Wires everything. `match.addPlayer(p, deck)` ×4, `startMatch()`, `startGame()`, `game.setGameOptions(go{rollbackTurnsAllowed=false})`, `game.getState().addWatcher(new StatsWatcher())`, registers both listeners, then submits the runner. | `Game.addTableEventListener`, `addPlayerQueryEventListener`, `GameState.addWatcher` |
| **GameRunner** | Single thread per game. Renames itself `ThreadUtils.THREAD_PREFIX_GAME + " " + gameId` (1.4.60 API). Runs `game.start(choosingPlayerId)`, `fireUpdatePlayersEvent()`, then `GameRecorder.finish()` and `game.cleanUp()`. Catches Throwable and records "error" (there is no `endWithTechnicalWinner` in 1.4.60). | `GameImpl.start`, `getWinner`, `Player.hasWon/hasLost` |
| **CallExecutor** | Single thread named `"CALL magelite"`. The **only** caller of `setResponse*`, `sendPlayerAction` and `setConcedingPlayer`. Keeps answers in order and never blocks Jetty or the game thread. | `HumanPlayer.setResponse*`, `Game.sendPlayerAction`, `setManaPaymentMode(Restricted)`, `setUseFirstManaAbility` |
| **Listeners → PromptMapper / GameViewMapper** | Run on the game thread. On `UPDATE`: maybe snapshot. On a query for the human (routed by `getTurnControlledBy()`): fresh snapshot (with playable) + prompt. On a query for a bot: status "thinking" and flush if dirty. `INFO`/`STATUS`: log. `ERROR`: toast. Guard: if `!ThreadUtils.isRunGameThread()`, only mark dirty. | `GameView(state, game, humanId, null)`, `PlayerQueryEvent` getters, `AbilityPickerView`, `CardsView`, `PermanentView` |
| **PlayableCalculator** | Port of `PlayerImpl.getPlayableObjects`: `human.getPlayable(game, true, Zone.ALL, false)`, grouped by source, main card and spell id. Also reports `hasNonManaAction`. One call per prompt. | `PlayerImpl.getPlayable(Game, boolean, Zone, boolean)`, `PlayableObjectsList(Map)` |
| **ResponseRouter** | Checks the `promptId` and value kind, then replicates `GameController.sendMessage` gating: priority player null or human, or the human controls the priority player (in which case the answer goes to that player). Action whitelist (see c). | `Player.isGameUnderControl`, `getPlayersUnderYourControl`, `Game.getPriorityPlayerId` |
| **AutoAnswerQueue** | Macros (attack, block, attack-all): send the first UUID, then answer the expected follow-up `PICK_TARGET` automatically if its targets contain the planned id. Otherwise clear the queue and show the prompt. | — |
| **AutoPassPolicy** | Priority `SELECT` where `!hasNonManaAction` and the policy allows it: dispatch `setResponseBoolean(false)` on the CALL thread and do not show the prompt. | — |
| **TempoController** | Visual pacing between bot actions (see e). | — |
| **GameAbortController** | "Leave": `setConcedingPlayer(human)`, then once the human's record is written, `setConcedingPlayer` for each remaining bot. Watchdog: if the game thread has not finished after 10 s, Electron restarts the engine. | `Game.setConcedingPlayer` |
| **Outbox** | One sender thread per WebSocket connection. Bounded queue; consecutive `state` messages collapse to the latest; prompts are never dropped. Caches the last state and open prompt so a reconnecting client is resynced. | — |
| **ImageService, StatsWatcher / GameRecorder / XpService** | See (h) and (f). | `CommanderPlaysCountWatcher`, `CommanderInfoWatcher` |
| **Parent watchdog** | `ProcessHandle.of(parentPid)` checked every 2 s; exit if Electron is gone. | — |

`Main` also sets `-Djava.awt.headless=true` and configures log4j through `PropertyConfigurator` with `log4j.logger.mage.player.ai=WARN` and `com.j256.ormlite=WARN`. `DataCollectorServices` is left uninitialised (no-op).

---

## (c) WebSocket protocol

Endpoint: `ws://127.0.0.1:{port}/ws/game/{gameId}?token=…`. All messages are JSON with a `t` field. REST covers everything outside a game: `/api/health`, `/api/decks*`, `/api/bots/decks`, `POST /api/games`, `/api/profile`, `/api/stats/*`, `/api/history`, `/img/*`.

### Server → client

- **`hello`** `{protocol:1, gameId, myPlayerId, seats:[{playerId,name,isHuman,deckName,commander}], tempo, settings}`
- **`state`** `{seq, cycle, turn, phase, step, activePlayerId, priorityPlayerId, myPlayerId, special, players: PlayerDto[] (turn order starting with me, from state.getPlayerList(myId)), hand: CardDto[], stack: StackDto[], combat: CombatDto[], exile: {name,cards:CardRef[]}[], revealed, lookedAt, companion, playable: {[id]:{n, nonMana}}}`

Lean DTOs. They come from `GameView`/`PlayerView`/`PermanentView`/`CardView`, plus live `Player` and watchers, since the mapper runs on the game thread.
- **`PlayerDto`** `{id, name, isMe, isHuman, life, counters[{name,n}] (poison, energy, experience), libraryCount, handCount, graveyard: CardRef[], exileCount, isActive, hasPriority, hasLeft, hasLost, manaPool{W,U,B,R,G,C}, command: [{id, kind(commander|emblem|plane|dungeon), name, set, num, tax, castCount}], commanderDamage: {[commanderId]: {[playerId]: n}}, monarch, initiative, designations[], skips{nextTurn, endOfTurn, nextMain, stackResolved, allTurns, endStepBeforeMyTurn}, battlefield: PermanentDto[], topCard?}`
- **`CardDto`** `{id, v (content hash), name, displayName, set, num, variousArt, token, tokenKey?, faceDown, manaCost[] (manaCostLeftStr), mv, types, subtypes, supertypes, colors, power, toughness, loyalty, defense, rules[] (omitted when v is unchanged; client caches by id+v), counters[], transformable, transformed, back?:{name,set,num}, zone, rarity, icons[]}`
- **`PermanentDto`** = CardDto + `{tapped, damage, sick, attachments[], attachedTo, controllerId, ownerId, copy, phasedIn, flipped, attacking, blocking, row(land|creature|other)}`
- **`StackDto`** `{id, kind(spell|ability), name, sourceId, set, num, controllerId, rules[], targets[], paid}`
- **`CombatDto`** `{defenderId, defenderName, attackers[], blockers[], blocked}`

Other server messages:
- **`prompt`** — see the per-kind table below.
- **`promptClosed`** `{promptId}`
- **`log`** `{entries:[{ts, kind(INFO|STATUS), rich}]}`
- **`status`** `{waitingFor:playerId, thinking:bool, autoPassed?:bool}`
- **`toast`** `{level, rich}` (`PERSONAL_MESSAGE`, errors, `informPlayer` warnings)
- **`gameOver`** `{placements[], winnerId, turns, durationMs, xp:{gained, breakdown[], level, levelUp}, mastery:{deckId, gained, level, levelUp}}`
- **`error`** `{message, fatal}`

`rich` is `Array<{text}|{obj:id,name,color}|{br}>`. It is parsed with jsoup from XMage HTML such as `<font color object_id='…'>Name</font> [abc]`. Mana symbols like `{G}` are left in the text for the client to render.

### Prompt envelope

Every prompt has `{t:"prompt", promptId, stateSeq, kind, playerId, message:rich, secondMessage?:rich (options.secondMessage), required, buttons:{left?, right?, special?}}`.

| kind | Extra fields | Valid answers |
|---|---|---|
| `ASK` | `autoAnswer?{text (AUTO_ANSWER_MESSAGE), originalId}`, `mulligan:bool`; buttons from `UI.left.btn.text` / `UI.right.btn.text` | bool |
| `SELECT` | `mode: priority \| attackers \| blockers \| other` (decided by option keys), `possibleAttackers[]`, `possibleBlockers[]`, `special` ("All attack") | uuid, bool, int(0), str "special" |
| `PICK_TARGET` | `targets[]`, `chosen[]`, `possible[]`, `zone`, `cards?:CardDto[]` (when `event.getCards()`/perms are present, shown as a modal), `defenderPick:bool` (inferred: step is DECLARE_ATTACKERS and all targets are players/planeswalkers/battles) | uuid (toggle), bool false (done/cancel) |
| `PICK_ABILITY` | `abilities:[{id, text, sourceId, sourceName, set, num}]` | uuid |
| `CHOOSE_ABILITY` | `objectName`, `choices:[{id,text}]` in order | uuid (null = cancel) |
| `CHOOSE_MODE` | `choices:[{id,text}]`, including the DONE/CANCEL ids | uuid |
| `CHOOSE_CHOICE` | `choice:{message, subMessage, required, keyed, items:[{key,value,hint?}], search, sort?, manaColor, special?{text,hint,canBeEmpty}}` | str (key or value; `"#"+key` = remember; `""` = cancel) |
| `PLAY_MANA` | `cost` (parsed from "Pay {…}"), `canSpecial` | uuid, mana{playerId,type}, str "special", bool false (cancel) |
| `PLAY_X_MANA` | — | bool, uuid, mana |
| `AMOUNT` | `min`, `max` | int |
| `MULTI_AMOUNT` | `items:[{message,min,max,default}]`, `totalMin`, `totalMax`, `title`, `header`, `canCancel` | str "a b c", bool (cancel) |
| `CHOOSE_PILE` | `pile1:CardDto[]`, `pile2:CardDto[]` | bool (true = pile 1) |

### Client → server

- `{t:"respond", promptId, uuid|bool|int|str|mana:{playerId,type}}` → `setResponseUUID/Boolean/Integer/String/ManaType`, sent from the CALL thread.
- `{t:"action", action, data?}`. Whitelist:
  - The 7 `PASS_PRIORITY_*`, `PASS_PRIORITY_CANCEL_ALL_ACTIONS`, `HOLD_PRIORITY`/`UNHOLD_PRIORITY`, `TRIGGER_AUTO_ORDER_*`, `REQUEST_AUTO_ANSWER_*`, `RESET_AUTO_SELECT_REPLACEMENT_EFFECTS` → `game.sendPlayerAction(a, humanId, data)`
  - `MANA_AUTO_PAYMENT_ON/OFF` → `setManaPaymentMode`; `…RESTRICTED_*` → `setManaPaymentModeRestricted`; `USE_FIRST_MANA_ABILITY_*` → `setUseFirstManaAbility`
  - `CONCEDE` → `setConcedingPlayer`
  - Rejected: `UNDO`, `ROLLBACK_*`, permission/`CLIENT_*`/`VIEW_*`/macro actions.
- `{t:"macro", kind:"attack", attackerId, defenderId}`, `{kind:"attackAll", defenderId}`, `{kind:"block", blockerId, attackerId}`, later `{kind:"autoTap"}`
- `{t:"settings", stops, autoPass, passAfterCast, confirmEmptyPool}`, `{t:"tempo", preset|{thinkSecs, actionDelayMs, fastOpponentTurns}}`, `{t:"leave"}`, `{t:"ping"}`

Rules on both sides:
- The server ignores a `respond` whose `promptId` is not the current open prompt, which stops double clicks.
- After answering, the client greys out the prompt until it gets `promptClosed` or a new prompt.

---

## (d) Human UX → XMage responses

- **Priority (SELECT priority).**
  - Playable objects glow (from `playable`, filtering out mana-only sources). Clicking one sends `uuid`. If there are several abilities you get `CHOOSE_ABILITY` as a popover anchored to the card.
  - **Pass**: Space or the right button sends `bool false`. **Hold priority**: Ctrl-click sends `HOLD_PRIORITY` then the uuid.
  - **Special** sends `str "special"`.
  - F-keys follow the XMage defaults: F3 `CANCEL_ALL`, F4 `UNTIL_NEXT_TURN`, F5 `UNTIL_TURN_END_STEP`, F6 `UNTIL_NEXT_TURN_SKIP_STACK`, F7 `UNTIL_NEXT_MAIN_PHASE`, F9 `UNTIL_MY_NEXT_TURN`, F10 `UNTIL_STACK_RESOLVED`, F11 `UNTIL_END_STEP_BEFORE_MY_NEXT_TURN`. F2 / Enter presses the OK button.
  - Show the active skip as a pill (from `PlayerDto.skips`). In Electron call `Menu.setApplicationMenu(null)`, otherwise F5 and F11 trigger reload and fullscreen.
- **Mulligan.** `ASK` with `mulligan:true` opens a full-screen hand. Mulligan sends `bool true`, Keep sends `bool false`. The follow-up "put N on the bottom" is a `PICK_TARGET` over hand ids, shown in the same view.
- **Targets.** Legal targets glow (permanents, player avatars, stack items, hand); chosen ones show a check. Click sends `uuid` (toggle). **Done** (the right button text from options) sends `bool false`. If `cards` is present (library search, revealed cards) the same logic runs in a modal card grid. Selecting the starting player is the same prompt with player avatars.
- **Attacking several opponents.**
  - Default flow: click an attacker (`uuid`). If there is more than one defender, the `PICK_TARGET` with `defenderPick` lights up opponent pods and planeswalkers/battles; click one.
  - Fast flow: drag the attacker onto an opponent pod, or pick a "current attack target" by clicking a pod first. Both send `macro attack`, and `AutoAnswerQueue` answers the defender pick.
  - **Attack all** sends `macro attackAll`, i.e. `"special"` followed by the defender.
  - Clicking an attacking creature removes it (`uuid`). **Confirm attacks** sends `bool true`. If invalid, the engine re-asks and `informPlayer` shows a toast explaining why.
  - Arrows go from attackers to defenders (from `combat`).
- **Blocking.** Click a blocker (`uuid`), then the attacker if prompted, or drag blocker onto attacker (`macro block`). Clicking a blocker again removes it. **Done** sends `bool`.
- **Mana payment (PLAY_MANA).**
  - The prompt bar shows the cost with mana symbols. Usable sources glow.
  - Clicking a land sends `uuid` (the engine auto-picks the ability if one fits, otherwise `CHOOSE_ABILITY`). Clicking a mana-pool pip sends `mana{myId, type}`. **Cancel** sends `bool false` (the engine restores its bookmark). Convoke/delve uses **Special**.
  - Settings toggle `MANA_AUTO_PAYMENT_ON` (use pool automatically) and `USE_FIRST_MANA_ABILITY`.
  - "Auto-tap" is a Phase-5 stretch: a host planner computes a land set and sends uuid clicks, answering `CHOOSE_ABILITY` with the planned ids.
- **X / amount.** Slider + numeric input with min/max → `int`. **Multi-amount**: one row per item with a running total → `str` of space-separated ints.
- **Choices.** Virtualized searchable list (card-name choice can have about 30,000 entries) → `str` key/value. Replacement effects get a "Remember" checkbox, which sends `"#"+key`.
- **Modes, abilities, piles, triggers.** Modes and abilities open a modal list (Done/Cancel are the special UUIDs). Piles show two columns. Trigger order (`PICK_ABILITY`) is a list where you click the trigger that goes on the stack first; a context menu offers "always first/last" (`TRIGGER_AUTO_ORDER_*`).
- **ASK auto-answer.** "Always yes/no for this" sends `REQUEST_AUTO_ANSWER_TEXT_YES/NO` with `data=autoAnswer.text`, then answers the current prompt.
- **Concede / leave.** Confirm dialog, then `CONCEDE` / `leave`.

---

## (e) Bot tempo and human auto-pass

**Think-time presets.** Set per bot, with a global default.

| Preset | skill → maxDepth | `setMaxThinkTimeSecs` | `fastOpponentTurns` | Action delay | Combat delay |
|---|---|---|---|---|---|
| Blitz | 1 → 4 | 2 | on | 0 ms | 150 ms |
| Normal | 2 → 4 | 4 | on | 350 ms | 500 ms |
| Thoughtful | 5 → 5 | 8 | off | 500 ms | 700 ms |
| Max | 7 → 7 | 15 | off | 600 ms | 800 ms |

**`MageLiteBot.priority(game)`:**
1. If `game.isSimulation()`, return `super.priority(game)`.
2. If `fastOpponentTurns` and `!game.isActivePlayer(playerId)` and `game.getStack().isEmpty()`: call `pass(game)` and return false. This skips the search in other players' main and declare steps, which is the biggest single speed gain. Bots still respond when something is on the stack.
3. Otherwise `boolean acted = super.priority(game)`. If `acted`, call `TempoController.afterBotAction(game)`: flush the snapshot on the game thread, then `Thread.sleep(actionDelayMs)`.

Also override `selectAttackers` and `selectBlockers` to sleep for the combat delay afterwards, so declarations can be seen.

**TempoController.**
- On `UPDATE` while a bot is acting, it compares a cheap signature (stack ids, permanent count, life totals, step). If it changed, it flushes immediately.
- Live tempo changes are just fields read on the next action.
- "Fast-forward" (holding Tab, or automatic once the human has F9'd) sets the delays to 0.
- When the human is eliminated: offer "Watch at 4× speed" or "End game" (via `GameAbortController`).

**Human stop defaults (MageLite defaults, editable in Settings → Stops):**
- `yourTurn`: main1 and main2 on; everything else off.
- `opponentTurn`: everything off; optional "stop at opponents' end step".
- `stopOnDeclareAttackers=true`, `stopOnDeclareBlockersWithAnyPermanents=true`, `stopOnStackNewObjects=true`.
- `passPriorityCast=true` and `passPriorityActivation=true` (auto-pass after casting; Ctrl-click to hold).
- `confirmEmptyManaPool=true`.

**AutoPassPolicy** (default on). For a human priority `SELECT` with `!hasNonManaAction` (no castable spell or land and no non-mana activation, per PlayableCalculator), dispatch `bool false` on the CALL thread and send `status{autoPassed}`. With the stop settings above, the human only sees bot turns when there is something to do, such as a response or a block.

---

## (f) Statistics and XP

**Capture.**
1. **`StatsWatcher extends Watcher(WatcherScope.GAME)`**: one no-argument constructor and **no instance fields**. Watcher copies and restores replace the instance, and AI simulations copy the state. In `watch(event, game)`, return immediately if `game.isSimulation()`, otherwise `StatsSink.of(game.getId()).accept(event, game)`. The sink is static, keyed by game id, and thread-safe. Events:

   | Event | Recorded |
   |---|---|
   | `DREW_CARD` | targetId → card name |
   | `SPELL_CAST` | sourceId → card; `event.getZone()==COMMAND` means a commander cast |
   | `LAND_PLAYED` | land drop |
   | `DAMAGED_PLAYER` | amount, source name, combat flag, commander source |
   | `LOST_LIFE` / `GAINED_LIFE` | life changes |
   | `ZONE_CHANGE` | battlefield → graveyard = dies |
   | `ATTACKER_DECLARED` | attack |
   | `LOSES` | elimination order + turn number |
   | `BEGIN_TURN` | turn 1 → snapshot the opening hand of each player from `getHand()` |

   Because rollback is disabled, the only restores are cancelled casts. Count `SPELL_CAST`, which fires after the cast completes, not `ACTIVATE_ABILITY`.
2. **`TrackingLondonMulligan`** counts mulligans exactly, for the human and the bots.
3. **`GameRecorder`** runs after `game.start()` returns, on the game thread. It reads `hasWon`/`hasLost`, life totals, `getTurnNum()`, `getStartTime`/`getEndTime`, commander damage (`CommanderInfoWatcher` per commander) and tax (`CommanderPlaysCountWatcher`). It computes placement = 4 − (number of players who lost before you) and writes everything in one transaction keyed by game id (so it can be replayed safely).

**Persistence.** SQLite file `%APPDATA%\MageLite\magelite.db` via plain JDBC with numbered SQL migrations:
- `profile(id=1, name, xp_total, level, created_at)`
- `decks(id, name, commanders, colors, source, source_url, dck_text, mastery_xp, mastery_level, created_at, updated_at)`
- `games(id, started_at, ended_at, duration_ms, turns, deck_id, result, placement, tempo, mulligans, start_seat, end_reason, xp_awarded)`
- `game_seats(game_id, seat, name, is_human, deck_name, commander, placement, eliminated_turn, life_end, mulligans, cmdr_dmg_dealt)`
- `game_card_stats(game_id, deck_id, card_name, opening, drawn, cast, first_cast_turn, dmg_to_players)`
- `xp_ledger(id, game_id, source, amount, ts)`
- `achievements(key, unlocked_at, game_id)`
- `settings(key, json)`

**Stats screens.**
- Overall winrate, plus breakdowns by deck, by commander, by bot preset and by bot deck.
- Average turns and minutes per game; average turn of first commander cast.
- Mulligan rate, and winrate by number of mulligans.
- Per card: drawn rate, cast-when-drawn rate, win% when cast vs not cast, opening-hand frequency, damage dealt.
- History list with seats and placements.

**XP formula (sketch).**
- `XP = round((40 + place[150, 70, 35, 0] + 3·min(turnsAlive, 25) + firstWinOfDay 100 + firstGameWithDeck 50) × difficulty(Blitz 0.9 / Normal 1.0 / Thoughtful 1.15 / Max 1.3) × streak(1 + 0.1·winStreak, capped at 1.5))`
- Anti-farming: conceding before your 3rd turn gives 0 XP.
- Levels: XP from L to L+1 = `round(150 · L^1.35)`. A title every 10 levels.
- Deck mastery: the deck's XP goes up by the game XP. Mastery 1–10 at cumulative thresholds 0, 200, 500, 900, 1500, 2300, 3300, 4600, 6200, 8200.
- Rewards are cosmetic only: frame and sleeve tint, badge, plus achievements such as "win before turn 10" or "beat every bot preset".

---

## (g) Startup time

1. **Electron** spawns `jre\bin\java.exe -Xmx3g -XX:+UseG1GC -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "<engine>\lib\*" dev.magelite.Main --port=0 --data=… --parent-pid=…` with `cwd=%APPDATA%\MageLite\engine` (for the `./db` path) and `windowsHide`. It shows a splash screen until the engine prints `MAGELITE_READY`.
2. **First run**: copy the 104 MB DB (about 1–2 s) and write the marker.
3. **Every run**:
   - `CardRepository.instance` + `ExpansionRepository.instance` (open H2, version/build checks: well under 1 s)
   - `isDatabaseEmpty()`
   - start Javalin
   - print READY (target under 3 s)
   - Never call `bootstrapLocalDb()` (saves about 8 s) or `CardScanner.scan()` (about 13 s) unless the marker is missing or mismatched.
4. **Fallback**: a full scan with a progress screen ("Indexing cards, first start after an update").
5. **Warmup** in the background (Sets class loading etc.). Starting a game waits on a `CompletableFuture` only if it isn't done yet. Phase 0 measures whether a game even needs `Sets` (I expect only a few cards and token code paths do).
6. **Phase 5**: dynamic AppCDS. A training run with `-XX:ArchiveClassesAtExit=<data>\app.jsa` covering boot, warmup and one game; later runs use `-XX:SharedArchiveFile=… -Xshare:auto` (silently ignored if it doesn't match). This mostly speeds up the 42,000 card classes. Also jlink a runtime with the jdeps module list.

---

## (h) Scryfall image proxy and cache

**Endpoints** (served by the engine): `GET /img/card/{set}/{cn}?face=front|back&size=normal|large|art_crop` and `GET /img/token/{set}/{name}/{n}`. The UI uses plain `<img src>` with `?token=` in the query.

**Resolution order for cards:**
1. Override from `ScryfallMaps.directLinks[set/name/cn]` or `[set/name]`.
2. Mapping cache (`set+cn → image_uris`, with `card_faces` for DFC backs).
3. `https://api.scryfall.com/cards/{set.lower}/{transformCn(cn)}/en?format=image&version={size}[&face=back]`.
4. On failure, the alternate URL without the language and with `include_variations=true` (same as the XMage client).

**Prefetch** at game start: `POST /cards/collection` with up to 75 `{set, collector_number}` per request (about 6 calls for 4 decks). Store the `image_uris` CDN links in the mapping table, then download straight from `cards.scryfall.io`. Those CDN downloads have no API rate limit; run 6 at a time.

**Tokens:** `ScryfallMaps.tokens[set/name[/n]]`, keyed by the image file name (or name) without " Token", plus the token set code and image number. Fallback: `cards/search?q=t:token+name:"…"&unique=prints`, cached. Last resort: a text-frame placeholder from the `CardDto`.

**`ScryfallMaps` source.** A build-time tool (`src\tools`, with `mage-client-1.4.60.jar` on its classpath only) reads via reflection the private static `supportedCards` in `ScryfallImageSupportTokens` (whose initialiser needs `TokenRepository`) and `ScryfallImageSupportCards.getDirectDownloadLinks()`, and writes `scryfall-maps.json`. The runtime never loads the 22 MB client jar.

**Politeness:**
- Token bucket of 8 requests/second for `api.scryfall.com`.
- Headers: `User-Agent: MageLite/<ver> (+contact)` and `Accept: application/json;q=0.9,*/*;q=0.8`.
- On 429/503: honour `Retry-After` with exponential backoff.
- Concurrent requests for the same image share one download.
- 404s are cached for 7 days.
- HTTP/2 via `java.net.http.HttpClient` with redirects followed.

**Cache layout:** `%APPDATA%\MageLite\cache\cards\{set}\{cn}_{face}_{size}.jpg` and `tokens\…`, plus a SQLite table `image_map`. LRU cap of 2 GB with a Settings page. Responses are served with `Cache-Control: max-age=31536000, immutable`.

---

## (i) Phases

| Phase | Scope | Milestone |
|---|---|---|
| **P0a: headless 4-bot spike** | `import-xmage.ps1`, Gradle bootstrap, `CardDbManager` (copy + marker, no scan), `SampleDeckCatalog` (load all 70 `.dck`, run `Commander.validate`, report unknown cards). `MageLiteMatch` with 4 `MageLiteBot` (skill 1, think 2 s), `GameRunner`, console log of INFO/STATUS, StatsWatcher counting events, timing/heap logging. Optional turn cap that ends the game by conceding everyone. | 10 games in a row finish. Report boot ms, ms per turn, bot decisions over their think limit, peak heap. Then the same with `fastOpponentTurns` on vs off. |
| **P0b: one human via console** | Replace seat 1 with `HumanPlayer` + `UserData`. Console prints each prompt as JSON (the PromptMapper prototype) and reads typed answers. Answers go through the CALL executor with the gating logic. | Play mulligan, a land, a spell paid by clicking lands, an attack split across 2 opponents, a target pick, trigger ordering, a block, concede. No deadlocks. |
| **P0c: DTO + timing** | `GameViewMapper` + `RichText`; dump state JSON lines next to `gamelogsJson`; time `GameView` + DTO building on the game thread (p50/p95) and measure JSON size. Try "End game" via concede-all and try `-verbose:class` to prune jars. | Go/no-go on the architecture. |
| **P1: engine service + read-only board** | Javalin REST + WebSocket, Outbox, `hello`/`state`/`log`/`status`, `POST /api/games` (bots only, spectating). UI: table layout for 4 pods, battlefield rows, stack, command zone, log, hover zoom (placeholder card frames). | Watch a 4-bot game live in the browser, using Vite dev server and `gradlew run`. |
| **P2: interactive play + Electron** | All prompt kinds, ResponseRouter, hotkeys, combat arrows, macros, AutoPassPolicy, TempoController with presets and a live slider, Settings → Stops. Electron shell: spawn engine, ready handshake, single-instance lock, parent watchdog, restart on crash. Sounds. | Full game against 3 bots from the desktop app using only the mouse and F-keys. |
| **P3: decks + images** | TextDeckParser (Moxfield, Archidekt and plain formats; sections; `SB:`; `(SET) num`; `*F*`; `[Category]`), ArchidektClient (REST), MoxfieldClient (Electron main-process `net.fetch` with a browser user agent, then fallback to "paste the export"). Commander picker when not detected, validation report, deck library, bot-deck chooser (random/specific per seat). ImageService + prefetch + token map export. | Import 3 real decks (paste + URL) and play them with real images. |
| **P4: stats + gamification** | SQLite migrations, GameRecorder, XpService, MasteryService, achievements, post-game screen with animated XP, profile page, stats dashboards, history. | Numbers match a hand-checked game; XP is awarded once per game. |
| **P5: polish, performance, packaging** | AppCDS, jlink runtime, electron-builder NSIS (bundle `engine\lib`, seed DB, sample decks, jre, about 300 MB), first-run wizard ("use bundled data" or "import from an XMage folder"), per-object diffs for `state`, animations, auto-tap planner (stretch), crash and log bundle export. | Clean Windows 11 VM: install, play, uninstall. Warm start under 3 s to the menu. |

---

## (j) Top risks and mitigations

1. **Slow AI late in the game** (40+ permanents, "thinks too long").
   - Turn on `fastOpponentTurns`; use the think caps from the presets (2–4 s); set AI logging to WARN.
   - Show a "thinking" indicator and offer fast-forward.
   - Optional turn cap or "end game"; `-Xmx3g`.
   - Measure in P0a before committing to UI work.
2. **Deadlocks or dropped answers.** Calling `setResponse*` from the game thread blocks it for 30 s and then drops the answer. Never block the game thread on a socket.
   - All answers go through the CALL executor; macros and auto-pass dispatch the same way.
   - The Outbox queue never blocks.
   - Unit test: auto-pass inside a listener.
3. **ConcurrentModificationException when building views.**
   - Build `GameView` only inside listeners on the game thread.
   - REST and reconnect requests read the cached snapshot.
   - Check `ThreadUtils.isRunGameThread()` before building.
4. **API differences between 1.4.60 and `master`.**
   - Compile against the vendored jars (which catches signature mismatches).
   - Use only tag `xmage_1.4.60V3` as source reference; note the `setMullgianType` typo and the missing `endWithTechnicalWinner`.
5. **Card DB rebuilt unexpectedly** (`JarVersion` build check, wrong working directory).
   - Never repackage the jars.
   - Electron passes `cwd`; the marker file is checked against jar hashes; scanning remains as a fallback with progress UI.
6. **First game stalls on lazy `Sets` loading.** Background warmup; wait on it at game start; AppCDS.
7. **`UserData` NPE and missing skip settings.** `HumanBridge` always calls `setUserData`; add a test.
8. **Control-change cards (Mindslaver and similar).** Route prompts by `getTurnControlledBy()`, mirror the `sendMessage` gating, and accept rough edges in v1 (test with specific cards in P2).
9. **Cards XMage doesn't implement in user decks.** Load with `ignoreErrors`, show a list of missing cards, and allow playing with the rest only after the user confirms.
10. **Moxfield blocking** (403 / Cloudflare). Fetch from the Electron main process; fall back to paste with step-by-step instructions. Archidekt is confirmed working.
11. **Scryfall limits or offline use.** Collection API, persistent cache, backoff, placeholders. The game never waits for images.
12. **Games that never end or can't be stopped.** Interrupts don't work (they're swallowed). Stop by conceding all players; if the thread hasn't finished in 10 s, Electron restarts the engine. Optional turn-cap setting.
13. **Orphaned `java.exe`.** Parent-PID watchdog + `before-quit` shutdown call + kill after 3 s.
14. **XMage HTML in prompts and logs.** Engine converts it to the rich structure with jsoup; the client never uses `dangerouslySetInnerHTML`.
15. **Size and licensing.** XMage is MIT: ship its license and attribution. Follow Scryfall's image guidelines (no paywall, no altering card images). Offer "import from an existing XMage install" to reduce the installer size.

---

## (k) Verification per phase

- **P0a**
  - JUnit: every sample deck loads and validates (log unknown cards).
  - 10 four-bot games end with exactly one `hasWon()`, or a draw.
  - Boot under 3 s with the marker, about 25 s+ without it; record both.
  - Per-turn timing table; counts of bot decisions hitting the think limit; heap under 2.5 GB.
  - Repeat with `fastOpponentTurns` off and compare.
- **P0b**
  - A scripted console session (answers read from a file) reaches turn 5.
  - Attack split across 2 defenders is confirmed by the `combat` dump.
  - Cancelling a payment restores the board correctly.
  - Concede ends the game.
  - Auto-pass doesn't stall (watch for the 30 s "Game frozen in waitResponseOpen" warning in the log: there must be none).
- **P0c**
  - Mapper tests on real `GameView` objects: every permanent has `set`/`num`, ids are unique, the human's hand is visible and bot hands are hidden.
  - p95 mapping time under 30 ms with 150 permanents.
  - JSON size logged.
- **P1**
  - WebSocket integration test (Java WebSocket client) receives `hello` then `state` with increasing `seq`.
  - Reloading the renderer resyncs from the cache.
  - Board matches the engine log over a whole bot game.
- **P2**
  - Playwright with `_electron` drives: mulligan, land, cast with mana click, target, `attack` macro onto opponent B, block, F4/F9, concede.
  - Stale `promptId` answers are ignored (double-click test).
  - Tempo slider changes visibly take effect.
  - Kill `java.exe` → Electron restarts the engine and shows an error.
- **P3**
  - Parser tests with fixtures in Moxfield, Archidekt, MTGA and plain formats (commander detection, sideboard, DFC "A // B", unknown cards).
  - Archidekt live call test.
  - Image test: `ths/191` downloads and is then cached (no second network request); a token resolves; 429 handling via a mocked server.
- **P4**
  - Hand-check one recorded game (casts, draws, mulligans, placement, commander damage) against the log.
  - XP is awarded exactly once (finish called twice).
  - Migrations upgrade v1 to v2 cleanly.
  - Simulation events don't inflate counts: compare with the AI disabled vs enabled.
- **P5**
  - Clean VM install and play.
  - Cold and warm start times with and without AppCDS.
  - Bundled `jre` passes `java --list-modules`.
  - Uninstall removes the program files and keeps user data.

---

### Critical files for implementation
- `d:\xmage_clone\engine\src\main\java\dev\magelite\game\GameHost.java` (match and game setup, listeners, routing, CALL executor wiring)
- `d:\xmage_clone\engine\src\main\java\dev\magelite\game\PromptMapper.java` (+ `ResponseRouter.java`): QueryType ↔ protocol mapping and the response gating rules
- `d:\xmage_clone\engine\src\main\java\dev\magelite\view\GameViewMapper.java` (GameView/Player/watchers → lean DTO, on the game thread)
- `d:\xmage_clone\engine\src\main\java\dev\magelite\game\MageLiteBot.java` (+ `TempoController.java`): think-time presets, `fastOpponentTurns`, visual pacing
- `d:\xmage_clone\engine\src\main\java\dev\magelite\boot\CardDbManager.java`: copy the prebuilt DB, check the marker, skip the scan

XMage reference sources at tag `xmage_1.4.60V3`: `Mage.Server/src/main/java/mage/server/game/GameController.java` and `Mage.Server.Plugins/Mage.Player.Human/src/mage/player/human/HumanPlayer.java`.