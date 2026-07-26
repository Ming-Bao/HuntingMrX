# Backend — Setup & Run

Spring Boot 3.5.0 · Java 21 · Maven

## Prerequisites

- **Java 21** — verify with `java -version`
- **Maven 3.9+** — verify with `mvn -version` (or use the included `mvnw` wrapper)

## Run (development)

```bash
cd backend
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

## Build (production jar)

```bash
mvn package
java -jar target/hunting-mrx-wellington-backend-0.0.1-SNAPSHOT.jar
```

## Configuration

Settings are in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `game.grace-period-seconds` | `60` | Disconnection grace period before game is aborted |
| `game.turn-timer-seconds` | `120` | Auto-skip a player's turn after this many seconds |
| `game.detective-escooter-tickets` | `10` | Escooter tickets per detective |
| `game.detective-bus-tickets` | `8` | Bus tickets per detective |
| `game.detective-train-tickets` | `4` | Train tickets per detective |
| `game.detective-ferry-tickets` | `2` | Ferry tickets per detective |

## API endpoints (Sprint 1)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/games` | Create a game — body: `{ "hostName": "Alice", "maxPlayers": 4 }` |
| `POST` | `/api/games/join` | Join a game — body: `{ "joinCode": "WXYZ12", "playerName": "Bob" }` |
| `GET` | `/api/games/{id}` | Get current game state |

All endpoints return `400` with `{ "error": "..." }` on invalid input.

## Quick smoke test

```bash
# Create a game
curl -s -X POST http://localhost:8080/api/games \
  -H 'Content-Type: application/json' \
  -d '{"hostName":"Alice","maxPlayers":4}' | jq .

# Join with the joinCode from the response above
curl -s -X POST http://localhost:8080/api/games/join \
  -H 'Content-Type: application/json' \
  -d '{"joinCode":"<code>","playerName":"Bob"}' | jq .
```
