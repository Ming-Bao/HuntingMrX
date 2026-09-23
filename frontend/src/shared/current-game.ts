// Who you are and the latest state of your game, shared by every page. Any
// part of the screen that reads it redraws when it changes.
//
// Your ids live in sessionStorage: it survives a page refresh but is separate
// for each browser tab, so two tabs can play as two different players.
import { reactive } from 'vue'
import { removePlayer } from './api'
import type { GameInfo, PlayerInfo, Role, PossibleMove } from './types'

export const currentGame = reactive({
  gameId: sessionStorage.getItem('gameId'),
  myPlayerId: sessionStorage.getItem('myPlayerId'),
  // Secret proof of who you are, sent with every request and used to name
  // your private live channels. Never shown to anyone; myPlayerId is public.
  mySecretKey: sessionStorage.getItem('mySecretKey'),
  // Latest game from the server. Not saved: after a refresh the lobby and
  // game board fetch it again.
  info: null as GameInfo | null,
  // Where you can move this turn; empty when it isn't your turn
  possibleMoves: [] as PossibleMove[],

  get me(): PlayerInfo | null {
    return this.info?.players.find(player => player.id === this.myPlayerId) ?? null
  },
  get myRole(): Role | null {
    return this.me?.role ?? null
  },
  get isMyTurn(): boolean {
    return !!this.myPlayerId &&
      this.info?.currentPlayerId === this.myPlayerId &&
      this.info?.phase === 'IN_PROGRESS'
  },
})

export function rememberGame(gameId: string, myPlayerId: string, mySecretKey: string, info: GameInfo) {
  Object.assign(currentGame, { gameId, myPlayerId, mySecretKey, info })
  sessionStorage.setItem('gameId', gameId)
  sessionStorage.setItem('myPlayerId', myPlayerId)
  sessionStorage.setItem('mySecretKey', mySecretKey)
}

export function updateGameInfo(info: GameInfo) {
  currentGame.info = info
  // Last turn's moves must not stay clickable once the turn has passed
  if (!currentGame.isMyTurn) currentGame.possibleMoves = []
}

export function forgetGame() {
  Object.assign(currentGame, { gameId: null, myPlayerId: null, mySecretKey: null, info: null, possibleMoves: [] })
  sessionStorage.removeItem('gameId')
  sessionStorage.removeItem('myPlayerId')
  sessionStorage.removeItem('mySecretKey')
}

/** Tells the server you've left (best effort, since you're going either way) and forgets the game. */
export async function leaveGame() {
  const { gameId, myPlayerId, mySecretKey } = currentGame
  if (gameId && myPlayerId && mySecretKey) {
    try { await removePlayer(gameId, mySecretKey, myPlayerId) } catch { /* leaving anyway */ }
  }
  forgetGame()
}
