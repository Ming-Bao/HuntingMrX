# Hunting Mr. X: Wellington Edition

A browser-based, real-time hidden-movement game played over an actual graph of Wellington's transport network — not a fictional board, the real bus routes, train lines, ferry crossing, and e-scooter zones of the city, as 261 nodes and 436 edges one player can vanish into and four others have to search.

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)
![MapLibre GL](https://img.shields.io/badge/MapLibre%20GL-4-396CB2?logo=maplibre&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--3.0-blue)

One player is **Mr X**. Everyone else is a **Detective**. Mr X's position is hidden from the moment the round starts, surfacing only on five scheduled reveal rounds — everyone else has to reconstruct where he's gone from nothing but the transport ticket he was forced to spend to get there. Detectives see each other and coordinate in the open; Mr X sees nothing but the map and plays alone against the clock, 24 rounds, no rematch.

---

## How it plays

| Role | Count | Knows |
|---|---|---|
| Mr X | 1 | Everyone's position. Moves first each round. |
| Detective | 1–5 | Every other detective's position, always. Mr X's, only on a reveal round. |

Movement runs on four transport modes, each tied to a ticket and a colour on the map:

| Mode | Ticket |
|---|---|
| E-scooter | `ESCOOTER` |
| Bus | `BUS` |
| Train | `TRAIN` |
| Ferry | `FERRY` |

A move needs a ticket matching the mode of the edge being crossed. Detectives hold a fixed budget per game (10 escooter / 8 bus / 4 train / 2 ferry, configurable) and never get more — spend them badly and you're stuck. Mr X never runs out of the four regular tickets, but also holds a small number of two special ones:

- **`INVISIBLE`** — travel any edge, any mode, no matching ticket required. Detectives see that an invisible ticket was used, never which mode it disguised.
- **`DOUBLE`** — take two moves in one turn before any detective responds. Rare (2 per game) and the only way to put real distance between two reveals.

**Reveal rounds** are fixed: 3, 8, 13, 18, 24. At the start of Mr X's turn on those rounds, his node is broadcast to every detective and logged. Between reveals he's a ticket trail and nothing else.

**The game ends** the instant a detective's move lands on Mr X's node — detectives win. If round 24 completes with Mr X still free, he wins. A disconnect that doesn't reconnect within the grace period aborts the game for everyone.

Full mechanical detail — turn ordering, movement constraints, double-move sequencing — lives in [`documentation/spec.md`](documentation/spec.md).

---

## Architecture

```
Browser (Vue 3 + MapLibre GL)
  │
  ├── REST HTTP/JSON ───────────────────────┐   lobby, move submission
  │                                         ▼
  └── WebSocket (STOMP/SockJS) ──► Spring Boot backend
                                     ├── REST controllers
                                     ├── STOMP broker
                                     ├── game engine (pure Java, in memory)
                                     └── ConcurrentHashMap<gameId, GameSession>
```

All authoritative state lives server-side in memory — no database, restarting the server ends every active game. The client only ever holds a role-filtered display copy pushed over WebSocket, which is how Mr X's hidden position stays hidden: the server never sends it to detectives outside a reveal round in the first place. The Wellington graph itself is a static JSON file the frontend fetches once on load, not a live routing API — see [`documentation/spec.md`](documentation/spec.md#3-wellington-graph) for why that tradeoff was made.

| Layer | Technology |
|---|---|
| Frontend | Vue 3, Vite, Tailwind CSS, TypeScript |
| Map | MapLibre GL |
| Backend | Java 21, Spring Boot 3, Maven |
| Real-time | Websocket |
| API contract | [`documentation/openapi.yaml`](documentation/openapi.yaml) — REST + WebSocket, kept in sync with every API change |

---

## Running it locally

**Backend** (serves the API on `:8999`):

```bash
cd backend
mvn spring-boot:run
```

**Frontend** (dev server on `:5173`, proxies `/api` and `/ws` to the backend):

```bash
cd frontend
npm install
npm run dev
```

Game settings — turn timer, disconnect grace period, detective ticket budgets, which map file to load — are in `backend/src/main/resources/application.properties`. By default the backend loads the full 261-node Wellington map (`map.json`); a 5-node `test-map.json` is also bundled for fast manual testing.

To regenerate or edit the map itself, see `mapCreator/` — a standalone HTML tool for placing nodes/edges on Wellington, plus a headless Selenium harness (`mapCreator/headless/`) for scripted generation and quality evaluation.

---

## Testing

```bash
cd backend
mvn test              # unit + lifecycle tests
mvn test -Dtest=FullGameE2ETest   # end-to-end, drives the real API over HTTP via a headless Firefox
```

The end-to-end suite requires Firefox and `geckodriver` on the host — see [`backend/doc.md`](backend/doc.md) for setup. Set `-De2e.headless=false` to watch the browser drive the game instead of running it headless.

---

## License & attribution

Licensed under [GPL-3.0](LICENSE).

Game mechanics are based on the board game *Scotland Yard*, designed by Ravensburger — used here with their permission, for this non-commercial ENGR489 student project at Victoria University of Wellington. This project is not affiliated with or endorsed by Ravensburger, and the Scotland Yard name/brand is not used in connection with it.
