# Frontend — Setup & Run

Vue 3 · Vite 6 · TypeScript · Tailwind CSS 4 · Pinia · MapLibre GL 4 · STOMP over SockJS

## Prerequisites

- **Node.js 18+** — verify with `node -v`
- **npm 9+** — verify with `npm -v`
- **Backend running** on `http://localhost:8999` (see `backend/doc.md`)

## Install dependencies

```bash
cd frontend
npm install
```

## Run (development)

```bash
npm run dev
```

The app starts on `http://localhost:5173`. Requests to `/api/*`, the `/ws` WebSocket and `/test-map.json` are proxied to `http://localhost:8999` automatically, so no CORS configuration is needed in the browser.

## Build (production)

```bash
npm run build
```

Output is written to `dist/`. Serve it with any static file server, e.g.:

```bash
npm run preview   # Vite's built-in preview server
```

To serve the app under a URL path prefix instead of the domain root, build with `BASE_PATH` set (e.g. `BASE_PATH=/mrx npm run build`) and run the backend with the same variable. Asset paths, routes, REST calls and the WebSocket all pick up the prefix (`src/utils/basePath.ts`).

## Project structure

```
src/
  api/          gameApi.ts: fetch wrappers for every REST call
  components/   game/ (GameMap, InfoPanel, MoveSelector, MrXLog, TicketGrid),
                lobby/ (JoinCodeCard, PlayerSlotList), ui/ (ErrorBanner, FormInput, PageHeader, ThemeToggle)
  router/       index.ts: Vue Router routes
  stores/       gameStore.ts: Pinia store (gameId, playerId, playerToken, gameState, validMoves)
                themeStore.ts: light/dark theme
  types/        game.ts: TypeScript types matching the backend's JSON
  utils/        basePath.ts (URL prefix), revealRounds.ts, transportModes.ts (mode colours and labels)
  views/        LandingView, CreateGameView, JoinGameView, LobbyView, GameBoardView, GameEndView
  main.ts       app entry point
  style.css     Tailwind CSS import
```

## Routes

| Path | View | Description |
|---|---|---|
| `/` | `LandingView` | Landing page: create or join |
| `/create` | `CreateGameView` | Create game form, then on to the lobby |
| `/join` | `JoinGameView` | Join a game by code |
| `/:code` | `JoinGameView` | Shareable join link with the 6-character code filled in |
| `/lobby/:id` | `LobbyView` | Lobby: join code, link and QR code, player list, kick and start (host) |
| `/game/:id` | `GameBoardView` | Game board |
| `/game/:id/end` | `GameEndView` | End screen: winner or abort reason |

The lobby listens on the public STOMP topic and the board on the player's private topics; both also re-sync over REST on every reconnect and poll every 6 s. `documentation/openapi.yaml` describes each topic and how the views react to it.

## Session persistence

`gameId`, `playerId` and `playerToken` are stored in `sessionStorage` so a page refresh does not lose context. The Pinia store re-hydrates from `sessionStorage` on load. `playerToken` is the player's secret: `gameApi.ts` sends it as the `X-Player-Token` header on every call that acts as the player, and the private STOMP topics are named by it. The light/dark theme choice is kept in `localStorage`.

## Tests

The frontend has no automated tests; `npm run build` type-checks the code with `vue-tsc`.
