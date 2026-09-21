# Backend fixes

This doc lists every problem found in the backend review on 2026-09-21: what goes wrong, why, how it's being fixed, and which test proves the fix. The design behind the fixes is in `docs/superpowers/specs/2026-09-21-backend-rewrite-design.md`.

Code references point at commit `88bf636`, the last commit before the rewrite. Paths are relative to `backend/src/main/java/com/huntingmrxwellington/`.

**Status:** every item below is **Planned**. Each one changes to **Fixed**, with its test names, as it lands.

## Summary

| ID | Problem | Severity | Status |
|---|---|---|---|
| B1 | Game freezes when Mr X is boxed in | High | Planned |
| B2 | Any player can see Mr X's position and act as other players | High | Planned |
| B3 | A detective leaving mid-game freezes the game or skips a turn | High | Planned |
| B4 | Mr X can chain DOUBLE tickets into three moves | Medium | Planned |
| B5 | Plain `DOUBLE` skips the mode check and hides the transport | Medium | Planned |
| B6 | Host leaving the lobby strands everyone else | Medium | Planned |
| B7 | Lobby operations race with each other | Low | Planned |
| B8 | Missing request fields return 500 instead of 400 | Low | Planned |

## Game engine bugs

### B1. Game freezes when Mr X is boxed in

**What happens:** if detectives hold every node next to Mr X, he can't make a legal move. The server rejects everything he submits, and nobody else is allowed to move, so the game sits there until the 15-minute idle abort kills it.

**Why:** `GameService.advanceRound` (`service/GameService.java:333`) and `startGame` (`:136`) hand the turn to Mr X without checking whether he can move. There's no rule for this case at all.

**Evidence:** this is why `FullGameE2ETest.fourPlayerGame_playsToCompletion` fails at random. On `test-map.json`, 36 of the 120 possible 4-player starts (30%) box Mr X in on turn 1. On the real map it's the endgame good detectives play for.

**Fix:** whenever the turn passes to Mr X (including at game start), check his legal moves. If he has none, the detectives win, as in the board game. The second leg of a double always has a legal move, because he can go back the way he came.

**Tests:** `GameTest` covers boxed-in at the start of a round and at game start; `GameLifecycleTest` plays a full game to this ending.

### B2. Any player can see Mr X's position and act as other players

**What happens:** the hidden-movement part of the game can be bypassed from the browser dev tools in several ways:

1. Player IDs are sent to everyone in `players[]`, and the same IDs are the only credential.
   - `GET /api/games/{id}?playerId=<Mr X's id>` returns Mr X's own view, including his position (`service/GameService.java:100`).
   - `GET /api/games/{id}/valid-moves?playerId=<Mr X's id>` shows where he can go, which gives away where he is (`:193`).
   - Moves, starts and kicks trust whatever ID is in the request body (`:110`, `:208`, `controller/GameController.java:52`).
2. `DELETE /api/games/{id}/players/{anyId}` with no body is treated as that player leaving (`controller/GameController.java:52`). Anyone can remove anyone, including making Mr X "leave", which aborts the game.
3. Private STOMP topics are named with the public player ID (`service/GameService.java:480`), so anyone can subscribe to Mr X's.
4. The in-memory STOMP broker accepts wildcard subscriptions (`config/WebSocketConfig.java:15`). Subscribing to `/topic/games/{id}/players/**` receives every player's private state without knowing any ID. This was confirmed on 2026-09-21 against Spring's `DefaultSubscriptionRegistry`.
5. Clients can probably also send messages straight to `/topic/...`, which would let them fake server broadcasts to the lobby. A test will confirm this before it's blocked.

**Fix:**
- Each player gets a secret `playerToken` when they create or join a game. It's returned only in that response and is never included in any broadcast.
- Every call that acts as a player sends it in an `X-Player-Token` header. The player ID stays public for display and turn checks.
- Private STOMP topics are named with the token.
- The broker only matches exact destinations, so wildcards receive nothing.
- Client SEND frames are rejected.
- The frontend stores the token next to the player ID and sends it on those calls.

**Tests:**
- `ApiIntegrationTest`: token rules on every endpoint.
- `WebSocketIntegrationTest`: a wildcard subscriber receives nothing, a client SEND is rejected, and a detective's topic never carries Mr X's position.
- `GameServiceTest`: tokens appear in topic names but never in message payloads.

### B3. A detective leaving mid-game freezes the game or skips a turn

**What happens:** if a detective leaves during their own turn, `currentPlayerId` still points at them, so nobody can move and the game freezes until the idle abort. If an earlier detective leaves, the stored turn index points one place too far along the list, and the next detective's turn gets skipped.

**Why:** `leaveGame` (`service/GameService.java:166`) removes the player without updating `currentPlayerId` or `currentDetectiveIndex`. Turn advancement (`:311`) trusts the stale index. The remaining players aren't told either, because the leave is only broadcast to the lobby topic, which the game board doesn't listen to.

**Fix:** new rule: **any player leaving an in-progress game ends it**, with an abort reason naming who left. The frontend already sends everyone to the end screen when a game ends. The stored turn index goes away too: turn order is worked out from the current player each time.

**Tests:** `GameTest` covers a detective leaving on their turn, a detective leaving off their turn, and Mr X leaving; `GameLifecycleTest` plays a game to this ending.

### B4. Mr X can chain DOUBLE tickets into three moves

**What happens:** while the second leg of a double move is pending, Mr X can play another `DOUBLE_...` ticket, which gives him a third move in the same turn. The UI hides the button, but the server never checks.

**Why:** `applyMrXMove` (`service/GameService.java:249`) starts a new double without checking `mrXDoubleMovePending`.

**Fix:** a DOUBLE while a double is pending is rejected with 400 "A double move is already in progress".

