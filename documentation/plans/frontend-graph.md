# Frontend Package and Component Graphs

Mermaid diagrams of the Vue frontend in `frontend/src/`, the counterpart of [`class-graph.md`](class-graph.md) for the backend. The detailed diagrams are also pages in [`frontend-graph.drawio`](frontend-graph.drawio), which is easier to zoom through: open it in draw.io (app.diagrams.net, the desktop app or the VS Code extension).

## High-Level Package Diagram

Each box is a folder under `frontend/src/` with the files in it and, in italics, the libraries only it uses. Solid arrows mean "imports from" (the router imports views lazily); dotted arrows are network traffic.

```mermaid
flowchart TB
    backend(["Spring Boot backend"])

    subgraph src["frontend/src"]
        root["<b>main.ts · App.vue</b><br/>creates the app, Pinia and the router"]
        router["<b>router</b><br/>index.ts"]
        views["<b>views</b><br/>LandingView · CreateGameView<br/>JoinGameView · LobbyView<br/>GameBoardView · GameEndView<br/><i>@stomp/stompjs · sockjs-client</i>"]
        cgame["<b>components/game</b><br/>GameMap · InfoPanel<br/>MoveSelector · MrXLog · TicketGrid<br/><i>maplibre-gl</i>"]
        clobby["<b>components/lobby</b><br/>JoinCodeCard · PlayerSlotList<br/><i>qrcode</i>"]
        cui["<b>components/ui</b><br/>ErrorBanner · FormInput<br/>PageHeader · ThemeToggle"]
        stores["<b>stores</b> (Pinia)<br/>gameStore · themeStore"]
        api["<b>api</b><br/>gameApi"]
        types["<b>types</b><br/>game.ts"]
        utils["<b>utils</b><br/>basePath · revealRounds<br/>transportModes"]
    end

    api -. "REST /api" .-> backend
    backend -. "STOMP push over /ws" .-> views
    root --> router
    root --> cui
    root --> stores
    router -- "lazy import()" --> views
    views --> cgame
    views --> clobby
    views --> cui
    views --> stores
    views --> api
    views --> types
    views --> utils
    cgame --> types
    cgame --> utils
    clobby --> types
    clobby --> utils
    cui --> stores
    stores --> types
    api --> types
    api --> utils
```

## Detailed Component Diagram

Vue components are drawn as classes: `+` marks props, `-` local state and functions, `/` computed values, and `«emit»` the events a component emits. Everything in a `<script setup>` block is private except what it exposes; `GameMap.focusNodeId` is the only exposed method. Left out: CSS-class and colour computeds, the popup text helpers in `GameBoardView`, and `GameMap`'s map-drawing internals. The diagram comes in two parts: state, API and types first, then the router, views and components that use them.

### Part 1: state, API and types (`stores`, `api`, `types`, `utils`)

`gameStore` keeps `gameId`, `playerId` and `playerToken` in `sessionStorage`; `themeStore` keeps the theme in `localStorage`. `leaveGame` and `kickPlayer` are the same function: `DELETE /api/games/{id}/players/{targetPlayerId}`.

