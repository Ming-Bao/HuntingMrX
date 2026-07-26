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
| Containerisation | Docker (frontend + backend as separate containers) |

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
DETECTIVE_ESCOOTER_TICKETS = TBD   // placeholder, e.g. 10
DETECTIVE_BUS_TICKETS      = TBD   // placeholder, e.g. 8
DETECTIVE_TRAIN_TICKETS    = TBD   // placeholder, e.g. 4
DETECTIVE_FERRY_TICKETS    = TBD   // placeholder, e.g. 2
```

These are global constants defined in `application.properties`. Detectives do not share tickets.

### 2.4 Turn Structure

The game runs for rounds 1–24. Within each round:

1. **Mr X's turn** — Mr X makes one move (or two if using a DOUBLE ticket)
2. **Detective turns** — each detective moves in the order they joined the game; a detective with no valid moves is automatically skipped

The round counter increments after all detectives have moved (or been skipped).

### 2.5 Reveal Rounds

At the **start** of Mr X's turn on rounds **3, 8, 13, 18, 24**, Mr X's current node is broadcast to all detectives. The node appears in `mrXLog` for that round and in the detective-view `PlayerDTO`.

### 2.6 Double Move

Mr X declares a double move by including `DOUBLE` as the ticket in their first move submission. This consumes one `DOUBLE` ticket and places Mr X in a "double move" sub-state:

1. First move: Mr X submits `{toNodeId, ticket: DOUBLE}`. Server deducts one DOUBLE ticket and prompts for the second move.
2. Second move: Mr X submits `{toNodeId, ticket: <ESCOOTER|BUS|TRAIN|FERRY|BLACK>}` for the second leg.

Both moves occur before any detective moves. Detectives see two `MrXLogEntry` rows for that round — each showing the ticket type used (or `BLACK`). Neither log entry reveals Mr X's position unless the round is a reveal round, in which case the final destination after the second move is revealed.

The `isDoubleFirst` flag on `MrXLogEntry` distinguishes the first leg from the second.

### 2.7 Black Ticket

Mr X submits `{toNodeId, ticket: BLACK}`. The move is valid on **any edge regardless of mode** — Mr X does not need a matching transport ticket. One `BLACK` ticket is consumed.

Detectives see `BLACK` in the log. The actual destination is hidden on non-reveal rounds. On reveal rounds the destination is still revealed, but the ticket type still shows as `BLACK`.

### 2.8 Win Conditions

| Outcome | Condition |
|---|---|
| Detectives win | Any detective's move ends on Mr X's node (caught) |
| Mr X wins | Round 24 completes with Mr X not caught |
| Game aborted | A player disconnects and the grace period expires without reconnection |

### 2.9 Movement Constraints

- Mr X **cannot** move to a node currently occupied by any detective.
- Multiple detectives **may** occupy the same node simultaneously.
- A player must hold at least one ticket matching a mode on the chosen edge (or use a BLACK ticket as Mr X).
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
- Spring Boot serves the compiled Vue frontend from `src/main/resources/static/`.

---

## 5. Data Models

### 5.1 Server-side Java

```
GameSession
  String id                          // UUID
  String joinCode                    // 6-char uppercase, e.g. "WXYZ12"
  GamePhase phase                    // LOBBY | IN_PROGRESS | PAUSED | ENDED
  int round                          // 1–24
  TurnPhase turnPhase                // MR_X_TURN | DETECTIVE_TURN
  boolean mrXDoubleMovePending       // true between first and second double-move leg
  List<Player> players               // in join order
  int currentDetectiveIndex          // index into players (detectives only) for whose detective turn it is
  int mrXNodeId                      // authoritative position, never sent to detectives
  List<MrXLogEntry> mrXLog
  Instant pausedAt                   // set on disconnect
  String disconnectedPlayerId        // player currently in grace period
  String winner                      // null | "MR_X" | "DETECTIVES" | "ABORTED"

Player
  String id                          // UUID, assigned on join
  String name
  Role role                          // MR_X | DETECTIVE
  int nodeId
  Map<TicketType, Integer> tickets   // -1 = unlimited

MrXLogEntry
  int round
  int leg                            // 1 (normal) | 1 or 2 (double move)
  TicketType ticketUsed              // ESCOOTER | BUS | TRAIN | FERRY | BLACK
  Integer nodeId                     // null unless reveal round AND this is the final leg

TicketType   (enum)  ESCOOTER | BUS | TRAIN | FERRY | BLACK | DOUBLE
GamePhase    (enum)  LOBBY | IN_PROGRESS | PAUSED | ENDED
TurnPhase    (enum)  MR_X_TURN | DETECTIVE_TURN
Role         (enum)  MR_X | DETECTIVE
```

### 5.2 Client-facing DTOs (JSON)

The server sends **role-filtered snapshots** — Mr X's position is withheld from detectives on non-reveal rounds.

```
GameStateDTO
  gameId          String
  joinCode        String
  phase           String           // "LOBBY" | "IN_PROGRESS" | "PAUSED" | "ENDED"
  round           int
  turnPhase       String           // "MR_X_TURN" | "DETECTIVE_TURN"
  currentPlayerId String           // playerId whose turn it is
  players         PlayerDTO[]
  mrXLog          MrXLogEntryDTO[]
  winner          String | null    // "MR_X" | "DETECTIVES" | "ABORTED" | null

