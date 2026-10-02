# Frontend refactor

Date: 2026-09-23. Scope: `frontend/`, the Vue 3 web app players use, plus the docs and diagrams that describe it.

The goal was to make the frontend as easy as possible to understand: fewer packages, no duplicated code, plain-English names and comments that explain *why*. The work went through four rounds, each reviewed before the next, followed by one bug fix.

## Starting point

- **Size:** 29 source files and 3,238 lines.
- **Many small folders:** `api/`, `router/`, `stores/`, `types/` and `utils/` held one to three files each.
- **Pinia:** a state-management library, used for two small stores. One of them only remembered the light/dark theme.
- **Duplicated code:** the lobby and the game board each had their own copy of the live connection: connect, catch-up fetch on reconnect and the 6-second poll. They also both had their own copy of leaving a game. Ticket icons and ticket-chip styles were copied between the map and the side panel, scrollbar styles between two lists, and button, card and form styles across four pages.
- **Tiny wrappers:** `ErrorBanner`, `FormInput` and `MoveSelector` components, a `revealRounds.ts` holding one list, and two names (`leaveGame`, `kickPlayer`) for the same request.
- **Long prop chains:** the game board passed 15 props to the side panel, which passed many of them on to the components inside it.
- **Dead code:** checks for a `'preview'` game id left over from a removed developer page, and map data properties no map layer read.
- **Jargon names:** `store`, `GameStateDTO`, `validMoves`, `resync`, `btn`, `ctx` and one-letter variables.

## Round 1: simplify

### Removed
- **Pinia:** game state is now a plain Vue `reactive()` object (`currentGame`). The theme store became a few lines in `main.ts` and `ThemeToggle`.
- **Wrappers:** `ErrorBanner`, `FormInput` and `MoveSelector` are inlined where they were used. `revealRounds.ts` became three lines in the game board, and the request aliases became one `removePlayer`.
- **Dead code:** the `'preview'` checks, the unread map properties, and a fallback branch that could never run.

### Merged duplicates
- **Live connection:** one `keepUpToDate()` in `api.ts` replaces the two copies. It still re-fetches the whole game on every reconnect and every 6 s, because the user-testing networks dropped connections often.
- **Leaving:** one `leaveGame()` replaces the per-page copies.
- **Requests:** every REST call goes through one `askServer()` helper. It replaced two response handlers and the map's own error path.
- **Styles:** classes used in more than one place (buttons, cards, form inputs, ticket chips, scrolling lists) are defined once in `style.css`.
- **Tickets:** colours, names, icons and display order live in one `tickets.ts`.

### Fewer props
The map, side panel, ticket grid and Mr X log now read game data straight from `currentGame`. The side panel went from 15 props to 7: they now carry only the move being built (node, ticket, double ticket, sending, error).

### Kept
SockJS stays. The backend uses it, and it falls back to plain HTTP on networks that block WebSockets.

## Round 2: folders

`src/` now holds only `App.vue` and folders:

| Folder | Holds |
|---|---|
| `app/` | Startup and wiring: `main.ts`, `router.ts`, `style.css` |
| `shared/` | Used by pages and components: `api.ts`, `current-game.ts`, `types.ts`, `tickets.ts` |
| `pages/` | One file per screen |
| `components/` | Shared pieces, plus `game-board/` and `lobby/` |

## Round 3: plain-English names

Names were changed only where an everyday word is clearer. A before/after list was reviewed first, and standard terms (`main.ts`, `router.ts`, `api.ts`, `types.ts`, `components/`, `ThemeToggle`, `TicketGrid`, `JoinCodeCard`) were kept.

