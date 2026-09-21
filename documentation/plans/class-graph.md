# Backend Package and Class Graphs

Rendered (Mermaid) version of [`classdiagram.md`](classdiagram.md). Both describe the backend in `backend/src/main/java/com/huntingmrxwellington/`.

## High-Level Package Diagram

Each box is a Java package with the classes in it. Solid arrows mean "imports from"; dotted arrows are network traffic. `game` is the rules engine: plain Java with no Spring, so it's unit-tested directly.

```mermaid
flowchart TB
    client(["Vue frontend (browser)"])

    subgraph backend["com.huntingmrxwellington"]
        app["<b>HuntingMrXApplication</b><br/>entry point, loads the map"]
        controller["<b>controller</b><br/>GameController<br/>MapController<br/>ApiExceptionHandler"]
        service["<b>service</b><br/>GameService"]
        config["<b>config</b><br/>GameSettings<br/>WebSocketConfig"]
        game["<b>game</b><br/><i>plain Java, no Spring</i><br/>Game · Player · MapGraph<br/>GameState · PlayerView<br/>MrXMove · ValidMove<br/>GamePhase · TurnPhase<br/>Role · Winner · TicketType"]
        exception["<b>exception</b><br/>GameNotFoundException<br/>ForbiddenException<br/>ConflictException"]
    end

    client -. "REST /api" .-> controller
    service -. "STOMP push over /ws" .-> client
    controller --> service
    controller --> game
    controller --> exception
    service --> config
    service --> game
    service --> exception
    app --> config
    app --> game
    config --> game
    game --> exception
```

## Detailed Class Diagram

Every field and public method, plus the package-private ones (marked `~`); static members are underlined. Left out: private helper methods (except `GameService.publish`, which sends every update) and `Game`'s plain getters. The diagram comes in two parts so it stays readable: the rules core, then the Spring layer around it, where `Game`, `MapGraph` and `GameState` appear again as plain boxes.

The same two parts are pages in [`class-graph.drawio`](class-graph.drawio), which is easier to zoom through: open it in draw.io (app.diagrams.net, the desktop app or the VS Code extension).

### Part 1: rules core (`game`)

`MapGraph.neighbours` is a `Map<Integer, Map<Integer, Set<TicketType>>>` (node to neighbour to modes); Mermaid can't draw a generic that nested, so it shows as `Map`.

```mermaid
classDiagram
    direction TB

    class Game {
        +int MAX_NAME_LENGTH$
        +int LAST_ROUND$
        +Set~Integer~ REVEAL_ROUNDS$
        -String id
        -String joinCode
        -int maxPlayers
        -MapGraph map
        -Map~TicketType,Integer~ detectiveTickets
        -InstantSource clock
        -List~Player~ players
        -List~MrXMove~ mrXLog
        -GamePhase phase
        -int round
        -TurnPhase turnPhase
        -Player current
        -boolean doubleMovePending
        -Winner winner
        -String abortReason
        -Instant turnStartedAt
        +join(String name) Player
        +host() Player
        +start(Player requester, Random rng) void
        ~start(Player requester, List~Player~ order, List~Integer~ startNodes) void
        +validMoves(Player player) List~ValidMove~
        +move(Player player, int to, String ticket) void
        +leave(Player player) boolean
        +kick(Player requester, String targetId) void
        +abortIfIdle(Duration limit) boolean
        +viewFor(Player viewer) GameState
        +playerByToken(String token) Optional~Player~
        +currentPlayer() Optional~Player~
        +players() List~Player~
    }
    class Player {
        +int UNLIMITED$
        -String id
        -String token
        -String name
        -EnumMap~TicketType,Integer~ tickets
        -Role role
        -Integer node
        +id() String
        +token() String
        +name() String
        +role() Role
        +node() Integer
        +isMrX() boolean
        +tickets() Map~TicketType,Integer~
        +has(TicketType ticket) boolean
        ~assign(Role role, int node, Map~TicketType,Integer~ startingTickets) void
        ~moveTo(int node) void
        ~spend(TicketType ticket) void
    }
    class MapGraph {
        -Set~TicketType~ EDGE_MODES$
        -byte[] json
        -List~Integer~ nodeIds
        -Map neighbours
        +parse(byte[] json)$ MapGraph
        +json() byte[]
        +nodeIds() List~Integer~
        +modesBetween(int a, int b) Set~TicketType~
        +validMoves(Player player, Set~Integer~ blocked) List~ValidMove~
    }
    class GameState {
        <<record>>
        String gameId
        String joinCode
        GamePhase phase
        int maxPlayers
        List~PlayerView~ players
        int round
        TurnPhase turnPhase
        String currentPlayerId
        Winner winner
        String abortReason
        List~MrXMove~ mrXLog
        boolean mrXDoubleMovePending
    }
    class PlayerView {
        <<record>>
        String id
        String name
        Role role
        Integer nodeId
        Map~TicketType,Integer~ tickets
    }
    class MrXMove {
        <<record>>
        int round
        int leg
        TicketType ticketUsed
        Integer nodeId
        boolean doubleMove
    }
    class ValidMove {
        <<record>>
        int nodeId
        List~TicketType~ ticketOptions
    }
    class GamePhase {
        <<enumeration>>
        LOBBY
        IN_PROGRESS
        ENDED
    }
    class TurnPhase {
        <<enumeration>>
        MR_X_TURN
        DETECTIVE_TURN
    }
    class Role {
        <<enumeration>>
        MR_X
        DETECTIVE
    }
    class Winner {
        <<enumeration>>
        MR_X
        DETECTIVES
    }
    class TicketType {
        <<enumeration>>
        ESCOOTER
        BUS
        TRAIN
        FERRY
        BLACK
        DOUBLE
    }

    Game "1" *-- "1..6" Player : players
    Game "1" *-- "0..*" MrXMove : mrXLog
    Game --> MapGraph : board
    Game ..> GameState : viewFor builds
    GameState "1" *-- "0..*" PlayerView : players
    GameState --> MrXMove : mrXLog
    MapGraph ..> ValidMove : creates
    Game ..> GamePhase
    Game ..> TurnPhase
    Game ..> Winner
    Player ..> Role
    Player ..> TicketType
```

