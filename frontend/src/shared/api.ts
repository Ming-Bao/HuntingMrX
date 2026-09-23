// All talk with the server: the one-off requests, and the live connection
// that pushes game updates as they happen.
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { GameInfo, MapData, PossibleMove } from './types'

// '/' by default, or e.g. '/mrx/' when built with BASE_PATH=/mrx (see
// vite.config.ts). Every address below is built from it, so hosting the site
// under a sub-folder needs no other change.
export const SITE_ADDRESS = import.meta.env.BASE_URL
const SERVER_ADDRESS = `${SITE_ADDRESS}api`
const LIVE_ADDRESS = `${SITE_ADDRESS}ws`

export interface JoinResult {
  playerId: string        // public id, shown to everyone
  playerToken: string     // secret key: proves who is acting, and names your private channels
  gameState: GameInfo
}

/** Sends one request to the server and returns its reply, or throws the server's error message. */
async function askServer<T>(method: string, path: string, secretKey?: string | null, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (secretKey) headers['X-Player-Token'] = secretKey
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(SERVER_ADDRESS + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const reply = await response.json().catch(() => null) // an empty reply (e.g. after leaving) becomes null
  if (!response.ok) throw new Error(reply?.error ?? 'Request failed')
  return reply as T
}

export const createGame = (hostName: string, maxPlayers: number) =>
  askServer<JoinResult>('POST', '/games/create', null, { hostName, maxPlayers })

export const joinGame = (joinCode: string, playerName: string) =>
  askServer<JoinResult>('POST', '/games/join', null, { joinCode, playerName })

export const getGame = (gameId: string, secretKey: string) =>
  askServer<GameInfo>('GET', `/games/${gameId}`, secretKey)

export const startGame = (gameId: string, secretKey: string) =>
  askServer<GameInfo>('POST', `/games/${gameId}/start`, secretKey)

/** Removing yourself is leaving; the host removing someone else is a kick. */
export const removePlayer = (gameId: string, secretKey: string, playerToRemove: string) =>
  askServer<void>('DELETE', `/games/${gameId}/players/${playerToRemove}`, secretKey)

// The server calls these "valid moves"
export const getPossibleMoves = (gameId: string, secretKey: string) =>
  askServer<PossibleMove[]>('GET', `/games/${gameId}/valid-moves`, secretKey)

export const submitMove = (gameId: string, secretKey: string, toNodeId: number, ticket: string) =>
  askServer<GameInfo>('POST', `/games/${gameId}/moves`, secretKey, { toNodeId, ticket })

export const getMap = () => askServer<MapData>('GET', '/map')

/**
 * Listens on the given live channels (channel → what to do with each message).
 * Returns a function that stops everything.
 *
 * Live messages are sent once and never repeated, and user-testing networks
 * dropped connections often. So `refresh` (fetch the whole game) also runs on
 * every reconnect and every 6 s: a missed message costs at most 6 s.
 */
export function keepUpToDate(channels: Record<string, (message: any) => void>, refresh: () => void): () => void {
  const connection = new Client({
    // SockJS rather than a bare WebSocket: it falls back to plain HTTP
    // streaming on networks that block WebSockets.
    webSocketFactory: () => new SockJS(LIVE_ADDRESS),
    // Runs again after every automatic reconnect. A new connection starts with
    // no subscriptions, so they're all set up here each time.
    onConnect: () => {
      refresh()
      for (const [channel, onMessage] of Object.entries(channels)) {
        connection.subscribe(channel, message => onMessage(JSON.parse(message.body)))
      }
    },
  })
  connection.activate()
  const refreshTimer = window.setInterval(refresh, 6000)
  return () => {
    connection.deactivate()
    window.clearInterval(refreshTimer)
  }
}
