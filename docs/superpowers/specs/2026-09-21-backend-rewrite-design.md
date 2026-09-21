# Backend rewrite design

- **Date:** 2026-09-21
- **Branch:** `code-compress`
- **Status:** all five sections approved in chat; this written spec is awaiting review.

## 1. Why

This comes from an evaluation of `backend/` on 2026-09-21: all 120 tests passed, one e2e test was flaky, and coverage was measured with JaCoCo.

### Bugs

Each bug gets a named regression test. Root causes, fixes and status for each one are tracked in `documentation/backend-fixes.md`.

| ID | Bug |
|---|---|
| B1 | **The game freezes when Mr X has no legal move.** There's no rule for Mr X being boxed in by detectives. This also causes the flaky `fourPlayerGame_playsToCompletion`: 30% of 4-player starts on `test-map.json` box Mr X in on turn 1. |
| B2 | **Role filtering can be bypassed.** Public player IDs (in every `players[]`) double as credentials. Anyone can read Mr X's view with `GET /api/games/{id}?playerId=<Mr X>`, subscribe to his STOMP topic, fetch his valid moves, move or kick as another player, or remove any player with a body-less `DELETE`. The in-memory STOMP broker also honours wildcard subscriptions, so `/topic/games/{id}/players/**` receives every private state; this was verified with `DefaultSubscriptionRegistry`. Clients can probably also SEND straight to `/topic/...`, faking server broadcasts. |
| B3 | **A detective leaving on their turn freezes the game.** An earlier detective leaving makes the next detective's turn get skipped (stale index). |
| B4 | **Mr X can play DOUBLE while a double is pending,** giving him three moves in one turn. |
| B5 | **A plain `DOUBLE` ticket skips the mode check** and logs `DOUBLE` as the transport, which hides the mode like a free Invisible ticket. |
| B6 | **When the host leaves the lobby, the game is deleted without a broadcast.** The other players are stranded; their polls return 404 and nothing tells them. |
| B7 | **Only `submitMove` is synchronized.** Join, start, leave and kick race with each other and with the 6-second polls, e.g. two simultaneous joins overfill a game. |
| B8 | **Missing request fields cause a NullPointerException and a 500** instead of a 400. |

### Docs vs code

- spec.md, openapi.yaml, CLAUDE.md, the README and final-report line 108 describe pause-on-disconnect, a 60 s grace period and a 120 s auto-skip. None of it exists: the code aborts a game after 15 minutes without a move.
- Detective ticket counts have three versions (application.properties 12/8/6/2; the `@Value` defaults and doc.md both 10/8/4/2).
- doc.md and openapi.yaml have other stale details.

### Tests

- The rules engine has no unit tests: `MapGraph` is mocked to return "no moves".
- Coverage without Selenium is 65% of lines and 45% of branches.
- About 15 tests duplicate others and about 10 only check getters. Tests reflect into private fields.
- The controller test only re-checks the exception→status mapping.
- Selenium is used only as an HTTP client, and that test is flaky.
- There are no WebSocket tests, and none of the proposal's coverage, mutation, property, fuzz or performance testing.

## 2. Decisions

| Topic | Decision |
|---|---|
| API | Change only what the fixes need: a secret player token, and plain `DOUBLE` is dropped. Frontend and openapi.yaml are updated to match. |
| Timers | Keep the idle abort, configurable via `game.turn-timer-seconds` = 900. Drop pause, grace and `PAUSED`, and fix the docs and report. |
| Meta-testing | JaCoCo, PIT, property-based and fuzz tests in plain JUnit with seeded randomness, and an opt-in performance harness. (jqwik was the first choice; it was dropped because its maintainers ask AI agents not to use it.) |
| E2E | Replace Selenium with a browserless REST + STOMP full-game test |
| Structure | Approach A: a plain rules core (`Game`) and a thin Spring service |
| Leaving | Any player leaving an in-progress game ends it. The host leaving the lobby ends the lobby. A non-host leaving the lobby is just removed. |
| Detective order | Random, fixed at game start. spec.md changes to match. |
| Report | Update the outline bullets in `final_report.tex` |

## 3. Architecture

```
game/           plain Java, no Spring
  Game          every rule: lobby, start, moves, turns, reveals, wins, leave/kick, idle abort, views
  Player        one class: id (public), token (secret), name, role, node, tickets
  MapGraph      parsed once from JSON; merges modes per node pair; valid-move search; keeps raw bytes
  MrXMove       record for a log entry (already reveal-filtered, so it doubles as the DTO)
  GameState · PlayerView · ValidMove   views sent to clients (JSON unchanged)
  GamePhase {LOBBY, IN_PROGRESS, ENDED} · TurnPhase · Role · TicketType · Winner {MR_X, DETECTIVES}
service/
  GameService   games map · per-game lock · token lookup · publish after every change · idle sweep
controller/
  GameController (request records nested) · MapController · ApiExceptionHandler
config/
  GameSettings     one @ConfigurationProperties record: map file, idle limit, detective tickets
  WebSocketConfig  + exact-match subscriptions only, + reject client SEND frames
exception/      GameNotFoundException 404 · ForbiddenException 403 · ConflictException 409
                (IllegalArgumentException → 400)
```

