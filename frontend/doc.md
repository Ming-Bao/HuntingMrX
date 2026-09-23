# Frontend — Setup & Run

Vue 3 · Vite 6 · TypeScript · Tailwind CSS 4 · MapLibre GL 4 · STOMP over SockJS

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

To serve the app under a URL path prefix instead of the domain root, build with `BASE_PATH` set (e.g. `BASE_PATH=/mrx npm run build`) and run the backend with the same variable. Asset paths, page addresses, server requests and the live connection all pick up the prefix: Vite exposes it as `import.meta.env.BASE_URL`, which `src/app/router.ts` and `src/shared/api.ts` build their URLs from.

## Project structure

```
src/
  App.vue          root component: light/dark switch and <RouterView>
  app/             startup and wiring
    main.ts          app entry point (loaded by index.html); applies the saved light/dark theme
    router.ts        which address shows which page (each page loaded only when needed)
    style.css        Tailwind import, dark-mode variant, shared CSS classes
    vite-env.d.ts    Vite's TypeScript types
  shared/          used by pages and components
    api.ts           every request to the server, and keepUpToDate() for the live connection
    current-game.ts  what we know about the current game: a plain reactive() object
                     (gameId, myPlayerId, mySecretKey, info, possibleMoves)
    types.ts         the shape of the information the server sends
    tickets.ts       ticket colours, names and icons
  pages/           HomePage, CreateGamePage, JoinGamePage, LobbyPage, GameBoardPage, GameOverPage
  components/      PageHeader, ThemeToggle
    game-board/    GameMap, SidePanel, MrXLog, TicketGrid
    lobby/         JoinCodeCard, PlayerList
```

The game board's components (`GameMap`, `SidePanel`, `TicketGrid`, `MrXLog`) read game information straight from `currentGame`. `GameBoardPage` passes them only the move being built (selected node, ticket, double ticket). Classes used in more than one component (`.button`, `.card`, `.form-input`, `.ticket-chip` and so on) are defined once in `app/style.css`. Everything else is styled in each component's scoped `<style>`.

## Routes

| Path | Page | Description |
|---|---|---|
| `/` | `HomePage` | Home page: create or join |
| `/create` | `CreateGamePage` | Create game form, then on to the lobby |
| `/join` | `JoinGamePage` | Join a game by code |
| `/:code` | `JoinGamePage` | Shareable join link with the 6-character code filled in |
| `/lobby/:id` | `LobbyPage` | Lobby: join code, link and QR code, player list, kick and start (host) |
| `/game/:id` | `GameBoardPage` | Game board |
| `/game/:id/end` | `GameOverPage` | End screen: winner or abort reason |

The lobby listens on the public live channel (a STOMP topic) and the board on the player's two private ones, both through `keepUpToDate` in `shared/api.ts`, which also asks the server for the whole game again on every reconnect and every 6 s. `documentation/openapi.yaml` describes each channel and how the pages react to it.

## Session persistence

`gameId`, `myPlayerId` and `mySecretKey` are stored in `sessionStorage` so a page refresh does not lose your seat; `shared/current-game.ts` reads them back on load. `mySecretKey` is the player's secret (the server calls it `playerToken`): `shared/api.ts` sends it as the `X-Player-Token` header on every request that acts as the player, and the private live channels are named by it. The light/dark theme choice is kept in `localStorage` (`app/main.ts` applies it, `ThemeToggle` changes it).

## Tests

The frontend has no automated tests; `npm run build` type-checks the code with `vue-tsc`.