**Tests:** `GameTest`.

### B5. Plain `DOUBLE` skips the mode check and hides the transport

**What happens:** sending the ticket as just `"DOUBLE"` (instead of `"DOUBLE_BUS"` and so on) moves Mr X to any adjacent node without checking the transport mode. It also logs `DOUBLE` as the ticket used, so detectives can't see how he travelled. That makes it a free Invisible ticket. The frontend never sends plain `DOUBLE`, but the OpenAPI spec and `spec.md` both document it as the way to start a double move.

**Why:** `service/GameService.java:256` only checks adjacency when there's no transport suffix, and `:273` logs the bare `DOUBLE`.

**Fix:** the first leg of a double must be `DOUBLE_<ESCOOTER|BUS|TRAIN|FERRY|BLACK>`. A plain `DOUBLE` gets a 400. The docs are updated to match what the frontend already sends.

**Tests:** `GameTest`, plus a request-level check in `ApiIntegrationTest`.

### B6. Host leaving the lobby strands everyone else

**What happens:** when the host leaves the lobby, the game is deleted with no broadcast. The other players stay on the lobby screen. Their background polls get a 404, the frontend ignores poll errors, and nothing tells them the game is gone.

**Why:** `leaveGame` (`service/GameService.java:167`) calls `games.remove` straight away and returns.

**Fix:** the host leaving ends the lobby for everyone. The game is marked ended with the reason "The host left the game" and stays in memory, so both the live broadcast and the polls show it. The lobby screen already displays this message for ended games.

**Tests:** `GameTest`, `GameLifecycleTest`.

### B7. Lobby operations race with each other

**What happens:** only move submission is synchronized. Joining, starting, leaving and kicking can run at the same time as each other and as the frontend's 6-second polls. Two joins at the same moment can both pass the "game is full" check and overfill the game, and the player list can change while a broadcast is iterating over it.

**Why:** only `submitMove` holds a lock (`service/GameService.java:205`), and the player list is a plain `ArrayList` (`model/GameSession.java:13`).

**Fix:** every operation on a game, reads included, runs inside `synchronized (game)` in `GameService`.

**Tests:** `GameServiceTest` starts many threads joining one game at once and checks it never goes over `maxPlayers`.

### B8. Missing request fields return 500 instead of 400

**What happens:** `POST /api/games/{id}/start` with an empty body, or a move without a ticket, throws a `NullPointerException`, and the client gets a 500 with no useful message.

**Why:** the service calls methods on request fields without checking them for null (`service/GameService.java:110`, `:208`, `:216`).

**Fix:** requests are validated at the controller and service boundary. Missing, blank or malformed fields get a 400 with an `{error}` message, and malformed JSON gets the same treatment.

**Tests:** `ApiIntegrationTest`; the jqwik fuzz tests send random JSON to every endpoint and check none of them returns a 500.

## Other fixes

| Problem | Fix |
|---|---|
| `spec.md`, the OpenAPI spec, `CLAUDE.md`, the README and the final report describe pause-on-disconnect, a 60 s grace period and a 120 s auto-skip turn timer. None of it exists: the code aborts a game after 15 minutes without a move. | The docs and report are corrected to what the game really does. `PAUSED` and the unused `grace-period-seconds` key are removed. The idle limit is read from `game.turn-timer-seconds` (900 s). |
| Detective ticket counts are defined in three places with two different sets of values (12/8/6/2 in `application.properties`; 10/8/4/2 in the `@Value` defaults and `backend/doc.md`). | One source of truth: `application.properties`, read through a single settings record. |
| `MapGraph` silently drops any edge with an unrecognised transport mode. If the map ever had two edges between the same pair of nodes, only the first edge's modes would count. | An unknown mode stops the server at startup with a clear error, and modes on duplicate edges are merged. |
| `MapController` reads the 1.8 MB map from disk on every request. | The map is read once at startup and served from memory. |
| A name made only of control characters (such as U+0000) passes the blank check, then `trim()` strips it down to an empty name. | Names are checked after `strip()`, which uses the same whitespace rule as the blank check. The jqwik name property covers it. |
| `backend/doc.md` says Spring Boot 3.5 and `POST /api/games`; the OpenAPI spec says port 8080 and lists valid-moves errors that never happen; the README says the map has 261 nodes (it has 216). | Corrected along with the rest of the docs. |

## Test suite fixes

| Problem | Fix |
|---|---|
| The rules engine had no unit tests. `MapGraph` was mocked to always return "no moves", so moves, turn order, double and Invisible tickets, reveals, catching and the round-24 win were never unit tested. Coverage without the browser test was 65% of lines and 45% of branches. | The rules move into a plain `Game` class, tested directly on small hand-drawn maps with exact positions (`GameTest`, `MapGraphTest`, `PlayerTest`). |
| The Selenium test only used Firefox to send HTTP requests, didn't test the UI, and was flaky because of B1. | Replaced by a browserless full-game test that uses real HTTP and STOMP clients and checks the game's invariants after every move. |
| Nothing tested the WebSocket side, including the role filtering that hides Mr X. | `WebSocketIntegrationTest` with real STOMP clients. |
| About 15 tests duplicated others and about 10 only checked getters. Tests reached into private fields with reflection. | Removed or rewritten. No reflection in the new suite. |
| None of the coverage, mutation, fuzz, property-based or performance testing promised in the proposal existed. | JaCoCo coverage on every run, PIT mutation testing on demand, jqwik property and fuzz tests, and an opt-in multiplayer latency test. |

## Behaviour changes players will notice

- If Mr X can't move at the start of his turn, the detectives win.
- If anyone leaves a game in progress, the game ends for everyone.
- If the host leaves the lobby, the lobby closes for everyone with a message.
- Mr X can't play a second DOUBLE during a double move.
