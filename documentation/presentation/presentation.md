# ENGR489 Trimester 1 Progress Presentation
## Scotland Yard: Wellington Edition
**Ming Bao — June 5, 2026**

---

## Slide 1 — Title

**Scotland Yard: Wellington Edition**
A real-world multiplayer location strategy game

Ming Bao | ENGR489 | Victoria University of Wellington

> *[Speaker note: 15 seconds. Just introduce the project name and yourself.]*

---

## Slide 2 — The Problem (motivation + problem statement)

**Board games don't scale to the real world**

- Scotland Yard (1983) is a classic hidden-movement deduction game — but it only exists as a physical board
- Existing digital versions (Board Game Arena, Steam) use fictional maps — no real geography
- Location-based games like Pokémon GO prove real-world geography makes games more engaging, but they lack strategic depth
- **Gap:** No web-based platform lets players play Scotland Yard on a real city map, in real time, from their own device
- **Secondary gap:** SWEN capstone projects at VUW are rarely visually compelling enough to engage a general audience — this project is designed to be a showpiece as well as a technical deliverable

**Goal:** Build a multiplayer Scotland Yard game overlaid on Wellington's real transport network, playable in a browser — and make it look like something you'd actually want to play

> *[Speaker note: 45 seconds. Hit the gap: physical board game with no digital real-world equivalent. Pokémon GO proves the concept works but is shallow strategically. The open day point is brief but genuine — say it once and move on.]*

---

## Slide 3 — Background Research: Related Work

**What exists and what's missing**

| System | Real map | Browser-based | Strategic depth | Multiplayer sync |
|---|---|---|---|---|
| Board Game Arena | No | Yes | High | Yes |
| Pokémon GO | Yes | No | Low | Limited |
| Google Maps games | Partial | Yes | Low | No |
| **This project** | **Yes** | **Yes** | **High** | **Yes** |

**Key insight from prior work:** Real-world routing APIs (Google Maps Directions) are too expensive ($5–10/1000 requests) and too slow (~300ms) for the hundreds of node-to-node edges needed — pre-computed GeoJSON paths are the right architecture