```mermaid
classDiagram
    direction TB

    class gameStore {
        <<store>>
        +gameId : string | null
        +playerId : string | null
        +playerToken : string | null
        +gameState : GameStateDTO | null
        +validMoves : ValidMoveDTO[]
        /isMyTurn : boolean
        /myPlayer : PlayerDTO | null
        /myRole : Role | null
        /isMrX : boolean
        +setGame(gameId: string, playerId: string, playerToken: string, state: GameStateDTO) void
        +updateGameState(state: GameStateDTO) void
        +setValidMoves(moves: ValidMoveDTO[]) void
        +clearGame() void
    }
    class themeStore {
        <<store>>
        +isDark : boolean
        +toggle() void
    }
    class gameApi {
        <<module>>
        -TOKEN_HEADER = X-Player-Token
        +createGame(hostName: string, maxPlayers: number) Promise~JoinResponse~
        +joinGame(joinCode: string, playerName: string) Promise~JoinResponse~
        +getGame(gameId: string, playerToken?: string) Promise~GameStateDTO~
        +startGame(gameId: string, playerToken: string) Promise~GameStateDTO~
        +leaveGame(gameId: string, playerToken: string, targetPlayerId: string) Promise~void~
        +kickPlayer(gameId: string, playerToken: string, targetPlayerId: string) Promise~void~
        +getValidMoves(gameId: string, playerToken: string) Promise~ValidMoveDTO[]~
        +submitMove(gameId: string, playerToken: string, toNodeId: number, ticket: string) Promise~GameStateDTO~
        +getMap() Promise~MapData~
    }
    class JoinResponse {
        <<interface>>
        +playerId : string
        +playerToken : string
        +gameState : GameStateDTO
    }
    class GameStateDTO {
        <<interface>>
        +gameId : string
        +joinCode : string
        +phase : GamePhase
        +maxPlayers : number
        +players : PlayerDTO[]
        +round : number
        +turnPhase : TurnPhase | null
        +currentPlayerId : string | null
        +winner : string | null
        +abortReason : string | null
        +mrXLog : MrXLogEntry[]
        +mrXDoubleMovePending : boolean
    }
    class PlayerDTO {
        <<interface>>
        +id : string
        +name : string
        +role : Role | null
        +nodeId : number | null
        +tickets : Record~TicketType, number~ | null
    }
    class MrXLogEntry {
        <<interface>>
        +round : number
        +leg : number
        +ticketUsed : string
        +nodeId : number | null
        +doubleMove : boolean
    }
    class ValidMoveDTO {
        <<interface>>
        +nodeId : number
        +ticketOptions : string[]
    }
    class MapData {
        <<interface>>
        +nodes : GraphNode[]
        +edges : GraphEdge[]
    }
    class GraphNode {
        <<interface>>
        +id : number
        +lat : number
        +lng : number
        +label : string
    }
    class GraphEdge {
        <<interface>>
        +from : number
        +to : number
        +modes : string[]
        +coordinates? : Array~number[]~
    }
    class DemoPlayer {
        <<interface>>
        +name : string
        +isYou : boolean
        +role : string
        +node : number | null
        +color : string
    }
    class DemoTicket {
        <<interface>>
        +type : string
        +label : string
        +count : number
        +color : string
    }
    class TicketType {
        <<type>>
        ESCOOTER
        BUS
        TRAIN
        FERRY
        BLACK
        DOUBLE
    }
    class GamePhase {
        <<type>>
        LOBBY
        IN_PROGRESS
        ENDED
    }
    class Role {
        <<type>>
        MR_X
        DETECTIVE
    }
    class TurnPhase {
        <<type>>
        MR_X_TURN
        DETECTIVE_TURN
    }
    class basePath {
        <<module>>
        +BASE_URL : string
        +API_BASE : string
        +WS_PATH : string
    }
    class revealRounds {
        <<module>>
        +REVEAL_ROUNDS : number[]
        +nextRevealRound(currentRound: number) number | null
    }
    class transportModes {
        <<module>>
        +MODE_COLORS : Record~string, string~
        +modeLegend : Array~object~
        +modeColor(mode: string) string
        +modeLabel(mode: string) string
    }

    gameStore --> GameStateDTO : gameState
    gameStore --> ValidMoveDTO : validMoves
    gameApi ..> JoinResponse : returns
    gameApi ..> MapData : returns
    gameApi ..> basePath : API_BASE
    JoinResponse --> GameStateDTO
    GameStateDTO "1" *-- "0..*" PlayerDTO : players
    GameStateDTO "1" *-- "0..*" MrXLogEntry : mrXLog
    GameStateDTO ..> GamePhase
    GameStateDTO ..> TurnPhase
    PlayerDTO ..> Role
    PlayerDTO ..> TicketType
    MapData "1" *-- "0..*" GraphNode : nodes
    MapData "1" *-- "0..*" GraphEdge : edges
```

### Part 2: router, views and components

Every view except `LandingView` reads `gameStore`, and `CreateGameView`, `JoinGameView`, `LobbyView` and `GameBoardView` call `gameApi` (see Part 1). `LobbyView` subscribes to the public topic `/topic/games/{id}`; `GameBoardView` subscribes to the player's two private topics. Both also re-sync over REST on every reconnect and poll every 6 s. Props flow down each arrow and events flow back up.

