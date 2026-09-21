### Overview

Game-level phases. A game in progress ends when the detectives catch Mr X or box him in, when Mr X survives round 24, or when it's aborted because a player left or the current player went 15 minutes without moving. A dropped WebSocket doesn't pause anything: clients reconnect and re-sync over REST.

```mermaid
stateDiagram-v2
    direction TB

    [*] --> Idle
    Idle --> Lobby : GameCreated
    Lobby --> InProgress : GameStarted
    Lobby --> LobbyClosed : HostLeft

    state InProgress {
        [*] --> MrXTurn
        MrXTurn --> DetectiveTurn : MoveComplete
        DetectiveTurn --> RoundEnd : AllDetectivesMoved
        RoundEnd --> MrXTurn : NextRound
    }

    InProgress --> DetectivesWin : Caught
    InProgress --> DetectivesWin : MrXBoxedIn
    InProgress --> MrXWins : Round24Reached
    InProgress --> GameAborted : PlayerLeft
    InProgress --> GameAborted : TurnIdle15Min

    DetectivesWin --> [*]
    MrXWins --> [*]
    GameAborted --> [*]
    LobbyClosed --> [*]
```

---

### Mr X states

His turn starts with a check that he can move at all: if detectives hold every neighbouring node, the detectives win. **AwaitingMove** tracks whether he's active or idle; after 15 minutes without a move the server aborts the game. Network errors enter the client's reconnect loop (STOMP retries indefinitely, and the client re-syncs over REST every 6 s).

```mermaid
stateDiagram-v2
    direction TB

    [*] --> CheckingBoxedIn
    CheckingBoxedIn --> DetectivesWin : NoLegalMove
    CheckingBoxedIn --> AwaitingMove : HasLegalMove

    state AwaitingMove {
        [*] --> Active
        Active --> Idle : NoActivityTimeout
        Idle --> Active : ActivityReceived
    }

    AwaitingMove --> ValidatingMove : MoveSubmitted
    AwaitingMove --> GameAborted : TurnIdle15Min
    AwaitingMove --> Reconnecting : NetworkError
    Reconnecting --> AwaitingMove : ReconnectSuccess

    ValidatingMove --> AwaitingMove : MoveInvalid
    ValidatingMove --> ApplyingSingleMove : MoveValidSingle
    ValidatingMove --> ApplyingDoubleMove : MoveValidDouble
    ApplyingSingleMove --> Logging : MoveApplied
    ApplyingDoubleMove --> AwaitingMove : FirstLegApplied
    ApplyingDoubleMove --> Logging : SecondLegApplied
    Logging --> Revealed : RevealRound
    Logging --> Hidden : OtherRound
    Revealed --> [*] : MoveComplete
    Hidden --> [*] : MoveComplete
    DetectivesWin --> [*]
    GameAborted --> [*]
```

---

### Detective states

Same active/idle tracking, idle limit and reconnect path as Mr X. A detective with no legal move is skipped without spending a ticket.

```mermaid
stateDiagram-v2
    direction TB

    [*] --> AwaitingDetMove

    state AwaitingDetMove {
        [*] --> Active
        Active --> Idle : NoActivityTimeout
        Idle --> Active : ActivityReceived
    }

    AwaitingDetMove --> ValidatingMove : MoveSubmitted
    AwaitingDetMove --> Skipped : NoValidMoves
    AwaitingDetMove --> GameAborted : TurnIdle15Min
    AwaitingDetMove --> Reconnecting : NetworkError
    Reconnecting --> AwaitingDetMove : ReconnectSuccess

    ValidatingMove --> AwaitingDetMove : MoveInvalid
    ValidatingMove --> ApplyingMove : MoveValid
    ApplyingMove --> CheckingCatch : MoveApplied
    CheckingCatch --> DetectivesWin : Caught
    CheckingCatch --> Skipped : NotCaught
    Skipped --> AwaitingDetMove : NextDetective
    Skipped --> [*] : AllDetectivesMoved
    DetectivesWin --> [*]
    GameAborted --> [*]
```
