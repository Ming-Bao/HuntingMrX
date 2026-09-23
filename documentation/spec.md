# Hunting Mr. X: Wellington Edition — Implementation Specification

## 1. Tech Stack

| Layer | Technology |
|---|---|
| Frontend framework | Vue.js 3 + Pinia (state management) + Vue Router, TypeScript, Tailwind CSS 4 |
| Map library | MapLibre GL v4 |
| Map tiles | CARTO basemap vector styles without labels: Dark Matter by default, Positron and Voyager selectable on the board (free, no API key) |
| Backend framework | Java 21 + Spring Boot 4 |
| Real-time comms | STOMP over SockJS (Spring WebSocket) |
| Build tools | Vite (frontend), Maven (backend) |
| Containerisation | Docker (single container, two processes under `supervisord`: Spring Boot on `:8999`, nginx on `:80` serving the built frontend and reverse-proxying `/api` and `/ws` to the backend — see `Dockerfile`, `docker/`) |

---

## 2. Game Rules

### 2.1 Roles

| Role | Count | Description |
|---|---|---|
| Mr X | 1 | Hidden player. Moves first each round. Position revealed only on reveal rounds. |
| Detective | 1–5 | Cooperative team. All positions visible to everyone at all times. |

### 2.2 Transport Modes

Four modes: `ESCOOTER`, `BUS`, `TRAIN`, `FERRY`.

Each edge in the graph declares the mode(s) that traverse it. A player may only move along an edge if they hold a ticket matching at least one of that edge's modes.

Map rendering uses one distinct colour per mode (hex values in §8.1):
- Escooter: green
- Bus: red
- Train: purple
- Ferry: cyan

### 2.3 Ticket Allocation

**Mr X (per game):**

| Ticket | Count |
|---|---|
| ESCOOTER | Unlimited |
| BUS | Unlimited |
| TRAIN | Unlimited |
| FERRY | Unlimited |
| DOUBLE | 2 |
| BLACK | N (where N = number of detectives at game start) |

**Each detective (per game):**

```
game.detective-escooter-tickets = 12
game.detective-bus-tickets      = 8
game.detective-train-tickets    = 6
game.detective-ferry-tickets    = 2
```

These are global constants defined in `application.properties`. Detectives do not share tickets.

### 2.4 Turn Structure

The game runs for rounds 1–24. Within each round:

1. **Mr X's turn**: Mr X makes one move (or two with a DOUBLE ticket). If he has no legal move when his turn starts, because detectives hold every node next to him, the detectives win.
2. **Detective turns**: each detective moves in a random order fixed when the game starts; a detective with no valid moves is automatically skipped.

The round counter increments after all detectives have moved (or been skipped).

### 2.5 Reveal Rounds

On rounds **2, 8, 13, 18, 24**, the node Mr X ends his move on is revealed: it is written into that round's final `mrXLog` entry, and detectives see it as Mr X's `nodeId` until the round ends.

### 2.6 Double Move

Mr X plays a double move by putting `DOUBLE_` in front of the first leg's ticket:

1. First leg: `{toNodeId, ticket: "DOUBLE_<ESCOOTER|BUS|TRAIN|FERRY|BLACK>"}`. The server spends one DOUBLE ticket plus the named ticket, and it stays Mr X's turn (`mrXDoubleMovePending: true`).
2. Second leg: `{toNodeId, ticket: "<ESCOOTER|BUS|TRAIN|FERRY|BLACK>"}`.

A bare `DOUBLE` ticket is rejected, and so is a second `DOUBLE_...` while a double is pending. Both legs happen before any detective moves and are logged as two `mrXLog` entries for the same round; the first has `doubleMove: true`. On a reveal round only the second leg's node is revealed.

### 2.7 Invisible Ticket

Displayed to players as the **Invisible ticket** (wire value `BLACK`, kept from the original ticket colour naming).

Mr X submits `{toNodeId, ticket: BLACK}`. The move is valid on **any edge regardless of mode** — Mr X does not need a matching transport ticket. One `BLACK` ticket is consumed.

Detectives see the Invisible ticket (`BLACK`) in the log. The actual destination is hidden on non-reveal rounds. On reveal rounds the destination is still revealed, but the ticket type still shows as Invisible.

### 2.8 Win Conditions