**Package note:** the sections as approved in chat put the core in `model/` and kept the views in `dto/`. The plan uses a new `game/` package holding both instead. That way the new core can be built and tested next to the old `model/` and `dto/` code, the build stays green after every task, and the old packages are deleted in one switch-over step. It also keeps each view next to the class that builds it (`Game.viewFor`).

**Deleted:**
- Main code: the `Player` interface, `AbstractPlayer`, `LobbyPlayer`, `DetectivePlayer`, `MrXPlayer`, `GameSession`, `MrXLogEntry`, `MrXLogEntryView`, `StartGameRequest`, `RemovePlayerRequest`, `WebConfig` (dev and prod are both same-origin) and `static/e2e.html`.
- Build: the Selenium dependency and the `e2e.headless` plumbing.

**How it works:**
- **Locking:** `Game` is not thread-safe. `GameService` wraps every call, reads included, in `synchronized (game)` (fixes B7).
- **Randomness at the edge:** `start(requester, rng)` picks Mr X, the detective order and the start nodes, then delegates to a deterministic package-private `start(requester, order, startNodes)` that tests call directly (`order.get(0)` becomes Mr X, the rest are detectives in turn order). Game also takes an `InstantSource`, so tests control the idle clock without sleeping.
- **One place builds views:** `game.viewFor(player)`, where `null` means the public view. `PlayerView` has no token field.
- **One publish rule:** after every successful change, send the public view to `/topic/games/{id}`, each player's view to their private topic, and valid moves to the current player's private valid-moves topic.
- **No stored turn index:** turn order is derived from `currentPlayerId`.
- **Host is the first lobby player** (what `LobbyView` already assumes), so there's no `hostPlayerId` field.
- **Config and map read once:** ticket counts and the idle limit come only from application.properties via `GameSettings`. The map is read once; `MapController` serves the cached bytes. An unknown mode in the map fails at startup instead of silently dropping the edge.

## 4. Game rules

Marked **(new)** where behaviour changes.

**Lobby**
- Names are 1–20 characters after trimming; `maxPlayers` is 2–6.
- The join code is 6 characters `[A-Z0-9]`, and joining is case-insensitive.
- Joining after the game has started, or when it's full, gives 409.
- Kicking: host only (403), lobby only (409), not yourself (400), and the target must exist (404).
- Leaving: a non-host is removed. **(new)** The host leaving ends the lobby: ENDED with abortReason "The host left the game", and the game stays in memory so polls see it. The last player out deletes the game.

**Start**
- Host only (403), lobby only (409), at least 2 players (400).
- Mr X is chosen at random, and the detectives go in a random order fixed for the game. Every player gets a distinct random start node.
- Tickets:
  - Mr X: ESCOOTER, BUS, TRAIN and FERRY unlimited (−1), 2 DOUBLE, and one Invisible (`BLACK`) per detective.
  - Detectives: 12/8/6/2 from config.
- The game starts in round 1 on Mr X's turn. **(new)** If Mr X starts boxed in, the detectives win at once.

**Moves**
- Only the current player may move (403), and only while the game is in progress (409).
- The ticket must be `ESCOOTER|BUS|TRAIN|FERRY|BLACK` or `DOUBLE_<one of those>`. **(new)** A plain `DOUBLE` or anything else gives 400 (B5).
- These give 400:
  - a destination that isn't adjacent;
  - a ticket whose mode isn't on the edge (except `BLACK`);
  - a ticket the player doesn't hold;
  - Mr X moving onto a detective.
- **(new)** `DOUBLE_<t>` while a double is pending gives 400 (B4). Otherwise it spends DOUBLE plus `t`, and Mr X moves again before the detectives.
- Log: one entry per Mr X leg, as `{round, leg 1|2, ticketUsed, nodeId, doubleMove}`.
  - `ticketUsed` is the transport or `BLACK`.
  - `nodeId` is set only on the final leg of a reveal round (2, 8, 13, 18, 24).
  - `doubleMove` is true on the first leg of a double.
- A detective moving onto Mr X means the detectives win.
- Turn order:
  - After Mr X's final leg, the next detective with a legal move plays. Detectives with no legal move are skipped without spending a ticket.
  - After the last detective, the round goes up by one; if round 24 has just finished, Mr X wins.