### Part 2: Spring layer (`controller`, `service`, `config`, `exception`)

`Game` and `GameService` throw the three exceptions. `ApiExceptionHandler` turns them into 404, 403 and 409, and an `IllegalArgumentException` or an unreadable request body into 400.

```mermaid
classDiagram
    direction LR

    namespace controller {
        class ApiExceptionHandler {
            <<RestControllerAdvice>>
            +notFound(GameNotFoundException e) ResponseEntity
            +forbidden(ForbiddenException e) ResponseEntity
            +conflict(ConflictException e) ResponseEntity
            +badRequest(IllegalArgumentException e) ResponseEntity
            +unreadable(HttpMessageNotReadableException e) ResponseEntity
        }
        class MapController {
            <<RestController>>
            -MapGraph map
            +getMap() byte[]
        }
        class GameController {
            <<RestController>>
            +String TOKEN_HEADER$
            -GameService games
            +createGame(CreateGameRequest req) JoinResponse
            +joinGame(JoinGameRequest req) JoinResponse
            +getGame(String id, String token) GameState
            +startGame(String id, String token) GameState
            +removePlayer(String id, String targetPlayerId, String token) void
            +getValidMoves(String id, String token) List~ValidMove~
            +submitMove(String id, String token, MoveRequest req) GameState
        }
        class CreateGameRequest {
            <<record>>
            String hostName
            int maxPlayers
        }
        class JoinGameRequest {
            <<record>>
            String joinCode
            String playerName
        }
        class MoveRequest {
            <<record>>
            Integer toNodeId
            String ticket
        }
    }

    namespace service {
        class GameService {
            <<Service>>
            -String JOIN_CODE_CHARS$
            -Map~String,Game~ games
            -SimpMessagingTemplate messaging
            -MapGraph map
            -GameSettings settings
            -Random random
            -InstantSource clock
            +createGame(String hostName, int maxPlayers) JoinResponse
            +joinGame(String joinCode, String playerName) JoinResponse
            +getGame(String gameId, String token) GameState
            +startGame(String gameId, String token) GameState
            +removePlayer(String gameId, String token, String targetPlayerId) void
            +validMoves(String gameId, String token) List~ValidMove~
            +submitMove(String gameId, String token, int toNodeId, String ticket) GameState
            +abortIdleGames() void
            -publish(Game game) void
        }
        class JoinResponse {
            <<record>>
            String playerId
            String playerToken
            GameState gameState
        }
    }

    class HuntingMrXApplication {
        <<SpringBootApplication>>
        +main(String[] args)$ void
        ~mapGraph(GameSettings settings) MapGraph
    }

    namespace config {
        class GameSettings {
            <<record>>
            String mapFile
            int turnTimerSeconds
            int detectiveEscooterTickets
            int detectiveBusTickets
            int detectiveTrainTickets
            int detectiveFerryTickets
            +detectiveTickets() Map~TicketType,Integer~
        }
        class WebSocketConfig {
            <<Configuration>>
            +configureMessageBroker(MessageBrokerRegistry registry) void
            +registerStompEndpoints(StompEndpointRegistry registry) void
            +configureClientInboundChannel(ChannelRegistration registration) void
        }
    }

    namespace exception {
        class GameNotFoundException
        class ForbiddenException
        class ConflictException
    }

    namespace game {
        class Game
        class MapGraph
        class GameState
    }

    GameController --> GameService : delegates
    GameController ..> CreateGameRequest
    GameController ..> JoinGameRequest
    GameController ..> MoveRequest
    MapController --> MapGraph : serves JSON
    GameService "1" o-- "0..*" Game : stores and locks
    GameService --> MapGraph
    GameService --> GameSettings : reads
    GameService ..> JoinResponse : returns
    JoinResponse --> GameState
    HuntingMrXApplication ..> MapGraph : creates at startup
    RuntimeException <|-- GameNotFoundException
    RuntimeException <|-- ForbiddenException
    RuntimeException <|-- ConflictException
```
