# Backend: setup and run

Spring Boot 4 · Java 21 · Maven

## Prerequisites

- **Java 21**: check with `java -version`
- **Maven 3.9+**: check with `mvn -version`

## Run (development)

```bash
cd backend
mvn spring-boot:run
```

The server starts on `http://localhost:8999`.

## Build (production jar)

```bash
mvn package
java -jar target/hunting-mrx-wellington-backend-0.0.1-SNAPSHOT.jar
```

## Configuration

Settings live in `src/main/resources/application.properties` and nowhere else:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8999` | HTTP port |
| `game.map-file` | `map.json` | Board file under `src/main/resources/static/`; `test-map.json` is a 5-node board for quick manual tests |
| `game.turn-timer-seconds` | `900` | A game is aborted if the current player hasn't moved for this long |
| `game.detective-escooter-tickets` | `12` | E-scooter tickets per detective |
| `game.detective-bus-tickets` | `8` | Bus tickets per detective |
| `game.detective-train-tickets` | `6` | Train tickets per detective |
| `game.detective-ferry-tickets` | `2` | Ferry tickets per detective |

## Code layout

- `game/`: the rules engine in plain Java (`Game`, `Player`, `MapGraph`) and the views sent to clients. No Spring in here.
- `service/GameService`: keeps the live games, locks around every call, checks player tokens, and publishes the new state over STOMP after every change.
- `controller/`: the REST endpoints and the error-to-status mapping.
- `config/`: `GameSettings` (the `game.*` properties) and `WebSocketConfig`.

The full REST and STOMP contract is in `documentation/openapi.yaml`.

## Tests

```bash
mvn test                                                   # everything except the latency test; writes target/site/jacoco/index.html
mvn test -Dgroups=perf -DexcludedGroups=                   # the multiplayer latency test only (prints a table)
mvn test-compile org.pitest:pitest-maven:mutationCoverage  # mutation testing; report in target/pit-reports/index.html
```

| Category | Where |
|---|---|
| Unit | `game/MapGraphTest`, `game/PlayerTest`, `game/GameTest` |
| Mock | `service/GameServiceTest` |
| Lifecycle | `service/GameLifecycleTest` |
| Integration | `controller/ApiIntegrationTest` (HTTP), `controller/WebSocketIntegrationTest` (STOMP) |
| Functional | `e2e/FullGameE2ETest` (full games over HTTP and STOMP) |
| Property-based and fuzz | `property/GamePropertyTest`, `property/ApiFuzzTest` (JUnit, seeded random) |
| Performance | `perf/MultiplayerPerfTest` (opt-in) |

No browser or driver is needed for any of them.

## Quick smoke test

```bash
# Create a game (keep the playerToken from the response: it's the host's secret)
curl -s -X POST http://localhost:8999/api/games/create \
  -H 'Content-Type: application/json' \
  -d '{"hostName":"Alice","maxPlayers":4}' | jq .

# Join with the joinCode from the response above
curl -s -X POST http://localhost:8999/api/games/join \
  -H 'Content-Type: application/json' \
  -d '{"joinCode":"<code>","playerName":"Bob"}' | jq .

# Start it as the host
curl -s -X POST http://localhost:8999/api/games/<gameId>/start \
  -H 'X-Player-Token: <host playerToken>' | jq .
```