- **(new)** Whenever the turn passes to Mr X and he has no legal move, the detectives win (B1). The second leg of a double always has one, because he can go back the way he came.

**Views**
- Mr X sees everything.
- Everyone else, including the public view, gets Mr X's `nodeId = null` unless the current round's log has a revealed entry, i.e. a reveal round after his move.
- Tokens never appear in a view.

**Endings**
- **(new)** Anyone leaving an in-progress game removes them and ends the game, with abortReason "Mr. X has left the game" or "<name> has left the game" (replaces B3).
- If the current player hasn't moved for `game.turn-timer-seconds`, the game ends with abortReason "A player exceeded the N-minute turn limit".
- Ended games stay in memory. Leaving an ended game removes that player, and the last one out deletes it.

## 5. API and security

Each player gets a secret `playerToken` (a random UUID) at create/join; it's returned only in that response. `playerId` stays public. Calls that act as a player send the header `X-Player-Token`.

| Endpoint | After |
|---|---|
| `POST /api/games/create`, `POST /api/games/join` | response `{playerId, playerToken, gameState}` |
| `GET /api/games/{id}` | optional header (replaces `?playerId=`). A valid token gives your view. A missing or unknown token gives the public view, never a 403, so a kicked player's poll still shows them they're gone. |
| `POST /api/games/{id}/start` | header, no body |
| `DELETE /api/games/{id}/players/{targetId}` | header required. Token owner = target means leave; otherwise it's a kick (host only, lobby only). No body; returns 204. |
| `GET /api/games/{id}/valid-moves` | header (replaces `?playerId=`). Returns the caller's legal moves whoever's turn it is; 409 if the game isn't in progress. |
| `POST /api/games/{id}/moves` | header + body `{toNodeId, ticket}` |
| any acting call with a missing or unknown token | 403 `{error}` |
| unknown game / malformed or incomplete body | 404 `{error}` / 400 `{error}` |

`/api/map` and all response bodies other than create/join are unchanged.

**STOMP:**
- The lobby topic `/topic/games/{gameId}` is unchanged.
- The private topics are `/topic/games/{gameId}/players/{playerToken}` and `…/valid-moves`.
- The broker matches exact destinations only (an `AntPathMatcher` whose `isPattern` returns `false`, set via `MessageBrokerRegistry.setPathMatcher`), so wildcards receive nothing.
- An inbound interceptor rejects client SEND frames.

**Frontend** (6 files, about 12 call sites, no visible UI change):
- `stores/gameStore.ts`: `playerToken` goes in sessionStorage next to `playerId`, and `setGame` takes it.
- `views/CreateGameView.vue`, `views/JoinGameView.vue`: pass `result.playerToken` to `setGame`.
- `api/gameApi.ts`: the acting calls take the token and send `X-Player-Token`, and `playerId`/`requesterId` leave the bodies and query strings.
- `views/LobbyView.vue`, `views/GameBoardView.vue`: pass `store.playerToken` to those calls and use it in the two private topic strings.

Comparisons against public IDs (`isHost`, `isMyTurn`, `stillInGame`) are unchanged. A tab left open across the deploy has no token and has to rejoin.

## 6. Tests

| Category (proposal) | Class | Proves |
|---|---|---|
| Unit | `game/MapGraphTest` | parsing, merged modes, unknown mode fails, valid moves by ticket, mode and blocking, Invisible on any edge |
| Unit | `game/PlayerTest` | ticket spending: unlimited stays unlimited, finite counts go down, empty or missing tickets rejected |
| Unit | `game/GameTest` | every rule in §4 on hand-drawn graphs with exact positions, including view filtering and B1, B3–B6 |
| Mock | `service/GameServiceTest` | real game and map, mocked `SimpMessagingTemplate`: topic → payload after each operation; Mr X's position only on his topic; valid moves only to the current player; tokens only in topic names, never in payloads; unknown game or token errors; many threads joining at once never overfill a game (B7) |
| Lifecycle | `service/GameLifecycleTest` | complete scripted games through the service, one per ending: caught, boxed in, survives 24 rounds, player leaves, host leaves lobby, idle abort |
| Integration | `controller/ApiIntegrationTest` | the real app through MockMvc, no mocks: statuses and `{error}` bodies for every endpoint, token rules, 400 not 500 on bad input (B8), token in create/join, never in `GameState` |
| Integration | `controller/WebSocketIntegrationTest` | real server, real STOMP clients: lobby broadcast, filtered per-player state, valid-moves push; a wildcard subscription receives nothing and a client SEND is rejected (B2) |
| Functional | `e2e/FullGameE2ETest` | real server, HTTP and STOMP clients for every player, complete 2- and 4-player games on `test-map.json`: invariants after every move, the winner matches the final board, turn enforcement, abort on leave |
| Property + fuzz | `property/GamePropertyTest`, `property/ApiFuzzTest` (JUnit, seeded random) | random legal games on `map.json` with 2–6 players: Mr X never shares a node with a detective, tickets never negative, exactly one current player, round 1–24, every game ends, detectives never see Mr X outside a reveal. Fuzz: random tickets, nodes and names raise only the expected exceptions; random JSON to every endpoint never gives a 500 |
| Performance | `perf/MultiplayerPerfTest`, `@Tag("perf")`, opt-in | K concurrent games with a STOMP client per player: prints a table of move-submit → broadcast latency (p50/p95/max); every game ends in a consistent state |