PlayerDTO
  id       String
  name     String
  role     String                  // "MR_X" | "DETECTIVE"
  nodeId   number | null           // Mr X: null in detective view on non-reveal rounds
  tickets  { [ticketType]: number } // Mr X unlimited tickets shown as -1

MrXLogEntryDTO
  round       int
  leg         int
  ticketUsed  String               // "ESCOOTER" | "BUS" | "TRAIN" | "FERRY" | "BLACK"
  nodeId      number | null        // null on non-reveal rounds

MoveRequestDTO  (client → server, REST body)
  playerId   String
  toNodeId   number
  ticket     String               // ticket type used

ValidMovesDTO  (server → client)
  moves  Array<{ nodeId: number, modes: String[] }>
```

---

## 6. REST API

Base path: `/api`

All error responses: `400 Bad Request` with body `{ "error": "<message>" }`.

| Method | Path | Request body | Response | Description |
|---|---|---|---|---|
| `POST` | `/api/games` | `{ "hostName": String }` | `GameStateDTO` | Create a new game; host is assigned `MR_X` role by default (reassigned on start) |
| `POST` | `/api/games/{id}/join` | `{ "joinCode": String, "playerName": String }` | `{ "playerId": String, "gameState": GameStateDTO }` | Join an existing LOBBY game |
| `POST` | `/api/games/{id}/start` | `{ "playerId": String }` | `GameStateDTO` | Start the game (host only, ≥2 players required); server assigns roles and starting nodes |
| `GET` | `/api/games/{id}/valid-moves?playerId={pid}` | — | `ValidMovesDTO` | Returns reachable nodes for the given player on their turn |
| `POST` | `/api/games/{id}/moves` | `MoveRequestDTO` | `GameStateDTO` | Submit a move; server validates, applies, and broadcasts updated state via WebSocket |

`POST /api/games/{id}/start` randomly assigns one player as Mr X and the rest as detectives. The host does not have a guaranteed role.

---

## 7. WebSocket Protocol (STOMP over SockJS)

### 7.1 Connection

Endpoint: `/ws` (SockJS). Clients connect using the `@stomp/stompjs` + `sockjs-client` libraries.

### 7.2 Server → Client Subscriptions

Because Mr X's position must be hidden from detectives, **each player subscribes to their own channel** rather than a shared game topic:

| Destination | Payload | Sent when |
|---|---|---|
| `/topic/games/{gameId}/players/{playerId}` | `GameStateDTO` (role-filtered) | After every state change: join, start, move, disconnect, reconnect, end |
| `/topic/games/{gameId}/players/{playerId}/valid-moves` | `ValidMovesDTO` | At the start of this player's turn |
| `/topic/games/{gameId}/players/{playerId}/error` | `{ "message": String }` | Move rejected or other per-player error |

The server uses `SimpMessagingTemplate.convertAndSendToUser()` targeting each player's session ID.

### 7.3 Client → Server

All game actions go through **REST** (`POST /api/games/{id}/moves`). WebSocket is receive-only for clients. This simplifies auth and error handling.

### 7.4 Role Filtering Rules

When building the `GameStateDTO` for a given player:

- **Detective view of Mr X's `PlayerDTO.nodeId`**: `null` unless the current round is a reveal round **and** Mr X's turn for this round has already completed.
- **Detective view of `mrXLog[i].nodeId`**: `null` unless `mrXLog[i]` is from a reveal round.
- **Mr X view**: full state, including own `nodeId` and all detective `nodeId`s.

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
- Mr X additionally sees DOUBLE (if available) and BLACK (if available).
- Confirming a selection calls `POST /api/games/{id}/moves`.

**Info panel (sidebar):**
- Current round and whose turn it is.
- Each player's name, role icon, and remaining ticket counts.
- Mr X travel log (for detectives: ticket types only; nodeId shown on reveal rounds).
- Pause/disconnection banner when `phase === "PAUSED"`.

**Mr X double-move UX:**
- After selecting `DOUBLE` as the ticket type for the first leg, the server responds with a `GameStateDTO` where `mrXDoubleMovePending = true`.
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

## 10. Disconnection Handling

Based on `documentation/plans/states-diagrams.md`:

1. Server detects WebSocket session close for `playerId`.
2. Game transitions to `phase: PAUSED`. `pausedAt` and `disconnectedPlayerId` are set.
3. A grace period timer starts (configurable: `game.grace-period-seconds`, default 60).
4. Server broadcasts `GameStateDTO` with `phase: "PAUSED"` to all remaining players.
5. **If the player reconnects** within the grace period: they re-subscribe to their per-player topic and receive the current `GameStateDTO`. Game resumes from where it stopped — the turn timer is reset.
6. **If the grace period expires**: game transitions to `phase: ENDED`, `winner: "ABORTED"`. Final state broadcast to all remaining players.

Turn timer: a per-turn server-side timer (configurable: `game.turn-timer-seconds`, default 120). On expiry the server auto-skips the current player's move and advances the turn. The skipped move is logged as `{ticket: null}` in `mrXLog` (Mr X) or simply skipped (detective).

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