```mermaid
classDiagram
    direction TB

    class App {
        <<component>>
        /isGameBoard : boolean
    }
    class router {
        <<router>>
    }
    class LandingView {
        <<view>>
    }
    class CreateGameView {
        <<view>>
        -hostName : string
        -maxPlayers : number
        -loading : boolean
        -error : string
        -handleCreate()
    }
    class JoinGameView {
        <<view>>
        -playerName : string
        -joinCode : string
        -loading : boolean
        -error : string
        -handleJoin()
    }
    class LobbyView {
        <<view>>
        -starting : boolean
        -startError : string
        -kicked : boolean
        -aborted : boolean
        -abortMessage : string
        -stompClient : Client | null
        -pollHandle : number | null
        /gameId : string
        /gameState : GameStateDTO | null
        /isHost : boolean
        /canStart : boolean
        -applyState(state: GameStateDTO)
        -handleStart()
        -handleKick(targetPlayerId: string)
        -handleLeave()
    }
    class GameBoardView {
        <<view>>
        -nodes : GraphNode[]
        -edges : GraphEdge[]
        -mapError : string | null
        -gameMapRef : GameMap | null
        -selectedNode : GraphNode | null
        -selectedTicket : string | null
        -submitting : boolean
        -moveError : string | null
        -doubleMode : boolean
        -popupQueue : PopupEvent[]
        -stompClient : Client | null
        -pollHandle : number | null
        /gameState : GameStateDTO | null
        /myNodeId : number
        /reachableNodeIds : Set~number~
        /isSelectedReachable : boolean
        /displayPlayers : DemoPlayer[]
        /myTickets : DemoTicket[]
        /hasDoubleTicket : boolean
        /mrXDoubleMovePending : boolean
        /turnLabel : string
        /nextReveal : number | null
        -applyState(state: GameStateDTO)
        -syncFromServer()
        -connectWs()
        -handleSelectNode(node: GraphNode | null)
        -confirmMove()
        -dismissPopup()
        -handleLeave()
    }
    class GameEndView {
        <<view>>
        /gameState : GameStateDTO | null
        /winner : string | null
        /bannerText : string
        /resultText : string
        /narrative : string
    }
    class GameMap {
        <<component>>
        +nodes : GraphNode[]
        +edges : GraphEdge[]
        +displayPlayers : DemoPlayer[]
        +selectedNode : GraphNode | null
        +reachableIds? : Set~number~
        +validMoves? : ValidMoveDTO[]
        -searchQuery : string
        -exploreNode : GraphNode | null
        -popupNode : GraphNode | null
        -currentStyleId : string
        +focusNodeId(nodeId: number)
        -switchStyle(id: string)
        -selectPopupMode(mode: string)
        +«emit» select-node(node: GraphNode | null)
        +«emit» select-ticket(mode: string)
    }
    class InfoPanel {
        <<component>>
        +players : DemoPlayer[]
        +tickets : DemoTicket[]
        +mrXLog : MrXLogEntry[]
        +selectedNode : GraphNode | null
        +selectedTicket : string | null
        +reachable : boolean
        +isMyTurn : boolean
        +submitting : boolean
        +moveError : string | null
        +validMoves : ValidMoveDTO[]
        +nodes : GraphNode[]
        +doubleMode : boolean
        +hasDoubleTicket : boolean
        +mrXDoubleMovePending : boolean
        /nodeById : Map~number, GraphNode~
        -selectMove(nodeId: number, mode: string)
        +«emit» select-ticket(mode: string)
        +«emit» confirm-move()
        +«emit» select-node(node: GraphNode | null)
        +«emit» focus-node(nodeId: number)
        +«emit» declare-double()
        +«emit» cancel-double()
        +«emit» leave()
    }
    class MoveSelector {
        <<component>>
        +selectedNode : GraphNode | null
        +selectedTicket : string | null
        +reachable : boolean
        +submitting : boolean
        +moveError : string | null
        /nodeDisplayName : string
        +«emit» confirm()
    }
    class MrXLog {
        <<component>>
        +log : MrXLogEntry[]
    }
    class TicketGrid {
        <<component>>
        +tickets : DemoTicket[]
        +isMyTurn : boolean
        +hasDoubleTicket : boolean
        +doubleMode : boolean
        +mrXDoubleMovePending : boolean
        +«emit» declare-double()
        +«emit» cancel-double()
    }
    class JoinCodeCard {
        <<component>>
        +code : string
        -copied : boolean
        -linkCopied : boolean
        -qrDataUrl : string
        /joinLink : string
        -copyCode()
        -copyLink()
    }
    class PlayerSlotList {
        <<component>>
        +players : PlayerDTO[]
        +maxPlayers : number
        +hostPlayerId? : string
        /emptySlots : number
        +«emit» kick(playerId: string)
    }
    class ErrorBanner {
        <<component>>
        +message : string
    }
    class FormInput {
        <<component>>
        +label : string
        +modelValue : string
        +placeholder? : string
        +type? : string
        +maxlength? : number
        +inputClass? : string
        +uppercase? : boolean
        -handleInput(e: Event)
        +«emit» update:modelValue(value: string)
    }
    class PageHeader {
        <<component>>
        +title : string
        +«emit» back()
    }
    class ThemeToggle {
        <<component>>
    }

    App --> ThemeToggle : renders, not on /game/*
    App ..> router : RouterView
    router --> LandingView : /
    router --> CreateGameView : /create
    router --> JoinGameView : /join and /#58;code
    router --> LobbyView : /lobby/#58;id
    router --> GameBoardView : /game/#58;id
    router --> GameEndView : /game/#58;id/end
    CreateGameView --> PageHeader
    CreateGameView --> FormInput
    CreateGameView --> ErrorBanner
    JoinGameView --> PageHeader
    JoinGameView --> FormInput
    JoinGameView --> ErrorBanner
    LobbyView --> PageHeader
    LobbyView --> ErrorBanner
    LobbyView --> JoinCodeCard
    LobbyView --> PlayerSlotList
    GameBoardView --> GameMap : calls focusNodeId via ref
    GameBoardView --> InfoPanel
    InfoPanel --> TicketGrid
    InfoPanel --> MrXLog
    InfoPanel --> MoveSelector
```