**Tooling (pom.xml):**
- JaCoCo `prepare-agent` + `report` on every `mvn test` (writes `target/site/jacoco/`).
- PIT 1.30.0 with `pitest-junit5-plugin` 1.2.3, on demand, targeting `game` and `service` and excluding the server-based tests.
- Surefire excludes the `perf` tag by default.
- The spike on 2026-09-21 confirmed PIT runs on Boot 4.0.6 / JUnit 6.0.3.

**Rules for the tests:**
- Every bug B1–B8 has a named regression test.
- No reflection and no `Thread.sleep`. STOMP tests wait on queues with timeouts.
- Positions are set explicitly and random games are seeded.
- MockMvc is built from the `WebApplicationContext`, as today, so no extra Boot 4 test module is needed.

**Deleted tests:** `DetectivePlayerTest`, `MrXPlayerTest`, `LobbyPlayerTest`, `GameControllerTest`, `HuntingMrXApplicationTests` and the Selenium `FullGameE2ETest`.

## 7. Docs and report

- **`documentation/openapi.yaml`:**
  - token header and `playerToken`; the new request bodies;
  - `DOUBLE_<ticket>`; `PAUSED` removed; `Winner` enum;
  - the leave, kick and abort rules and the boxed-in rule;
  - token-keyed topics and the broker hardening; valid-moves errors;
  - port 8999; `info.version` 0.1.11 → 0.1.12.
- **`documentation/spec.md`:** random detective order, the double protocol, win and abort conditions, tokens. Pause, grace and `winner: "ABORTED"` come out.
- **`documentation/plans/states-diagrams.md`:** Paused, grace and AutoSkipped come out; PlayerLeft → aborted, IdleTimeout → aborted and Mr X boxed in → detectives win go in.
- **`documentation/plans/classdiagram.md`:** redrawn for the new classes. It still showed `GameRepository` (removed in an earlier refactor) and the Player class hierarchy.
- **`documentation/plans/flowcharts.md`:** the Mr X flow gets the boxed-in check, the reveal after his move, and the right reveal rounds (it said 3, 8, 13, 18, 24). The overview gets the abort paths.
- **`backend/doc.md`:** Spring Boot 4, the real endpoints, the real config, and the test, coverage, PIT and perf commands.
- **`frontend/doc.md`:** the sessionStorage and store now hold `playerToken`.
- **`README.md`:** the testing section (no Firefox), "261-node" → 216, and no grace period.
- **`CLAUDE.md`:** the Game State Machine section, the "Turn timers" design decision, and a Documentation Layout line for `documentation/backend-fixes.md`.
- **`documentation/backend-fixes.md`** (new): each fix with its root cause, change, regression test and status. Written now as the plan; updated with the real test names and results as each fix lands.
- **`documentation/final_report/final_report.tex`** outline bullets:
  - line 44 (test count);
  - line 108 (disconnect and timer behaviour);
  - lines 171–175 (suite, categories, browserless e2e, meta-testing now present with numbers);
  - line 180 (perf numbers);
  - plus a security lesson (bypassable filtering, fixed with tokens and broker hardening) and the rewrite as evidence in "what worked".
- **Left as-is (historical records):** `documentation/presentation/presentation.md` and `documentation/project_proposal/`.

## 8. Out of scope

- Removing ended games from memory; the games map gets a `ponytail:` comment naming the ceiling.
- Revealing Mr X at game end; spacing out start positions (a boxed-in start is 0.026% likely on `map.json`).
- Join-code collision checks (36⁶ codes).
- Automated frontend tests.

## 9. Done when

- `mvn test` passes, `mvn test -Dgroups=perf -DexcludedGroups=` runs the harness, PIT runs, and `npm run build` is clean.
- B1–B8 each have a passing regression test.
- `game` and `service` reach at least 90% line and 85% branch coverage and at least 80% mutation score; the real numbers are reported either way.
- A manual 2-browser smoke test on the dev server works: create, join, start, a move each, a double, leave.
- openapi.yaml is in sync and bumped, and the docs and report bullets are updated.
