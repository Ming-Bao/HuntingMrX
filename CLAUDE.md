# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ENGR489 capstone project: **Hunting Mr. X: Wellington Edition** — a web-based multiplayer hidden-movement game overlaid on a real-world map of the Wellington region. Players are assigned roles (Mr X or Detectives), move between graph nodes on the Wellington map using transport tickets, and win by catching or evading each other over 24 rounds.

**Naming note**: the game mechanics are based on the board game *Scotland Yard* by Ravensburger. Ravensburger granted permission to use the mechanics for this non-commercial academic project, but the "Scotland Yard" name/brand must not be used in the project (code, docs, UI, or public references) — hence "Hunting Mr. X: Wellington Edition". Do not reintroduce "Scotland Yard" as a name anywhere in this repo; it may still appear in historical/submitted documents under `documentation/project_proposal/`, which are left as-is since they're the record of what was originally submitted.

**Ticket naming note**: the `BLACK` ticket (`TicketType.BLACK` in code, `TicketType` enum in `openapi.yaml`) is shown to players as the **Invisible ticket**. The wire/enum value stays `BLACK` — only the player-facing name changed (see `frontend/src/utils/transportModes.ts` for the existing label mapping). Docs should say "Invisible ticket" in prose and reserve `BLACK` for literal enum/wire-value references.

Implementation is well underway. The backend is a Spring Boot app (`backend/`): a plain-Java rules core (`game/`) behind a thin `GameService`, REST controllers, a STOMP broker, and a full test suite (see Evaluation Methods). The frontend is a Vue 3 + Vite + Pinia + Tailwind app (`frontend/`) with lobby and game views, a Pinia store, and a MapLibre GL map wired to the backend's map data over STOMP/WebSocket. `documentation/openapi.yaml` tracks the live REST + WebSocket surface as it evolves (see the sync rule below).

## Running the Map API Benchmark

The timing benchmark compares Google Maps, Leaflet, and MapLibre GL render performance:

```bash
cd documentation/test_map_api
python3 time_maps.py
```

Requires Firefox (snap path `/snap/firefox/current/usr/lib/firefox/firefox`), geckodriver, and `selenium`. Results are written to `timing_results.json`.

To include Google Maps: create `documentation/test_map_api/googlemaps/env` containing:
```
GOOGLE_MAPS_API_KEY=your_key_here
```

## Architecture

**Client–server, real-time WebSocket communication.**

- **Backend**: Spring Boot game engine enforcing the game rules — player roles, turn management, movement validation, ticket tracking, win conditions, session management (`Game`, `GameService`, `MapGraph`, `GameController`, `WebSocketConfig`).
- **Frontend**: Vue 3 map UI (MapLibre GL) allowing players to view available moves, select transport, and track game state, backed by a Pinia store and STOMP over WebSocket.
- **Map layer**: static graph JSON (`map.json`/`test-map.json`), not routing-API-based. API routing was ruled out early — too costly and too slow for the number of edges required. The map file is loaded into `MapGraph` once at startup and served to the frontend at `GET /api/map`.

## Game State Machine

Documented in `documentation/plans/states-diagrams.md`:

- **Game phases**: `Idle → Lobby → InProgress → (DetectivesWin | MrXWins | GameAborted)`; a lobby also closes (`ENDED`) when its host leaves
- **InProgress sub-phases**: `MrXTurn → DetectiveTurn → RoundEnd → MrXTurn` (cycles 24 rounds)
- **Leaving and idling**: any player leaving a game in progress aborts it for everyone, and the server aborts a game whose current player hasn't moved for `game.turn-timer-seconds` (900 s). A dropped WebSocket is *not* leaving: clients reconnect and re-sync over REST, and nothing pauses.
- **Mr X turn flow**: boxed-in check (no legal move means the detectives win) → fetch valid moves → select node + ticket → optional double (first leg sent as `DOUBLE_<ticket>`) → server validates → broadcast; on rounds 2, 8, 13, 18, 24 the node he ends on is revealed
- **Detective turn flow**: fetch valid moves → select node + ticket → submit → server catch-check → advance to the next detective (skipping any with no legal move) or increment round

## Key Design Decisions

