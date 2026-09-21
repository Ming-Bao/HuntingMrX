# Hunting Mr. X: Wellington Edition — Implementation Specification

## 1. Tech Stack

| Layer | Technology |
|---|---|
| Frontend framework | Vue.js 3 + Pinia (state management) + Vue Router |
| Map library | MapLibre GL v4 |
| Map tiles | CartoDB Dark NoLabels raster tiles (free, no API key) |
| Backend framework | Java 21 + Spring Boot 3 |
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

Map rendering uses one distinct colour per mode:
- Escooter — green
- Bus — blue
- Train — orange
- Ferry — purple

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

One static file, served by the Spring Boot backend from `src/main/resources/static/`:

**`map.json`** — nodes and edges with inline road geometry:

```json
{
  "nodes": [
    { "id": 1, "lat": -41.2787, "lng": 174.7798 },
    { "id": 2, "lat": -41.2841, "lng": 174.7756 }
  ],
  "edges": [
    {
      "from": 1,
      "to": 2,
      "modes": ["bus", "escooter"],
      "geometry": {
        "coordinates": [[174.7798, -41.2787], [174.7820, -41.2801], [174.7756, -41.2841]]
      }
    }
  ]
}
```

- `id` is a unique integer.
- `from`/`to` reference node `id` integers.
- `geometry.coordinates` is an array of `[lng, lat]` pairs (GeoJSON coordinate order), pre-computed from OSM road data — not generated at runtime.
- Edges are **undirected** — movement is valid in both directions.
- `modes` is a non-empty array of lowercase mode strings.

### 3.2 Scope

The actual Wellington node set is a separate task. Format is fixed by this spec. Target: 50–150 nodes covering Wellington CBD and inner suburbs, connected by the four transport modes wherever real Wellington infrastructure exists.

### 3.3 Starting Positions

On game start the server randomly assigns each player a distinct node. Mr X's starting node is **never sent to detectives** — it is stored server-side only and excluded from detective-view `PlayerDTO` objects.

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
                                           ConcurrentHashMap<gameId, GameSession>
```

- All authoritative game state lives in memory on the server. The client holds only a display copy received via WebSocket.
- `map.json` is served as a static file — the frontend fetches it once on page load.
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

| Role | Tailwind class | Hex |
|---|---|---|
| Page background | `bg-gray-950` | `#0a0a0a` |
| Card / panel | `bg-gray-900` / `bg-gray-800` | `#111827` / `#1f2937` |
| Primary action | `bg-blue-600` | `#2563eb` |
| Secondary action | `bg-gray-700` | `#374151` |
| Success / start | `bg-green-600` | `#16a34a` |
| Destructive | `bg-red-600` | `#dc2626` |
| Body text | `text-white` | — |
| Muted text | `text-gray-400` | `#9ca3af` |

**Transport mode colours** (map polylines + ticket UI):

| Mode | Tailwind class | Hex |
|---|---|---|
| Escooter | `text-amber-500` / `bg-amber-500` | `#f59e0b` |
| Bus | `text-red-500` / `bg-red-500` | `#ef4444` |
| Train | `text-orange-500` / `bg-orange-500` | `#f97316` |
| Ferry | `text-cyan-500` / `bg-cyan-500` | `#06b6d4` |

### 8.2 Typography

System font stack: `-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif`. No custom web font in v1.

### 8.3 Icons

Lucide Vue Next (`lucide-vue-next` package) — the Vue port of Lucide React used in the Figma reference. Key icons: `Users`, `UserPlus`, `Trophy`, `Clipboard`, `Check`, `ArrowLeft`.

### 8.4 Border Radius

`0.625rem` globally (matches shadcn/ui default). Applied via Tailwind `rounded-lg` on cards and buttons.

---

## 9. Frontend Screens and Components

### 9.1 Landing Page (`/`)

Layout: vertically centred, full-viewport-height, `bg-gray-950`.

Structure (top → bottom, centred):

1. **Hero block**
   - Title: "Hunting Mr. X" — white, `text-4xl font-bold`
   - Subtitle: "Wellington Edition" — `text-blue-400`, lighter weight
   - Tagline: "Hunt down Mr. X across Wellington's streets" — `text-gray-400 text-sm`

2. **Button group** (stacked on mobile, side-by-side ≥ sm)
   - **Create Game** — `bg-blue-600`, `Users` icon left, routes to `/create`
   - **Join Game** — `bg-gray-700`, `UserPlus` icon left, routes to `/join`

No header or nav bar in v1.

---

### 9.2 Create Game Page (`/create`)

Two sequential phases on the same route, controlled by local component state.

**Phase 1 — Input** (before `POST /api/games`):
- `ArrowLeft` back link to `/`
- Heading: "Create Game"
- Form card (`bg-gray-900 rounded-lg p-6`):
  - "Your Name" label + text input
  - "Max Players" label + `<select>` (options 2–6)
  - **Create Game** button — `bg-blue-600`, full width; calls `POST /api/games`

**Phase 2 — Lobby** (after game created, waiting for players):
- `ArrowLeft` back link (abandons game, returns to `/`)
- Heading: "Game Lobby"
- **Game code card** (`bg-gray-800 rounded-lg`):
  - Code in large monospace (`font-mono text-2xl tracking-widest`), e.g. `WXYZ12`
  - Copy button with `Clipboard` icon; swaps to `Check` icon for ~2 s on copy
- **Player slots list**:
  - Host row: display name + `Host` badge (`bg-blue-600/20 text-blue-400 text-xs rounded-full`)
  - Remaining slots: "Waiting for player…" (`text-gray-500`, `border-dashed border-gray-700`)
  - Each joined player: display name + `Ready` badge
  - List updated live via WebSocket `GameStateDTO` push