| Outcome | Condition |
|---|---|
| Detectives win | A detective's move ends on Mr X's node (caught), or Mr X has no legal move when his turn starts (boxed in) |
| Mr X wins | Round 24 completes with Mr X not caught |
| Game aborted | Any player leaves a game in progress, or the current player makes no move for `game.turn-timer-seconds` (15 minutes). `winner` stays null and `abortReason` says why. |

In the lobby, a non-host player who leaves is simply removed; the host leaving closes the lobby for everyone (`phase: ENDED`, `abortReason: "The host left the game"`).

### 2.9 Movement Constraints

- Mr X **cannot** move to a node currently occupied by any detective.
- Multiple detectives **may** occupy the same node simultaneously.
- A player must hold at least one ticket matching a mode on the chosen edge (or use an Invisible ticket as Mr X).
- A detective with zero valid moves from their current node is automatically skipped — no ticket is consumed.

---

## 3. Wellington Graph

### 3.1 File Format

The board is one JSON file in `backend/src/main/resources/static/`, named by `game.map-file` (`map.json` by default). The backend reads it once at startup and serves it at `GET /api/map`. A missing file, an unknown mode or an edge to a node that doesn't exist stops the server from starting.

```json
{
  "nodes": [
    { "id": 1, "lat": -41.2787, "lng": 174.7798, "label": "1", "offRoad": false },
    { "id": 2, "lat": -41.2841, "lng": 174.7756, "label": "2", "offRoad": false }
  ],
  "edges": [
    {
      "from": 1,
      "to": 2,
      "modes": ["BUS", "ESCOOTER"],
      "coordinates": [[174.7798, -41.2787], [174.7820, -41.2801], [174.7756, -41.2841]]
    }
  ]
}
```

- `id` is a unique integer; `label` is its display name.
- `from`/`to` reference node `id` integers.
- `modes` is an array of `ESCOOTER`, `BUS`, `TRAIN` or `FERRY` (uppercase, the `TicketType` names). An edge with an empty `modes` array joins nothing.
- `coordinates` is an array of `[lng, lat]` pairs (GeoJSON coordinate order) drawn as the edge, pre-computed from OSM road data, not generated at runtime.
- Edges are **undirected**: movement is valid in both directions. If two edges join the same pair of nodes, their modes are merged.
- `offRoad` is written by the map creator and ignored by the game.

### 3.2 Scope

`map.json` has 216 nodes and 372 edges spanning about 22 km × 24 km of the Wellington region (edge-mode counts: 323 e-scooter, 257 bus, 9 train, 2 ferry). It was built with the map creator in `mapCreator/`. `test-map.json` is a 5-node, 7-edge board for quick manual tests.

### 3.3 Starting Positions

On game start the server randomly assigns each player a distinct node. Mr X's starting node is **never sent to detectives**: his `nodeId` is null in every view but his own until a reveal round.

---

## 4. System Architecture

```
Browser (Vue.js + Pinia + MapLibre GL)
  │
  ├── REST HTTP/JSON ──────────────────────┐
  │   (lobby, move submission)             │
  │                                        ▼
  └── WebSocket (STOMP/SockJS) ──► Spring Boot Backend
                                     ├── REST Controllers
                                     ├── WebSocket STOMP Broker
                                     ├── Game Engine (pure Java)
                                     └── In-memory store
                                           ConcurrentHashMap<gameId, Game>
```

- All authoritative game state lives in memory on the server. The client holds only a display copy received over REST and WebSocket.
- The map file is read once at startup and served at `GET /api/map`; the game board fetches it when it mounts.
- No database in v1. Restarting the server terminates all active games.
- In the Docker deployment, nginx serves the compiled Vue frontend and reverse-proxies `/api` and `/ws` to Spring Boot in the same container (see the Containerisation row above). For local development, the Vite dev server proxies the same paths instead (`frontend/vite.config.ts`).

---

## 5. Data Models

### 5.1 Server-side Java

The rules live in plain Java in `backend/src/main/java/com/huntingmrxwellington/game/`, with no Spring:

