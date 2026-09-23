# Backend Class Diagrams

PlantUML source. A Mermaid version that renders on GitHub, with a package diagram, is in [`class-graph.md`](class-graph.md).

## High-Level Overview

```plantuml
@startuml
skinparam classAttributeIconSize 0

class GameController <<Controller>>
class MapController <<Controller>>
class GameService <<Service>>
class Game
class Player
class MapGraph

GameController --> GameService : delegates
MapController --> MapGraph : serves JSON
GameService "1" --> "0..*" Game : stores (in memory)
Game "1" *-- "1..6" Player : players
Game --> MapGraph : board
@enduml
```

## Detailed Diagram

```plantuml
@startuml
skinparam classAttributeIconSize 0

' ── Enumerations (package game.enums) ───────────────────────────────

enum GamePhase {
    LOBBY
    IN_PROGRESS
    ENDED
}

enum TurnPhase {
    MR_X_TURN
    DETECTIVE_TURN
}

enum Role {
    MR_X
    DETECTIVE
}

enum Winner {
    MR_X
    DETECTIVES
}

enum TicketType {
    ESCOOTER
    BUS
    TRAIN
    FERRY
    BLACK
    DOUBLE
}

' ── Rules core (package game, no Spring) ──────────────────────────────

class Player {
    - id : String
    - token : String
    - name : String
    - role : Role
    - node : Integer
    - tickets : Map<TicketType, Integer>
    --
    + has(ticket : TicketType) : boolean
    ~ assign(role : Role, node : int, tickets : Map) : void
    ~ moveTo(node : int) : void
    ~ spend(ticket : TicketType) : void
}

class MapGraph {
    - json : byte[]
    - nodeIds : List<Integer>
    - neighbours : Map<Integer, Map<Integer, Set<TicketType>>>
    --
    + {static} parse(json : byte[]) : MapGraph
    + modesBetween(a : int, b : int) : Set<TicketType>
    + validMoves(player : Player, blocked : Set<Integer>) : List<ValidMove>
}

class Game {
    - phase : GamePhase
    - round : int
    - turnPhase : TurnPhase
    - current : Player
    - doubleMovePending : boolean
    - winner : Winner
    - abortReason : String
    - mrXLog : List<MrXMove>
    --
    + join(name : String) : Player
    + start(requester : Player, rng : Random) : void
    + validMoves(player : Player) : List<ValidMove>
    + move(player : Player, to : int, ticket : String) : void
    + leave(player : Player) : void
    + kick(requester : Player, targetId : String) : void
    + abortIfIdle(limit : Duration) : boolean
    + viewFor(viewer : Player) : GameState
    + playerByToken(token : String) : Optional<Player>
}

Game "1" *-- "1..6" Player : players
Game --> MapGraph : board
Game ..> GamePhase
Game ..> TurnPhase
Game ..> Winner
Player ..> Role
Player ..> TicketType

' ── Views sent to clients (records in package game.view) ────────────

class GameState <<record>> {
    gameId, joinCode, phase, maxPlayers, players,
    round, turnPhase, currentPlayerId, winner,
    abortReason, mrXLog, mrXDoubleMovePending
}

class PlayerView <<record>> {
    id, name, role, nodeId, tickets
}

class MrXMove <<record>> {
    round, leg, ticketUsed, nodeId, doubleMove
}

class ValidMove <<record>> {
    nodeId, ticketOptions
}

GameState "1" *-- "0..*" PlayerView : players
GameState "1" *-- "0..*" MrXMove : mrXLog
Game ..> GameState : builds per viewer

' ── Spring layer ──────────────────────────────────────────────────────

class GameSettings <<record>> {
    mapFile, turnTimerSeconds,
    detective ticket counts
}

class GameService <<Service>> {
    - games : ConcurrentHashMap<String, Game>
    --
    + createGame(hostName : String, maxPlayers : int) : JoinResponse
    + joinGame(joinCode : String, playerName : String) : JoinResponse
    + getGame(gameId : String, token : String) : GameState
    + startGame(gameId : String, token : String) : GameState
    + removePlayer(gameId : String, token : String, targetPlayerId : String) : void
    + validMoves(gameId : String, token : String) : List<ValidMove>
    + submitMove(gameId : String, token : String, toNodeId : int, ticket : String) : GameState
    + abortIdleGames() : void
    - publish(game : Game) : void
}

class JoinResponse <<record>> {
    playerId, playerToken, gameState
}

class GameController <<Controller>> {
    + createGame(req) : JoinResponse
    + joinGame(req) : JoinResponse
    + getGame(id, token) : GameState
    + startGame(id, token) : GameState
    + removePlayer(id, targetPlayerId, token) : void
    + getValidMoves(id, token) : List<ValidMove>
    + submitMove(id, token, req) : GameState
}

class MapController <<Controller>> {
    + getMap() : byte[]
}

class WebSocketConfig <<Configuration>> {
    exact-match subscriptions only
    client SEND frames dropped
}

class PageRoutes <<Configuration>> {
    page addresses forwarded to index.html
}

GameService "1" --> "0..*" Game : stores, locks
GameService --> GameSettings
GameService ..> JoinResponse : creates
GameController --> GameService : delegates
MapController --> MapGraph : serves JSON

' ── Errors (package game.exception) ─────────────────────────────────

class NotFoundException <<RuntimeException>>
class ForbiddenException <<RuntimeException>>
class ConflictException <<RuntimeException>>

class ApiExceptionHandler <<ControllerAdvice>> {
    NotFoundException → 404
    ForbiddenException → 403
    ConflictException → 409
    IllegalArgumentException, unreadable body → 400
    error body: JSON with one "error" field
}

Game ..> NotFoundException : throws
Game ..> ForbiddenException : throws
Game ..> ConflictException : throws
GameService ..> NotFoundException : throws
GameService ..> ForbiddenException : throws
@enduml
```
