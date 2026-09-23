// The shape of the information the server sends. Mirrors the schemas in
// documentation/openapi.yaml; the field names must match the server's.

export type TicketType = 'ESCOOTER' | 'BUS' | 'TRAIN' | 'FERRY' | 'BLACK' | 'DOUBLE'
export type GamePhase = 'LOBBY' | 'IN_PROGRESS' | 'ENDED'
export type Role = 'MR_X' | 'DETECTIVE'
export type TurnPhase = 'MR_X_TURN' | 'DETECTIVE_TURN'

// The Wellington map, from GET /api/map
export interface MapNode { id: number; lat: number; lng: number; label: string }
export interface MapConnection { from: number; to: number; modes: string[]; coordinates?: [number, number][] }
export interface MapData { nodes: MapNode[]; edges: MapConnection[] }

export interface PlayerInfo {
  id: string
  name: string
  role: Role | null
  nodeId: number | null        // null when hidden from you (Mr X outside a reveal)
  tickets: Record<TicketType, number> | null
}

export interface MrXLogEntry {
  round: number
  leg: number                  // 2 for the second half of a double move
  ticketUsed: string
  nodeId: number | null        // set only on reveal rounds
  doubleMove: boolean
}

export interface GameInfo {
  gameId: string
  joinCode: string
  phase: GamePhase
  maxPlayers: number
  players: PlayerInfo[]        // players[0] is the host
  round: number
  turnPhase: TurnPhase | null
  currentPlayerId: string | null
  winner: string | null
  abortReason: string | null
  mrXLog: MrXLogEntry[]
  mrXDoubleMovePending: boolean
}

export interface PossibleMove {
  nodeId: number
  ticketOptions: string[]
}

// Not from the server: a player as drawn on the map and listed in the side panel
export interface PlayerMarker { name: string; isYou: boolean; role: Role | null; node: number | null; color: string }