```
Game
  String id, joinCode
  GamePhase phase                    // LOBBY | IN_PROGRESS | ENDED
  int round                          // 0 in the lobby, then 1–24
  TurnPhase turnPhase                // MR_X_TURN | DETECTIVE_TURN; null outside IN_PROGRESS
  Player current                     // whose move it is; null outside IN_PROGRESS
  boolean doubleMovePending          // between the two legs of a double move
  List<Player> players               // lobby: join order (host first); in play: Mr X first, then detectives in turn order
  List<MrXMove> mrXLog
  Winner winner                      // null | MR_X | DETECTIVES
  String abortReason                 // set when a player leaves or the idle limit passes

Player
  String id                          // public, sent to everyone
  String token                       // secret, only ever sent to this player at create/join
  String name
  Role role                          // null in the lobby
  Integer node                       // null in the lobby
  Map<TicketType, Integer> tickets   // -1 = unlimited

MrXMove (record)
  int round, int leg                 // leg 2 is the second leg of a double
  TicketType ticketUsed              // ESCOOTER | BUS | TRAIN | FERRY | BLACK
  Integer nodeId                     // null unless reveal round AND final leg
  boolean doubleMove                 // true on the first leg of a double
```

### 5.2 Client-facing views (JSON)

`GameState`, `PlayerView`, `MrXMove` and `ValidMove` are defined in `documentation/openapi.yaml` (components/schemas). `Game.viewFor` builds each view per viewer, and none of them carries a player token.

---

## 6. REST API

`documentation/openapi.yaml` has the full contract. In short: create and join return `{playerId, playerToken, gameState}`; every call that acts as a player (start, leave or kick, valid moves, move) sends the token in the `X-Player-Token` header; errors are `{ "error": "<message>" }` with status 400, 403, 404 or 409.

---

## 7. WebSocket Protocol (STOMP over SockJS)

### 7.1 Connection

Endpoint: `/ws` (SockJS). Clients connect using the `@stomp/stompjs` + `sockjs-client` libraries.

### 7.2 Server → Client Subscriptions