- **Map library**: Decided — MapLibre GL, wired into the frontend (`frontend/package.json`). Chosen after benchmarking against Google Maps and Leaflet (see `documentation/test_map_api/`).
- **Routing**: GeoJSON pre-computed paths preferred over live routing APIs — APIs are too expensive per-request and too slow for hundreds of node-to-node edges.
- **Turn timers**: no auto-skip. The server aborts a game whose current player has been idle for `game.turn-timer-seconds` (900 s). Pause-on-disconnect with a grace period was dropped because user-testing networks dropped WebSockets too often.
- **Player tokens**: create/join return a public `playerId` and a secret `playerToken`. Every call that acts as a player sends the token in `X-Player-Token`, and private STOMP topics are named by it; the public id grants nothing. The STOMP broker matches exact destinations only and drops client SEND frames, so nobody can listen in on another player's topic.
- **Rules core**: every game rule lives in plain Java in `backend/src/main/java/com/huntingmrxwellington/game/` (`Game`, `Player`, `MapGraph`) with no Spring, so it's unit-tested directly. `GameService` only stores games, locks, checks tokens and publishes.

## OpenAPI Spec — MANDATORY SYNC RULE

**`documentation/openapi.yaml` must be updated in the same change as any modification to the API surface — REST or WebSocket.**

### REST changes (backend `@RestController`)
Update whenever you:
- Add, remove, or rename an endpoint
- Change a request body or response shape
- Add a new enum variant to `GamePhase`, `TurnPhase`, `Role`, `Winner`, or `TicketType`

→ Update the matching `paths:` entry and `components/schemas:` section.

### WebSocket changes (backend broadcasts or frontend subscriptions)
Update whenever you:
- Add a new STOMP topic the server publishes to (backend `messaging.convertAndSend(...)`)
- Add a new STOMP subscription in any frontend view or composable (`.subscribe(...)`)
- Change the payload schema of an existing topic
- Add new client-side reactions to an existing topic (e.g. a new `phase` value triggers a new navigation)
- Remove a topic or subscription

→ Update the matching `webhooks:` entry in `openapi.yaml`. Each STOMP topic has one `webhooks` entry. Document: the topic path, what triggers a publish, and what the client is expected to do on receipt.

### Every change
Bump the patch version in `info.version` (e.g. `0.1.2` → `0.1.3`).

Do **not** skip this step even for small changes. The OpenAPI file is the single source of truth for the full API surface — REST and real-time.

## Documentation Layout

- `documentation/openapi.yaml` — OpenAPI 3.1.0 spec for REST and the STOMP topics (keep in sync with the code)
- `documentation/plans/` — state diagrams, game flowcharts, and package and class graphs for the backend (`class-graph.md`) and frontend (`frontend-graph.md`), all Mermaid; the detailed diagrams are also in draw.io (`class-graph.drawio`, `frontend-graph.drawio`), and `classdiagram.md` is the backend class diagram in PlantUML
- `documentation/test_map_api/` — map library benchmarks, timing results, per-library notes
- `documentation/project_proposal/` — original proposal (LaTeX source + PDFs)
- `documentation/spec.md` — living spec
- `documentation/backend-fixes.md`: bugs found in the 2026-09-21 backend review, with cause, fix, regression test and status
- `documentation/final_report/`: final report outline (LaTeX, IEEEtran) and its built PDF
- `documentation/presentation/`: the Trimester 1 progress presentation (5 June 2026), as presented
- `documentation/user-testing/`: raw feedback from the two user-testing rounds
- `docs/superpowers/`: design spec and implementation plan for the 2026-09-21 backend rewrite
- `backend/doc.md` — backend setup/run guide (lives next to the code it documents, not under `documentation/`)
- `frontend/doc.md` — frontend setup/run guide (same reasoning)

## Evaluation Methods

Unit, mock, lifecycle, integration, functional, property-based/fuzz and performance tests live in `backend/src/test`; the table in `backend/doc.md` maps each category to its classes. Coverage (JaCoCo) runs with every `mvn test`, mutation testing (PIT) on demand, and the latency test is opt-in. User evaluation used the SUS questionnaire. The frontend has no automated tests.
