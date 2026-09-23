# Frontend Package and Component Graphs

Mermaid diagrams of the Vue frontend in `frontend/src/`, the counterpart of [`class-graph.md`](class-graph.md) for the backend. The detailed diagrams are also pages in [`frontend-graph.drawio`](frontend-graph.drawio), which is easier to zoom through: open it in draw.io (app.diagrams.net, the desktop app or the VS Code extension).

## High-Level Package Diagram

Each box is a file or folder under `frontend/src/`; `src/` itself holds only `App.vue` and folders. Italics mark libraries that only that box uses; `vue`, `vue-router` and the `lucide-vue-next` icons are used throughout. Solid arrows mean "imports from" (the router loads each page only when it's needed). Dotted arrows are network traffic.

```mermaid
flowchart TB
    backend(["Spring Boot backend"])

    subgraph src["frontend/src"]
        appvue["<b>App.vue</b><br/>theme toggle and RouterView"]
        app["<b>app/</b><br/>main.ts · router.ts · style.css<br/>starts the app, routes, shared CSS classes"]
        pages["<b>pages/</b><br/>HomePage · CreateGamePage<br/>JoinGamePage · LobbyPage<br/>GameBoardPage · GameOverPage"]
        cgame["<b>components/game-board/</b><br/>GameMap · SidePanel<br/>MrXLog · TicketGrid<br/><i>maplibre-gl</i>"]
        clobby["<b>components/lobby/</b><br/>JoinCodeCard · PlayerList<br/><i>qrcode</i>"]
        cshared["<b>components/</b><br/>PageHeader · ThemeToggle"]
        subgraph shared["shared/"]
            currentgame["<b>current-game.ts</b><br/>what we know about the game"]
            api["<b>api.ts</b><br/>server requests · keepUpToDate<br/><i>@stomp/stompjs · sockjs-client</i>"]
            types["<b>types.ts</b>"]
            tickets["<b>tickets.ts</b><br/>ticket colours, names, icons"]
        end
    end

    api -. "REST /api" .-> backend
    backend -. "STOMP push over /ws" .-> api
    app --> appvue
    appvue --> cshared
    app -- "router: lazy import()" --> pages
    pages --> cgame
    pages --> clobby
    pages --> cshared
    pages --> currentGame
    pages --> api
    pages --> types
    cgame --> currentGame
    cgame --> tickets
    cgame --> types
    clobby --> api
    clobby --> types
    currentGame --> api
    currentGame --> types
    api --> types
```

## Detailed Component Diagram

Vue components are drawn as classes: `+` marks props and exports, `-` local state and functions, `/` computed values and getters, and `«emit»` the events a component emits. Everything in a `<script setup>` block is private except what it exposes; `GameMap.showNode` is the only exposed method. Left out: CSS-class computeds and `GameMap`'s map-drawing internals (icons, map data and layers). The diagram comes in two parts: game information, server calls and types first, then the router, pages and components that use them.

### Part 1: game information, server and types (`shared/`: `current-game.ts`, `api.ts`, `types.ts`, `tickets.ts`)

`currentGame` is a plain Vue `reactive()` object. It keeps `gameId`, `myPlayerId` and `mySecretKey` in `sessionStorage` so a refresh keeps your seat. `api.removePlayer` does both leaving and kicking: `DELETE /api/games/{id}/players/{playerToRemove}`. `JoinResult` keeps the server's own field names (`playerId`, `playerToken`, `gameState`). `keepUpToDate` opens the live connection (STOMP over SockJS), listens on the given channels, asks the server for the whole game again on every (re)connect and every 6 s, and returns a stop function.

```mermaid
classDiagram
    direction TB

    class currentGame {
        <<module>>
        +gameId : string | null
        +myPlayerId : string | null
        +mySecretKey : string | null
        +info : GameInfo | null
        +possibleMoves : PossibleMove[]
        /me : PlayerInfo | null
        /myRole : Role | null
        /isMyTurn : boolean
        +rememberGame(gameId: string, myPlayerId: string, mySecretKey: string, info: GameInfo) void
        +updateGameInfo(info: GameInfo) void
        +forgetGame() void
        +leaveGame() Promise~void~
    }
    class api {
        <<module>>
        +SITE_ADDRESS : string
        -askServer(method: string, path: string, secretKey?: string, body?: unknown) Promise~T~
        +createGame(hostName: string, maxPlayers: number) Promise~JoinResult~
        +joinGame(joinCode: string, playerName: string) Promise~JoinResult~
        +getGame(gameId: string, secretKey: string) Promise~GameInfo~
        +startGame(gameId: string, secretKey: string) Promise~GameInfo~
        +removePlayer(gameId: string, secretKey: string, playerToRemove: string) Promise~void~
        +getPossibleMoves(gameId: string, secretKey: string) Promise~PossibleMove[]~
        +submitMove(gameId: string, secretKey: string, toNodeId: number, ticket: string) Promise~GameInfo~
        +getMap() Promise~MapData~
        +keepUpToDate(channels: Record~string, handler~, refresh: function) function
    }
    class tickets {
        <<module>>
        +TICKET_COLORS : Record~string, string~
        +TRANSPORT_TYPES : string[]
        +TICKET_ORDER : string[]
        +ticketColor(mode: string) string
        +ticketName(mode: string) string
        +ticketIcon(mode: string) LucideIcon
    }
    class JoinResult {
        <<interface>>
        +playerId : string
        +playerToken : string
        +gameState : GameInfo
    }
    class GameInfo {
        <<interface>>
        +gameId : string
        +joinCode : string
        +phase : GamePhase
        +maxPlayers : number
        +players : PlayerInfo[]
        +round : number
        +turnPhase : TurnPhase | null
        +currentPlayerId : string | null
        +winner : string | null
        +abortReason : string | null
        +mrXLog : MrXLogEntry[]
        +mrXDoubleMovePending : boolean
    }
    class PlayerInfo {
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
    class PossibleMove {
        <<interface>>
        +nodeId : number
        +ticketOptions : string[]
    }
    class MapData {
        <<interface>>
        +nodes : MapNode[]
        +edges : MapConnection[]
    }
    class MapNode {
        <<interface>>
        +id : number
        +lat : number
        +lng : number
        +label : string
    }
    class MapConnection {
        <<interface>>
        +from : number
        +to : number
        +modes : string[]
        +coordinates? : Array~number[]~
    }
    class PlayerMarker {
        <<interface>>
        +name : string
        +isYou : boolean
        +role : Role | null
        +node : number | null
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

    currentGame --> GameInfo : info
    currentGame --> PossibleMove : possibleMoves
    currentGame ..> api : removePlayer
    api ..> JoinResult : returns
    api ..> MapData : returns
    JoinResult --> GameInfo
    GameInfo "1" *-- "0..*" PlayerInfo : players
    GameInfo "1" *-- "0..*" MrXLogEntry : mrXLog
    GameInfo ..> GamePhase
    GameInfo ..> TurnPhase
    PlayerInfo ..> Role
    PlayerInfo ..> TicketType
    PlayerMarker ..> Role
    MapData "1" *-- "0..*" MapNode : nodes
    MapData "1" *-- "0..*" MapConnection : edges
```

### Part 2: router, pages and components

Every page except `HomePage` uses `currentGame`, and every page except `HomePage` and `GameOverPage` calls `api` (see Part 1). `LobbyPage` listens on the public channel `/topic/games/{id}`; `GameBoardPage` listens on the player's two private channels. Both do it through `api.keepUpToDate`. `GameMap`, `SidePanel`, `TicketGrid` and `MrXLog` read game information straight from `currentGame`; props carry only the move being built in `GameBoardPage`. Props flow down each arrow and events flow back up.

```mermaid
classDiagram
    direction TB

    class App {
        <<component>>
    }
    class router {
        <<router>>
    }
    class HomePage {
        <<page>>
    }
    class CreateGamePage {
        <<page>>
        -hostName : string
        -maxPlayers : number
        -loading : boolean
        -error : string
        -create()
    }
    class JoinGamePage {
        <<page>>
        -playerName : string
        -joinCode : string
        -loading : boolean
        -error : string
        -join()
    }
    class LobbyPage {
        <<page>>
        -gameId : string
        -starting : boolean
        -startError : string
        -ended : object | null
        -stopUpdates()
        /info : GameInfo | null
        /isHost : boolean
        /canStart : boolean
        -onGameUpdate(state: GameInfo)
        -refresh()
        -start()
        -kick(playerToRemove: string)
        -leave()
    }
    class GameBoardPage {
        <<page>>
        -gameId : string
        -nodes : MapNode[]
        -edges : MapConnection[]
        -mapError : string | null
        -mapPanel : GameMap | null
        -selectedNode : MapNode | null
        -selectedTicket : string | null
        -usingDoubleTicket : boolean
        -sendingMove : boolean
        -moveError : string | null
        -popups : object[]
        -stopUpdates()
        /info : GameInfo | null
        /players : PlayerMarker[]
        /turnLabel : string
        /nextReveal : number | null
        -onGameUpdate(state: GameInfo)
        -refresh()
        -selectNode(node: MapNode | null)
        -confirmMove()
        -leave()
    }
    class GameOverPage {
        <<page>>
        /info : GameInfo | null
        /round : number
        /result : object
    }
    class GameMap {
        <<component>>
        +nodes : MapNode[]
        +edges : MapConnection[]
        +players : PlayerMarker[]
        +selectedNode : MapNode | null
        -searchText : string
        -exploredNode : MapNode | null
        -popupNode : MapNode | null
        -currentStyle : string
        /reachableNodeIds : Set~number~
        /myNode : MapNode | null
        +showNode(nodeId: number)
        -moveCameraTo(node: MapNode | null)
        -switchStyle(id: string)
        -pickPopupTicket(ticket: string)
        +«emit» select-node(node: MapNode | null)
        +«emit» select-ticket(ticket: string)
    }
    class SidePanel {
        <<component>>
        +players : PlayerMarker[]
        +nodes : MapNode[]
        +selectedNode : MapNode | null
        +selectedTicket : string | null
        +sendingMove : boolean
        +moveError : string | null
        +usingDoubleTicket : boolean
        /nodeLookup : Map~number, MapNode~
        /canReachSelected : boolean
        /selectedNodeName : string
        -pickMove(nodeId: number, ticket: string)
        +«emit» select-node(node: MapNode | null)
        +«emit» select-ticket(ticket: string)
        +«emit» confirm-move()
        +«emit» show-on-map(nodeId: number)
        +«emit» use-double-ticket()
        +«emit» cancel-double-ticket()
        +«emit» leave()
    }
    class TicketGrid {
        <<component>>
        +usingDoubleTicket : boolean
        /tickets : object[]
        /hasDoubleTicket : boolean
        +«emit» use-double-ticket()
        +«emit» cancel-double-ticket()
    }
    class MrXLog {
        <<component>>
        /log : MrXLogEntry[]
    }
    class JoinCodeCard {
        <<component>>
        +code : string
        -qrImage : string
        -copied : code | link | null
        /joinLink : string
        -copy(text: string, which: string)
    }
    class PlayerList {
        <<component>>
        +players : PlayerInfo[]
        +maxPlayers : number
        +canKick : boolean
        +«emit» kick(playerToRemove: string)
    }
    class PageHeader {
        <<component>>
        +title : string
        +«emit» back()
    }
    class ThemeToggle {
        <<component>>
        -isDark : boolean
        -toggle()
    }

    App --> ThemeToggle : renders, not on /game/*
    App ..> router : RouterView
    router --> HomePage : /
    router --> CreateGamePage : /create
    router --> JoinGamePage : /join and /#58;code
    router --> LobbyPage : /lobby/#58;id
    router --> GameBoardPage : /game/#58;id
    router --> GameOverPage : /game/#58;id/end
    CreateGamePage --> PageHeader
    JoinGamePage --> PageHeader
    LobbyPage --> PageHeader
    LobbyPage --> JoinCodeCard
    LobbyPage --> PlayerList
    GameBoardPage --> GameMap : calls showNode via ref
    GameBoardPage --> SidePanel
    SidePanel --> TicketGrid
    SidePanel --> MrXLog
```