| Destination | Payload | Sent when |
|---|---|---|
| `/topic/games/{gameId}` | `GameState` (public view) | After every change |
| `/topic/games/{gameId}/players/{playerToken}` | `GameState` (this player's view) | After every change |
| `/topic/games/{gameId}/players/{playerToken}/valid-moves` | `ValidMove[]` | After every change, to the player whose turn it is |

Private topics are named by the secret token, and the broker only matches exact destinations, so another client can't subscribe to them; wildcard subscriptions receive nothing.

### 7.3 Client → Server

All game actions go through **REST**. WebSocket is receive-only for clients: the server drops any SEND frame a client sends.

### 7.4 Role Filtering Rules

When building the view for a given player:

- **Detective or public view of Mr X's `nodeId`**: `null` unless the current round is a reveal round **and** Mr X has made his move this round.
- **`mrXLog[i].nodeId`**: `null` unless `mrXLog[i]` is the final leg of a reveal round (the same for everyone, Mr X included).
- **Mr X view**: full state, including his own `nodeId` and every detective's.

---

## 8. Design System

### 8.1 Colour Palette

There are two themes, dark (the default) and light. The sun/moon button switches between them on every page except the game board and end screen, and the choice is kept in `localStorage`. The classes below are the dark theme; components pair each with a lighter class for the light theme (e.g. `bg-gray-50 dark:bg-gray-950` on the board). Tailwind 4 defines these colours in OKLCH, so the class is the reference, not a hex value.

| Role | Tailwind class (dark theme) |
|---|---|
| Page background | `bg-gray-950` |
| Card / panel | `bg-gray-900` / `bg-gray-800` |
| Primary action | `bg-blue-600` |
| Secondary action | `bg-gray-700` |
| Success / start | `bg-green-600` |
| Destructive | `bg-red-600` |
| Body text | `text-white` |
| Muted text | `text-gray-400` |

**Transport mode colours** (map lines and ticket UI), from `frontend/src/utils/transportModes.ts`:

| Mode | Hex |
|---|---|
| Escooter | `#22c55e` (green) |
| Bus | `#ef4444` (red) |
| Train | `#8b5cf6` (purple) |
| Ferry | `#06b6d4` (cyan) |
| Invisible (`BLACK`) | `#64748b` (grey; ticket UI only, no map lines) |

### 8.2 Typography

Tailwind's default system font stacks: `font-sans` for text, `font-mono` for join codes. No web fonts are loaded.

### 8.3 Icons

Lucide (`lucide-vue-next`), the Vue port of the Lucide icons used in the Figma reference. Icons in use: `Users` and `UserPlus` (landing page), `ArrowLeft` (page headers), `ClipboardCopy` and `Check` (copy buttons; `Check` also marks the chosen ticket in the reachable-nodes list), `Sun` and `Moon` (theme toggle), `Trophy` (end screen), `Scooter`, `Bus`, `TrainFront`, `Ship` and `EyeOff` (transport modes and the Invisible ticket), and `LocateFixed` (centre the map on your node).

### 8.4 Border Radius

No global override. Cards and buttons use Tailwind's `rounded-lg` (0.5rem); badges and pills use `rounded-full`.

---

## 9. Frontend Screens and Components

### 9.1 Landing Page (`/`)

Layout: vertically centred, full-viewport-height, `bg-gray-950` (white in the light theme). The theme toggle sits in the top-right corner here and on every other page except the game board and end screen.

Structure (top to bottom, centred):

1. **Hero block**
   - Title: "Hunting Mr. X", `text-4xl font-bold`
   - Subtitle: "Wellington Edition", `text-blue-400 italic text-lg`
   - Tagline: "Hunt down Mr. X across Wellington's streets", `text-sm` muted
2. **Button group** (stacked)
   - **Create Game**: `bg-blue-600`, `Users` icon, routes to `/create`
   - **Join Game**: `bg-gray-700`, `UserPlus` icon, routes to `/join`
3. **Attribution line**: game mechanics based on the Ravensburger board game; non-commercial student project, not affiliated with Ravensburger.

No header or nav bar.

---

### 9.2 Create Game Page (`/create`)

- Page header "Create Game" with an `ArrowLeft` back button to `/`
- Form card:
  - "Your Name" input (max 20 characters)
  - "Max Players" `<select>`, "2 players" to "6 players", default 4
  - Error banner for server errors, e.g. a blank name
  - **Create Game** button: calls `POST /api/games/create`, keeps the returned player id, token and state, and goes to the lobby

---

### 9.3 Join Game Page (`/join`, `/:code`)

- Page header "Join Game" with a back button to `/`
- Form card:
  - "Your Name" input (max 20 characters)
  - "Game Code" input (monospace, uppercase, max 6 characters). A shared link `/<code>` opens this page with the code filled in.
  - Error banner for server errors, e.g. "Game not found" or "Game is full"
  - **Join Game** button: calls `POST /api/games/join` and goes to the lobby

---

### 9.4 Lobby (`/lobby/:id`)

- Page header "Game Lobby"; its back button leaves the game
- **Join code card**: the 6-character code with a copy button, the join link (`/<code>`) with its own copy button, and a QR code of that link ("Scan to join")
- **Player list** "Players (n/max)": the host first with a `Host` badge, everyone else with `Ready`, and "Waiting for player…" for each empty slot. The host sees a **Kick** button on every other player.
- The host gets **Start Game** (`POST /api/games/{id}/start`), disabled with "Need at least 2 players to start" until someone joins. Everyone else sees "Waiting for the host to start the game…".
- **Leave Game** button
- Updates arrive on the public topic `/topic/games/{id}`, with a REST re-sync on every reconnect and a 6 s poll. When the game starts everyone moves to the board. A kicked player sees "You were kicked"; if the host leaves, everyone else sees "Game ended" with the reason.

---

### 9.5 Game Board (`/game/:id`)

**Header:** back button, title, your role badge, a centred turn badge ("Your Turn", "Mr X's Turn" or "<name>'s Turn"), a "Double Move — 2nd leg" badge while a double move is pending, and "Round n / 24" with the next reveal round.

**Map panel (MapLibre GL):**
- CARTO basemap without labels; a Dark / Light / Voyager switch picks the style, and zooming out stops at the Wellington region.
- Edges are drawn from their `coordinates`, one colour per mode (§8.1). Nodes are circles; players are markers.
- On your turn your reachable nodes are highlighted. Clicking one opens a popup with a button per transport mode you can pay with.
- A node search box, a button that centres the map on your node, and a mode legend.

**Side panel:**
- Players: name, colour and node, with `?` for Mr X when you can't see him. Clicking a node number centres the map on it.
- Your tickets, with ∞ for Mr X's unlimited ones. Mr X also gets **Use Double Ticket** while he has one left.
- Mr X log: the ticket used each round, a DOUBLE tag on the first leg of a double (the second leg is labelled like "2b"), and a reveal row with his node on reveal rounds.
- Reachable nodes: each node you can reach with a chip for every ticket that pays for it, the Invisible ticket included for Mr X.
- Move: the chosen node and ticket, and **Confirm Move** (`POST /api/games/{id}/moves`). When it isn't your turn this reads "Waiting for other players...".
- **Leave Game**, which ends the game for everyone.

**Popups:** a blocking card announces your role when the game starts, "Your Turn" when your turn begins, and Mr X's reveal ("You've Been Revealed" for him, "Mr X Revealed" for detectives). If several arrive at once they stack in one card, and one click dismisses them.

**Mr X double move:** Mr X presses **Use Double Ticket** and picks the first leg, which is sent as `DOUBLE_<ticket>`. The server answers with `mrXDoubleMovePending = true`, the header shows the 2nd-leg badge, and he picks the second leg from his new node.

**Markers:** every detective has their own colour and is always visible. Mr X always sees his own marker; detectives see it only after his move in a reveal round, until that round ends.

---

### 9.6 Game Over Page (`/game/:id/end`)

Layout: vertically centred, full-viewport-height.

1. **Trophy icon** (`Trophy`, 64 px): red when Mr X wins, blue otherwise
2. **Banner**: "Mr. X Escaped!" on `bg-red-600`, "Detectives Win!" on `bg-blue-600`, or "Game Over" (blue) when the game was aborted
3. **Summary card**: Game Code, Rounds Played, and Result ("Mr. X wins", "Detectives win" or the abort reason)
4. **Narrative line**: "Mr. X survived all 24 rounds undetected.", "The detectives caught Mr. X on round 17." (also used when he was boxed in), or the abort reason, e.g. "Alice has left the game"
5. **Buttons**: **Back to Home** (`/`) and **Play Again** (`/create`)

---

## 10. Leaving, Idling and Disconnects

- **A dropped WebSocket doesn't end or pause the game.** User testing showed connections drop often, so there is no pause and no grace period. Clients reconnect automatically (STOMP), re-sync over REST on every reconnect, and poll `GET /api/games/{id}` every 6 s as a fallback.
- **Leaving:** any player leaving a game in progress ends it for everyone (`phase: ENDED`, `abortReason` names who left). In the lobby a non-host who leaves is removed; the host leaving closes the lobby for everyone.
- **Idle limit:** every 30 s the server checks each game in progress. If the current player hasn't moved for `game.turn-timer-seconds` (900 s by default), the game ends with `abortReason: "A player exceeded the 15-minute turn limit"`. There is no auto-skip.

---

## 11. API Documentation

`documentation/openapi.yaml` is the authoritative OpenAPI 3.1.0 specification for the REST endpoints and, under `webhooks:`, the STOMP topics. It must be kept in sync with the code at all times.

**Rule (the full version is in `CLAUDE.md`):** any change to the API surface updates `documentation/openapi.yaml` in the same change. That covers adding, removing or renaming an endpoint; changing a request or response shape; a new `GamePhase`, `TurnPhase`, `Role`, `Winner` or `TicketType` value; and any new, changed or removed STOMP topic, frontend subscription, or client-side reaction to a topic. Every change bumps the patch version in `info.version`.

The file can be viewed at [Swagger Editor](https://editor.swagger.io/) by pasting the YAML, or with any OpenAPI-compatible viewer.

---

## 12. Non-functional Requirements

| Requirement | Target |
|---|---|
| Map render time (200 nodes / 285 edges) | < 3 s (per MapLibre GL benchmark in `documentation/test_map_api/`) |
| WebSocket state push latency | < 500 ms from move submission to all clients receiving update (measured p95 8.3 ms, max 36.6 ms over 10 concurrent 6-player games; `MultiplayerPerfTest`) |
| Concurrent games | Multiple simultaneous games supported; per-game `synchronized` lock in `GameService` |
| Persistence | None in v1 — in-memory only |
| Browser support | Latest Chrome, Firefox, Safari |
| Mobile | Not a v1 requirement; desktop-first layout |

Map performance note: all road geometry is pre-computed in `map.json`. No routing API calls are made at runtime. This was the key finding from the map API evaluation — live routing APIs are too slow and too costly for this graph size.
