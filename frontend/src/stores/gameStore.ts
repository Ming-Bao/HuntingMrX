import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { GameStateDTO, ValidMoveDTO } from '../types/game'

export const useGameStore = defineStore('game', () => {
  const gameId   = ref<string | null>(sessionStorage.getItem('gameId'))
  const playerId = ref<string | null>(sessionStorage.getItem('playerId'))
  // Secret proof of identity for acting-as-player API calls and the private STOMP
  // topics. Never shown to anyone else; playerId is the public id.
  const playerToken = ref<string | null>(sessionStorage.getItem('playerToken'))
  const gameState  = ref<GameStateDTO | null>(null)
  const validMoves = ref<ValidMoveDTO[]>([])

  function setGame(newGameId: string, newPlayerId: string, newPlayerToken: string, state: GameStateDTO) {
    gameId.value      = newGameId
    playerId.value    = newPlayerId
    playerToken.value = newPlayerToken
    gameState.value = state
    sessionStorage.setItem('gameId', newGameId)
    sessionStorage.setItem('playerId', newPlayerId)
    sessionStorage.setItem('playerToken', newPlayerToken)
  }

  function updateGameState(state: GameStateDTO) {
    gameState.value = state
    // Clear valid moves when it stops being our turn
    if (state.currentPlayerId !== playerId.value) {
      validMoves.value = []
    }
  }

  function setValidMoves(moves: ValidMoveDTO[]) {
    validMoves.value = moves
  }

  function clearGame() {
    gameId.value    = null
    playerId.value  = null
    playerToken.value = null
    gameState.value = null
    validMoves.value = []
    sessionStorage.removeItem('gameId')
    sessionStorage.removeItem('playerId')
    sessionStorage.removeItem('playerToken')
  }

  const isMyTurn = computed(() =>
    !!playerId.value &&
    gameState.value?.currentPlayerId === playerId.value &&
    gameState.value?.phase === 'IN_PROGRESS'
  )

  const myPlayer = computed(() =>
    gameState.value?.players.find(p => p.id === playerId.value) ?? null
  )

  const myRole = computed(() => myPlayer.value?.role ?? null)

  const isMrX = computed(() => myRole.value === 'MR_X')

  return { gameId, playerId, playerToken, gameState, validMoves, setGame, updateGameState, setValidMoves, clearGame, isMyTurn, myPlayer, myRole, isMrX }
})