- **Start Game** button — `bg-green-600`, full width; disabled until ≥ 2 players connected; visible to host only; calls `POST /api/games/{id}/start`

---

### 9.3 Join Game Page (`/join`)

- `ArrowLeft` back link to `/`
- Heading: "Join Game"
- Form card (`bg-gray-900 rounded-lg p-6`):
  - "Your Name" label + text input
  - "Game Code" label + text input (`font-mono text-xl uppercase`, maxlength 6)
  - Inline error box — `bg-red-900/20 border border-red-700 text-red-400 text-sm rounded` — shown when code is invalid or game is full
  - **Join Game** button — `bg-blue-600`, full width; calls `POST /api/games/{id}/join`

---

### 9.4 Game Board (`/game/:id`)

**Map panel (MapLibre GL):**
- Wellington base map (CartoDB Dark NoLabels tiles).
- `map.json` loaded once on page load. Edge `geometry.coordinates` arrays used directly as polyline paths — one line layer per transport mode, each with a distinct colour (see §8.1). Nodes rendered as circle markers.
- On a player's turn, their reachable nodes are highlighted (larger radius, bright border). All other nodes are dimmed.
- Clicking a highlighted node opens the **ticket selector**.

**Ticket selector (modal/popover):**
- Shows only tickets the player holds that are valid for at least one mode on the chosen edge.
- Mr X additionally sees DOUBLE (if available) and Invisible (if available).
- Confirming a selection calls `POST /api/games/{id}/moves`.

**Info panel (sidebar):**
- Current round and whose turn it is.
- Each player's name, role icon, and remaining ticket counts.
- Mr X travel log (for detectives: ticket types only; nodeId shown on reveal rounds).

**Mr X double-move UX:**
- After Mr X picks a double move and a ticket for the first leg (sent as `DOUBLE_<ticket>`), the server responds with a state where `mrXDoubleMovePending = true`.
- The UI shows a "Select your second move" banner and re-highlights reachable nodes from Mr X's new position.

**Marker rendering:**
- Detectives: distinct colour per player (up to 5 colours), always visible to all.
- Mr X (Mr X's own view): unique marker, always visible to self.
- Mr X (detective view): marker hidden unless reveal round or game ended.

---

### 9.5 Game Over Page (`/game/:id/end`)

Layout: vertically centred, full-viewport-height, `bg-gray-950`.

Structure (top → bottom, centred):

1. **Trophy icon** — `lucide-vue-next Trophy`, size `w-16 h-16`
   - Mr X wins: `text-red-500`
   - Detectives win: `text-blue-500`

2. **Winner banner** — full-width rounded pill
   - Mr X: "Mr. X Escaped!" on `bg-red-600`
   - Detectives: "Detectives Win!" on `bg-blue-600`

3. **Summary card** (`bg-gray-900 rounded-lg`) — two-column grid:
   - Left: "Game Code" label + code value
   - Right: "Result" label + winner name

4. **Narrative box** (`bg-gray-800 rounded italic text-sm text-gray-300`): one sentence describing how the game ended (e.g. "Mr. X survived all 24 rounds undetected." or "Detective caught Mr. X at round 17.")

5. **Button row** (side-by-side):
   - **Back to Home** — `bg-gray-800`, routes to `/`
   - **Play Again** — `bg-blue-600`, routes to `/create`

---

## 10. Leaving, Idling and Disconnects

- **A dropped WebSocket doesn't end or pause the game.** User testing showed connections drop often, so there is no pause and no grace period. Clients reconnect automatically (STOMP), re-sync over REST on every reconnect, and poll `GET /api/games/{id}` every 6 s as a fallback.
- **Leaving:** any player leaving a game in progress ends it for everyone (`phase: ENDED`, `abortReason` names who left). In the lobby a non-host who leaves is removed; the host leaving closes the lobby for everyone.
- **Idle limit:** every 30 s the server checks each game in progress. If the current player hasn't moved for `game.turn-timer-seconds` (900 s by default), the game ends with `abortReason: "A player exceeded the 15-minute turn limit"`. There is no auto-skip.

---

## 11. API Documentation

The file `documentation/openapi.yaml` is the authoritative OpenAPI 3.1.0 specification for all REST endpoints. It must be kept in sync with the controller implementation at all times.

**Rule for Claude Code**: Whenever a REST endpoint is added, modified, or removed in any Spring controller, update `documentation/openapi.yaml` in the same change. Specifically:

- Add or remove the path entry under `paths:`.
- Add or remove request body schema(s) under `components/schemas/`.
- Add or remove response schema(s) and examples.
- Update enum values if a new `GamePhase`, `TurnPhase`, `Role`, or `TicketType` variant is added.
- Keep the `version:` field in `info:` bumped (patch for new endpoints, minor for breaking changes).

The file can be viewed locally at [Swagger Editor](https://editor.swagger.io/) by pasting the YAML, or with any OpenAPI-compatible viewer.

---

## 12. Non-functional Requirements

| Requirement | Target |
|---|---|
| Map render time (200 nodes / 285 edges) | < 3 s (per MapLibre GL benchmark in `documentation/test_map_api/`) |
| WebSocket state push latency | < 500 ms from move submission to all clients receiving update |
| Concurrent games | Multiple simultaneous games supported; per-game `synchronized` lock in game engine |
| Persistence | None in v1 — in-memory only |
| Browser support | Latest Chrome, Firefox, Safari |
| Mobile | Not a v1 requirement; desktop-first layout |

Map performance note: all road geometry is pre-computed in `map.json`. No routing API calls are made at runtime. This was the key finding from the map API evaluation — live routing APIs are too slow and too costly for this graph size.