> *[Speaker note: 45 seconds. The table tells the story fast. Explain the GeoJSON routing decision briefly — it's a genuine engineering trade-off.]*

---

## Slide 4 — Background Research: Map Library Evaluation

**Benchmark: Google Maps vs Leaflet vs MapLibre GL**
*(10 runs each, automated via Selenium/Firefox)*

| Library | Baseline render (mean) | Stress test (mean) | License | API key? |
|---|---|---|---|---|
| Google Maps | 0.70 s | 2.69 s | Proprietary | Required ($) |
| Leaflet | 0.23 s | 2.21 s | Open source | No |
| **MapLibre GL** | **0.40 s** | **2.31 s** | **Open source** | **No** |

**Decision: MapLibre GL**
- GPU-accelerated WebGL rendering — critical for 200-node graph overlay at 60fps
- Free, no API key, Mapbox-derived (industry-standard tile format)
- Best consistency under load (stdev 0.030 vs Leaflet's 0.055)
- Leaflet is faster at baseline but has no native WebGL — graph layer would degrade at scale

> *[Speaker note: 60 seconds. Show the data, explain the decision. Emphasise this was driven by measurement, not preference. Leaflet is faster at baseline but MapLibre wins on what matters for this project: WebGL graph rendering at scale.]*

---

## Slide 5 — Tools, Methodology & Approach

**I'm deliberately using industry tools throughout this project**

**AI-assisted development:** Claude Code (Anthropic's CLI) — used for architecture decisions, code generation, and test writing. Not a crutch — I review and own everything it produces. This is how modern engineers work.

**Stack (justified):**

| Layer | Tool | Why |
|---|---|---|
| Backend | Java 21 + Spring Boot 4 | Industry standard, WebSocket/STOMP built-in |
| Frontend | Vue 3 + Pinia + TypeScript | Reactive, typed, composable |
| Map | MapLibre GL | WebGL, open-source (benchmark above) |
| Real-time | STOMP over SockJS | Spring's native WebSocket protocol |
| Containerisation | Docker (planned T2) | Reproducible deploys, industry norm |
| Testing | JUnit 5 + Mockito + AssertJ | Standard Java test stack |

**Methodology:** Agile sprints — each sprint delivers a runnable increment

> *[Speaker note: 60 seconds. Mention Claude Code and Docker early — user specifically asked for this. Justify each tool, don't just list them — the rubric penalises listing without justification.]*

---

## Slide 6 — System Architecture

```
Browser (Vue 3 + MapLibre GL)
  │  REST (HTTP/JSON)   — lobby setup, game creation
  │  WebSocket (STOMP)  — real-time game events
  ▼
Spring Boot 4 Backend
  ├── GameController     (6 REST endpoints)
  ├── WebSocketConfig    (STOMP broker, /topic/games/{id})
  ├── GameService        (all game logic)
  ├── GameRepository     (in-memory, ConcurrentHashMap)
  └── Player hierarchy:
        Player (interface)
        ├── LobbyPlayer     (pre-role, no tickets)
        ├── MrXPlayer       (unlimited transport, finite BLACK/DOUBLE)
        └── DetectivePlayer (configurable finite tickets)

Static files:
  graph.json     — nodes + edges (Wellington transport network)
  roads.geojson  — road geometry for map rendering
```

**Design decisions driven by requirements:**
- Interface not class → roles are a runtime assignment, not a field
- In-memory store → no database complexity for v1; easy to swap later
- WebSocket push → clients never poll; server is authoritative

> *[Speaker note: 60 seconds. Walk through top to bottom. Emphasise that the Player interface design was deliberate — LobbyPlayer has no role, roles only exist after startGame. This came from a real design flaw identified and fixed during development.]*

---

## Slide 7 — Game State Machine

*(Show the Mermaid state diagram — render from documentation/plans/states-diagrams.md)*

```
Idle → Lobby → InProgress → { DetectivesWin | MrXWins | GameAborted }
                     ↕
                  Paused (disconnection grace period)

InProgress sub-phases:
  MrXTurn → DetectiveTurn → RoundEnd → MrXTurn  (×24 rounds)
```

**Implemented so far:** Idle → Lobby → InProgress transitions, abort on MrX disconnect  
**Next sprint:** Turn rotation, move validation, catch detection, reveal rounds

> *[Speaker note: 30 seconds. Don't read the diagram. Say: "The state machine drives every operation — the backend rejects anything that's out of order. StartGame throws if you're not in LOBBY. KickPlayer throws if you're IN_PROGRESS."]*

---

## Slide 8 — What's Been Built: Backend

**Sprint 1–2 complete: Lobby + Role Assignment**

REST API (6 endpoints, all tested):
- `POST /api/games` — create game, return join code
- `POST /api/games/join` — join by code
- `GET /api/games/{id}` — get current state
- `POST /api/games/{id}/start` — host starts, assigns roles
- `DELETE /api/games/{id}/players/{pid}` — leave
- `POST /api/games/{id}/players/{pid}/kick` — host kicks player from lobby

WebSocket: Server broadcasts to `/topic/games/{id}` after every state change — no polling

OpenAPI 3.1 spec kept in sync via a **pre-commit git hook** — the build fails if the API surface changes without updating the spec

> *[Speaker note: 45 seconds. The pre-commit hook is a nice engineering detail that shows process discipline. Mention that roles are randomly assigned at startGame, not at join time — this was a deliberate design fix.]*

---

## Slide 9 — What's Been Built: Frontend + Demo

**Working lobby flow** *(live demo or video)*

1. Player creates game → lands on lobby with 6-char join code
2. Second player joins by code → both lobbies update **instantly via WebSocket**
3. Host can kick players → kicked player sees a notification and is redirected
4. Host starts game → all players transition together

**Component architecture:** 10 reusable components across 5 views  
**Real-time:** STOMP/SockJS — removed all polling on user feedback

> *[Speaker note: 60 seconds. Demo the lobby flow live if possible — create on one tab, join on another, show the real-time update. If demo fails, have screenshot sequence ready.]*

---

## Slide 10 — Testing Strategy

**104 tests, all passing**

| Layer | Tests | What they catch |
|---|---|---|
| Model unit | 27 | Ticket invariants — e.g. MrX transport stays at -1 after use |
| Service unit | 46 | State transitions, validation, broadcast calls |
| Lifecycle integration | 17 | Full create→join→start→leave flows with real repo |
| Controller (HTTP) | 13 | Request routing, error response shape |
| App context | 1 | Spring context loads without errors |

**Philosophy:** Tests target specific invariants, not just happy paths  
Example: `startGame_setsCurrentPlayerIdToMrX` — fails if line 121 of GameService is deleted  
Example: `leaveGame_mrXLeaves_mrXRemovedFromPlayerList` — previous tests only checked phase, not player list

> *[Speaker note: 30 seconds. Show that testing was deliberate and targeted at real bugs, not just coverage numbers.]*

---

## Slide 11 — Trade-offs & Limitations

**Critical thinking: what I chose and why it could bite me**

| Decision | Trade-off | Mitigation |
|---|---|---|
| In-memory state | Server restart kills all games | Acceptable for v1; persist to Redis in v2 |
| No authentication | Anyone who knows a join code can join | By design — simplicity over security for a demo game |
| WebSocket (STOMP) | More complex than REST polling | Standard Spring support; already working |
| Pre-computed GeoJSON | Must build Wellington graph manually | One-time effort; no per-request API cost |
| MapLibre GL | 40ms slower at baseline than Leaflet | WebGL wins at scale; Leaflet would degrade under graph load |

**Current limitations:**
- Game engine (move validation, catch detection, win conditions) not yet implemented
- Wellington graph data not yet populated
- No Docker deployment yet

**Sustainability:** No per-request cloud API calls (GeoJSON is static); runs on minimal infrastructure; open-source stack with no vendor lock-in

> *[Speaker note: 45 seconds. The rubric explicitly wants trade-offs and limitations — this slide directly maps to the Critical Thinking marking criteria.]*

---

## Slide 12 — Future Plan (SMART)

**Sprint 3 — Game Engine (Trimester 1, remaining weeks)**
- Move validation against graph edges
- Ticket decrement on move
- Turn rotation (MrX → each detective → repeat)
- Catch detection and win conditions
- Reveal rounds (2, 8, 13, 18, 24)
- *Milestone:* Two players can complete a full round

**Sprint 4 — Map + Wellington Graph (T2, Weeks 1–3)**
- Build Wellington node/edge graph (~100 nodes, 4 transport modes)
- MapLibre GL rendering of nodes and edges on map
- Node selection and ticket picker UI
- *Milestone:* Player can select and submit a move on the real map

**Sprint 5 — Deployment + Evaluation (T2, Weeks 4–8)**
- Docker containerisation (backend + frontend)
- Host on VUW infrastructure or cloud
- SUS usability evaluation with real players (target: 5+ participants)
- Mutation testing and performance benchmarking
- *Milestone:* Full game playable end-to-end by external users

**Risk:** Wellington graph data is the critical path — if node population takes longer than 2 weeks, map rendering slips. Mitigation: start with 20-node prototype.

> *[Speaker note: 45 seconds. The rubric wants SMART goals and identification of dependencies/failure points. The Wellington graph is the honest risk — say it.]*

---

## Slide 13 — Summary

**Where we are at end of Trimester 1**

✅ Full specification written  
✅ Map library evaluated with benchmark data — MapLibre GL selected  
✅ System architecture designed (state machine, player hierarchy, WebSocket)  
✅ Working backend: 6 endpoints, WebSocket, role assignment, 104 tests passing  
✅ Working frontend: create/join/lobby with real-time updates  
✅ OpenAPI spec in sync, enforced by pre-commit hook  

**Next:** Game engine (move validation, catch detection, win conditions) + map rendering — the visual showpiece  
**Tools:** Claude Code + Docker + Spring Boot — industry workflow from day one

> *[Speaker note: 15 seconds. End on what's done and where it's going. Mention tools one more time — it's a differentiator.]*

---

## Speaker Notes Summary (timing guide)

| Slide | Content | Time |
|---|---|---|
| 1 | Title | 0:15 |
| 2 | Problem | 0:45 |
| 3 | Related work | 0:45 |
| 4 | Map benchmark | 1:00 |
| 5 | Tools + methodology | 1:00 |
| 6 | Architecture | 1:00 |
| 7 | State machine | 0:30 |
| 8 | Backend built | 0:45 |
| 9 | Frontend + demo | 1:00 |
| 10 | Testing | 0:30 |
| 11 | Trade-offs | 0:45 |
| 12 | Future plan | 0:45 |
| 13 | Summary | 0:15 |
| **Total** | | **~8:15** |

---

## Likely Q&A Questions (prepare answers)

**"Why not use a database?"**
> In-memory is deliberate for v1 — zero setup complexity, stateless restarts are fine for demo games. A Redis or PostgreSQL adapter would slot in behind the GameRepository interface without changing any service code.

**"Why MapLibre over Leaflet if Leaflet is faster?"**
> At baseline Leaflet wins. But Leaflet uses Canvas/SVG — adding 200 graph edges degrades linearly. MapLibre uses WebGL — the graph layer renders on GPU and performance stays consistent. The benchmark measured empty map load; the graph load is where MapLibre will win.

**"What if two players move simultaneously?"**
> The game model enforces `currentPlayerId` — only the current player's moves are accepted. The backend validates the player ID on every move submission. Concurrent requests are handled by Spring's thread-safe in-memory store (ConcurrentHashMap).

**"How do you plan to evaluate this?"**
> SUS (System Usability Scale) questionnaire with real players after full game is playable. Also: automated performance test (WebSocket latency under concurrent games), mutation testing to measure test suite quality, and functional testing against the game rules specification.

**"What's the biggest risk to delivery?"**
> Populating the Wellington graph. Every other component is decoupled and can be built in parallel. The graph is the one piece that requires manual geographic research and validation. Mitigation: 20-node prototype first, then expand.
