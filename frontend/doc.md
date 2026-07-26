# Frontend — Setup & Run

Vue 3 · Vite 6 · TypeScript · Tailwind CSS 4 · Pinia

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

The app starts on `http://localhost:5173`. API requests to `/api/*` are proxied to `http://localhost:8999` automatically — no CORS configuration needed in the browser.

## Build (production)

```bash
npm run build
```

Output is written to `dist/`. Serve it with any static file server, e.g.:

```bash
npm run preview   # Vite's built-in preview server
```

## Project structure

```
src/
  api/          gameApi.ts — fetch wrappers for all REST calls
  router/       index.ts   — Vue Router routes
  stores/       gameStore.ts — Pinia store (gameId, playerId, gameState)
  types/        game.ts    — TypeScript interfaces matching backend DTOs
  views/        LandingView, CreateGameView, JoinGameView, GameBoardView
  main.ts       app entry point
  style.css     Tailwind CSS import
```

## Routes

| Path | View | Description |
|---|---|---|
| `/` | `LandingView` | Landing page — create or join |
| `/create` | `CreateGameView` | Create game form → lobby (polls every 2 s) |
| `/join` | `JoinGameView` | Join game by code |
| `/game/:id` | `GameBoardView` | Game board (stub — Sprint 2) |

## Session persistence

`gameId` and `playerId` are stored in `sessionStorage` so a page refresh does not lose context. The Pinia store re-hydrates from `sessionStorage` on load.
