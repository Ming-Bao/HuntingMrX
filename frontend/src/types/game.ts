export type TicketType = 'ESCOOTER' | 'BUS' | 'TRAIN' | 'FERRY' | 'BLACK' | 'DOUBLE'

export interface GraphNode { id: number; lat: number; lng: number; label: string }
export interface GraphEdge { from: number; to: number; modes: string[]; coordinates?: [number, number][] }
export interface MapData { nodes: GraphNode[]; edges: GraphEdge[] }

// Legacy UI helpers used by InfoPanel / demo
export interface DemoPlayer { name: string; isYou: boolean; role: string; node: number | null; color: string }
export interface DemoTicket { type: string; label: string; count: number; color: string }

export type GamePhase = 'LOBBY' | 'IN_PROGRESS' | 'PAUSED' | 'ENDED'
export type Role = 'MR_X' | 'DETECTIVE'
export type TurnPhase = 'MR_X_TURN' | 'DETECTIVE_TURN'

export interface PlayerDTO {
  id: string
  name: string
  role: Role | null
  nodeId: number | null
  tickets: Record<TicketType, number> | null
}

export interface MrXLogEntry {
  round: number
  leg: number
  ticketUsed: string
  nodeId: number | null
  doubleMove: boolean
}

export interface GameStateDTO {
  gameId: string
  joinCode: string
  phase: GamePhase
  maxPlayers: number
  players: PlayerDTO[]
  round: number
  turnPhase: TurnPhase | null
  currentPlayerId: string | null
  winner: string | null
  abortReason: string | null
  mrXLog: MrXLogEntry[]
  mrXDoubleMovePending: boolean
}

export interface ValidMoveDTO {
  nodeId: number
  ticketOptions: string[]
}

export interface ValidMovesDTO {
  moves: ValidMoveDTO[]
}
