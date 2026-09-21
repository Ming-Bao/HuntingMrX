### Overall High level states


``` mermaid
stateDiagram-v2
    direction TB

    [*] --> Lobby
    Lobby --> Setup
    Lobby --> Closed : host left
    Setup --> MrXTurn

    MrXTurn --> DetectivesWin : Mr. X has no legal move
    MrXTurn --> DetectiveTurn
    DetectiveTurn --> CatchCheck

    CatchCheck --> DetectivesWin : on Mr. X node
    CatchCheck --> TurnCheck

    TurnCheck --> MrXWins : final round reached
    TurnCheck --> MrXTurn : final round not reached

    MrXTurn --> Aborted : player left or 15 min idle
    DetectiveTurn --> Aborted : player left or 15 min idle

    DetectivesWin --> [*]
    MrXWins --> [*]
    Aborted --> [*]
    Closed --> [*]
```

---

### Mr X flow

``` mermaid
flowchart TD
    A([Mr. X turn starts]) --> X{Any legal move?}
    X -- no, boxed in --> XW([Detectives win!])
    X -- yes --> B[Fetch valid moves]
    B --> C{Response ok?}
    C -- error --> B2[Show error, retry]
    B2 --> B
    C -- ok --> D[Render reachable nodes on map]
    D --> E[Mr. X selects node]
    E --> F{Valid ticket\nfor selected node?}
    F -- no valid ticket --> E
    F -- ticket available --> G[Use ticket]
    G --> H{Double ticket?}
    H -- yes, first leg --> I1[Submit DOUBLE_ticket leg\nvalidated and logged, never revealed]
    I1 --> E
    H -- no --> I[Submit move]
    I --> J{Server validates}
    J -- invalid --> E
    J -- valid --> K[Spend ticket, log the leg]
    K --> R{Reveal round?\nrounds 2,8,13,18,24}
    R -- yes --> RA[Show position to detectives]
    R -- no --> L
    RA --> L([Advance to Detective turn])
```

---

### Detective flow

``` mermaid
flowchart TD
    A([Detective turn starts]) --> B[Fetch all positions]
    B --> C[Fetch valid moves for current detective]
    C --> D{Has valid moves?}
    D -- no --> E[Detective skips]
    D -- yes --> F[Highlight reachable nodes on map]
    F --> G[Detective selects node]
    G --> H{Valid ticket\nfor selected node?}
    H -- no valid ticket --> G
    H -- ticket available --> I[Use ticket]
    I --> J[Submit move]
    J --> K{Catch check\nserver-side}
    K -- caught --> L([Detectives win!])
    K -- no catch --> M{All detectives moved?}
    E --> M
    M -- no --> C
    M -- yes --> O{Was that round 24?}
    O -- yes --> P([Mr. X wins!])
    O -- no --> N[Increment round]
    N --> Q([Next Mr. X turn])
  
```



