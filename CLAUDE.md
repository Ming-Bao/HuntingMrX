# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ENGR489 capstone project: **Hunting Mr. X: Wellington Edition** — a web-based multiplayer hidden-movement game overlaid on a real-world map of the Wellington region. Players are assigned roles (Mr X or Detectives), move between graph nodes on the Wellington map using transport tickets, and win by catching or evading each other over 24 rounds.

**Naming note**: the game mechanics are based on the board game *Scotland Yard* by Ravensburger. Ravensburger granted permission to use the mechanics for this non-commercial academic project, but the "Scotland Yard" name/brand must not be used in the project (code, docs, UI, or public references) — hence "Hunting Mr. X: Wellington Edition". Do not reintroduce "Scotland Yard" as a name anywhere in this repo; it may still appear in historical/submitted documents under `documentation/project_proposal/`, which are left as-is since they're the record of what was originally submitted.

**Ticket naming note**: the `BLACK` ticket (`TicketType.BLACK` in code, `TicketType` enum in `openapi.yaml`) is shown to players as the **Invisible ticket**. The wire/enum value stays `BLACK` — only the player-facing name changed (see `frontend/src/utils/transportModes.ts` for the existing label mapping). Docs should say "Invisible ticket" in prose and reserve `BLACK` for literal enum/wire-value references.

Implementation is well underway. The backend is a Spring Boot app (`backend/`) with a working game engine — REST + WebSocket controllers, game/lobby/turn models, ticket and move validation, and unit/e2e tests. The frontend is a Vue 3 + Vite + Pinia + Tailwind app (`frontend/`) with lobby and game views, a Pinia store, and a MapLibre GL map wired to the backend's map data over STOMP/WebSocket. `documentation/openapi.yaml` tracks the live REST + WebSocket surface as it evolves (see the sync rule below).

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

- **Backend**: Spring Boot game engine enforcing the game rules — player roles, turn management, movement validation, ticket tracking, win conditions, session management (`GameService`, `MapGraph`, `GameController`, `WebSocketConfig`).
- **Frontend**: Vue 3 map UI (MapLibre GL) allowing players to view available moves, select transport, and track game state, backed by a Pinia store and STOMP over WebSocket.
- **Map layer**: static graph JSON (`map.json`/`test-map.json`), not routing-API-based. API routing was ruled out early — too costly and too slow for the number of edges required. The map graph is served statically and loaded by `MapGraph` at startup.

## Game State Machine

Documented in `documentation/plans/states-diagrams.md`:

- **Game phases**: `Idle → Lobby → InProgress → (Paused | DetectivesWin | MrXWins | GameAborted)`
- **InProgress sub-phases**: `MrXTurn → DetectiveTurn → RoundEnd → MrXTurn` (cycles 24 rounds)
- **Disconnection handling**: `InProgress → Paused` on any disconnect; reconnect within grace period resumes, otherwise `GameAborted`
- **Mr X turn flow**: reveal check (rounds 2, 8, 13, 18, 24) → fetch valid moves → select node + ticket → optional double-ticket second move → server validates → broadcast
- **Detective turn flow**: fetch valid moves → select node + ticket → submit → server catch-check → advance to next detective or increment round

## Key Design Decisions

- **Map library**: Decided — MapLibre GL, wired into the frontend (`frontend/package.json`). Chosen after benchmarking against Google Maps and Leaflet (see `documentation/test_map_api/`).
- **Routing**: GeoJSON pre-computed paths preferred over live routing APIs — APIs are too expensive per-request and too slow for hundreds of node-to-node edges.
- **Turn timers**: Server-side auto-skip on `TurnTimerExpired` so gameplay advances even if a player is idle.

## OpenAPI Spec — MANDATORY SYNC RULE

**`documentation/openapi.yaml` must be updated in the same change as any modification to the API surface — REST or WebSocket.**

### REST changes (backend `@RestController`)
Update whenever you:
- Add, remove, or rename an endpoint
- Change a request body or response shape
- Add a new enum variant to `GamePhase`, `TurnPhase`, `Role`, or `TicketType`

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

- `documentation/openapi.yaml` — OpenAPI 3.1.0 spec (keep in sync with controllers)
- `documentation/plans/` — state diagrams and game flowcharts (Mermaid)
- `documentation/test_map_api/` — map library benchmarks, timing results, per-library notes
- `documentation/project_proposal/` — original proposal (LaTeX source + PDFs)
- `documentation/spec.md` — living spec
- `backend/doc.md` — backend setup/run guide (lives next to the code it documents, not under `documentation/`)
- `frontend/doc.md` — frontend setup/run guide (same reasoning)

## Planned Evaluation Methods

Unit, mock, lifecycle, integration, functional, performance testing of game logic and multiplayer sync; user evaluation via SUS questionnaire. Meta-testing: coverage, mutation, fuzz, and property-based testing.