| Before | After |
|---|---|
| `views/`, `LandingView`, `GameEndView`, … | `pages/`, `HomePage`, `GameOverPage`, … |
| `InfoPanel`, `PlayerSlotList` | `SidePanel`, `PlayerList` |
| `store`, `gameState`, `validMoves`, `playerToken` | `currentGame`, `info`, `possibleMoves`, `mySecretKey` |
| `GameStateDTO`, `ValidMoveDTO`, `GraphNode`, `GraphEdge` | `GameInfo`, `PossibleMove`, `MapNode`, `MapConnection` |
| `connectLive`, `resync`, `applyState` | `keepUpToDate`, `refresh`, `onGameUpdate` |
| `modeColor`, `.mode-chip`, `.btn` | `ticketColor`, `.ticket-chip`, `.button` |
| `p`, `n`, `e`, `ctx` | `player`, `node`, `connection`, `pen` |

Field names that come from the server's JSON (such as `playerToken` and `mrXDoubleMovePending`) were left unchanged, because the code has to match what the server sends.

## Round 4: comments

- **Cut:** comments that repeated the code, and long ones (the reconnect explanation went from 9 lines to 6, the scrollbar note from 5 to 2).
- **Added:** comments that answer a real question. For example:
  - two browser tabs can be two players because the ids live in `sessionStorage`, which is per tab
  - the end page shows only "Game Over" after a refresh, because the game info isn't saved
  - the live channels are re-subscribed inside `onConnect`, because a reconnect starts with none
  - a move clears the selection before sending, so a double-click can't send it twice
  - all 16 pie icons are drawn up front, because the map can only show icons it already has by name
- **Dead code found while commenting:** the side panel and map popup removed duplicate tickets from each node's options. The backend's `MapGraph.validMoves` builds those options from a set, so they never repeat, and the de-duplication was removed.

## Bug fix: half-blank map

**Symptom:** the map sometimes filled only the top ~120 px of its area until the window was resized. It happened in a game tab that started in the background.

**Cause:** MapLibre 4.7.1 watches its container's size but ignores the first change it sees. A map created while the page was still settling stayed at that early size.

**Fix:** `GameMap` adds its own size watcher that calls `map.resize()` on every change, including the first. A test that held the map's box at 120 px until the map existed showed a 120 px map in an 847 px box before the fix, and a full 847 px map after it.

## Size

| | Before | After |
|---|---|---|
| Source files | 29 | 23 |
| Source lines | 3,238 | 2,407 |
| Directly in `src/` | 7 folders + 4 files | 4 folders + `App.vue` |
| Runtime packages | 8 (with Pinia) | 7 |
| Side panel props | 15 | 7 |
| Built CSS | ~193 kB | ~155 kB |

## Docs kept in sync

- **Updated:** `frontend/doc.md`, `CLAUDE.md`, `documentation/spec.md` and `final_report.tex`. Each was checked against the code, which also fixed claims that were already wrong in `spec.md`: the map doesn't highlight reachable nodes, nodes are pie icons, and a double move's second leg shows as "Round 1b".
- **Diagrams:** `documentation/plans/frontend-graph.md` was rewritten from the real imports. A script confirmed that every member in the class diagrams exists in the code, and each Mermaid block was rendered to check it. `frontend-graph.drawio` was regenerated from the Mermaid, and its pages were rendered and checked by eye.
- **OpenAPI:** `documentation/openapi.yaml` needed no change: no endpoint, live channel or client reaction changed.

## Verification

1. **Build:** `npm run build` (type-check with `vue-tsc`, then Vite) passed after every round.
2. **Browser test:** a scripted two-player run with Playwright and headless Chromium, repeated after every round:
   - theme persistence across a reload
   - create; join through the shared link; kick; rejoin with a lower-case code
   - start, then the role popups
   - a Mr X double move, picked once from the map popup and once from the side panel
   - the round-2 reveal popup and log row
   - leaving, which ends the game for the other player

   A second run covered the host leaving the lobby and refreshing the board mid-game. Both passed with no page errors; the only console message was the browser's request for a `/favicon.ico` the app doesn't have.
3. **Backend:** `mvn clean test` passed all 134 tests. The backend wasn't changed.
