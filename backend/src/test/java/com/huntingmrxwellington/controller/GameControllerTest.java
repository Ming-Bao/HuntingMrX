package com.huntingmrxwellington.controller;

import tools.jackson.databind.ObjectMapper;
import com.huntingmrxwellington.dto.GameStateDTO;
import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.GamePhase;
import com.huntingmrxwellington.model.TurnPhase;
import com.huntingmrxwellington.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class GameControllerTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper json;

    @MockitoBean SimpMessagingTemplate messaging;
    @MockitoBean GameService gameService;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    // -------------------------------------------------------------------------
    // POST /api/games/create — create game
    // -------------------------------------------------------------------------

    @Test
    void createGame_validRequest_returns201WithPlayerIdAndGameState() throws Exception {
        var result = new GameService.CreateResult("player-1", lobbyState("game-1"));
        when(gameService.createGame(eq("Alice"), eq(4))).thenReturn(result);

        mvc.perform(post("/api/games/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("hostName", "Alice", "maxPlayers", 4))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").value("player-1"))
                .andExpect(jsonPath("$.gameState.phase").value("LOBBY"));
    }

    @Test
    void createGame_serviceThrows_returns400WithErrorField() throws Exception {
        when(gameService.createGame(any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Game not created"));

        mvc.perform(post("/api/games/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("hostName", "", "maxPlayers", 4))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Game not created"));
    }

    // -------------------------------------------------------------------------
    // POST /api/games/join — join game
    // -------------------------------------------------------------------------

    @Test
    void joinGame_validRequest_returns200WithPlayerIdAndGameState() throws Exception {
        var result = new GameService.JoinResult("player-2", lobbyState("game-1"));
        when(gameService.joinGame(eq("ABC123"), eq("Bob"))).thenReturn(result);

        mvc.perform(post("/api/games/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("joinCode", "ABC123", "playerName", "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("player-2"));
    }

    @Test
    void joinGame_unknownCode_returns404() throws Exception {
        when(gameService.joinGame(any(), any()))
                .thenThrow(new GameNotFoundException("Game not found"));

        mvc.perform(post("/api/games/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("joinCode", "XXXXXX", "playerName", "Bob"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    @Test
    void joinGame_gameFull_returns409() throws Exception {
        when(gameService.joinGame(any(), any()))
                .thenThrow(new ConflictException("Game is full"));

        mvc.perform(post("/api/games/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("joinCode", "ABC123", "playerName", "Bob"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Game is full"));
    }

    @Test
    void joinGame_wrongPhase_returns409() throws Exception {
        when(gameService.joinGame(any(), any()))
                .thenThrow(new ConflictException("Game is not in the lobby phase"));

        mvc.perform(post("/api/games/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("joinCode", "ABC123", "playerName", "Bob"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void joinGame_blankName_returns400() throws Exception {
        when(gameService.joinGame(any(), any()))
                .thenThrow(new IllegalArgumentException("Player name is required"));

        mvc.perform(post("/api/games/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("joinCode", "ABC123", "playerName", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // GET /api/games/{id} — get game state
    // -------------------------------------------------------------------------

    @Test
    void getGame_knownId_returns200WithGameState() throws Exception {
        when(gameService.getGame(eq("game-1"), isNull())).thenReturn(lobbyState("game-1"));

        mvc.perform(get("/api/games/game-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("game-1"))
                .andExpect(jsonPath("$.phase").value("LOBBY"));
    }

    @Test
    void getGame_unknownId_returns404() throws Exception {
        when(gameService.getGame(eq("unknown"), isNull()))
                .thenThrow(new GameNotFoundException("Game not found"));

        mvc.perform(get("/api/games/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    // -------------------------------------------------------------------------
    // POST /api/games/{id}/start — start game
    // -------------------------------------------------------------------------

    @Test
    void startGame_validHost_returns200WithInProgressState() throws Exception {
        when(gameService.startGame(eq("game-1"), eq("player-1"))).thenReturn(inProgressState("game-1"));

        mvc.perform(post("/api/games/game-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("playerId", "player-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("IN_PROGRESS"));
    }

    @Test
    void startGame_notHost_returns403() throws Exception {
        when(gameService.startGame(any(), any()))
                .thenThrow(new ForbiddenException("Only the host can start the game"));

        mvc.perform(post("/api/games/game-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("playerId", "wrong-id"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only the host can start the game"));
    }

    @Test
    void startGame_gameNotFound_returns404() throws Exception {
        when(gameService.startGame(any(), any()))
                .thenThrow(new GameNotFoundException("Game not found"));

        mvc.perform(post("/api/games/unknown/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("playerId", "player-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    @Test
    void startGame_wrongPhase_returns409() throws Exception {
        when(gameService.startGame(any(), any()))
                .thenThrow(new ConflictException("Game is not in the lobby phase"));

        mvc.perform(post("/api/games/game-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("playerId", "player-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void startGame_notEnoughPlayers_returns400() throws Exception {
        when(gameService.startGame(any(), any()))
                .thenThrow(new IllegalArgumentException("Need at least 2 players to start"));

        mvc.perform(post("/api/games/game-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("playerId", "player-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // DELETE /api/games/{id}/players/{targetPlayerId} — leave game
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_validPlayer_returns204() throws Exception {
        doNothing().when(gameService).leaveGame("game-1", "player-1");

        mvc.perform(delete("/api/games/game-1/players/player-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void leaveGame_unknownPlayer_returns404() throws Exception {
        doThrow(new GameNotFoundException("Player not in game"))
                .when(gameService).leaveGame(any(), any());

        mvc.perform(delete("/api/games/game-1/players/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Player not in game"));
    }

    @Test
    void leaveGame_gameNotFound_returns404() throws Exception {
        doThrow(new GameNotFoundException("Game not found"))
                .when(gameService).leaveGame(any(), any());

        mvc.perform(delete("/api/games/unknown/players/player-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/games/{id}/players/{targetPlayerId} — kick player (requesterId != targetPlayerId)
    // -------------------------------------------------------------------------

    @Test
    void kickPlayer_validRequest_returns204() throws Exception {
        doNothing().when(gameService).kickPlayer("game-1", "host-id", "target-id");

        mvc.perform(delete("/api/games/game-1/players/target-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requesterId", "host-id"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void kickPlayer_notHost_returns403() throws Exception {
        doThrow(new ForbiddenException("Only the host can kick players"))
                .when(gameService).kickPlayer(any(), any(), any());

        mvc.perform(delete("/api/games/game-1/players/target-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requesterId", "wrong-id"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only the host can kick players"));
    }

    @Test
    void kickPlayer_wrongPhase_returns409() throws Exception {
        doThrow(new ConflictException("Players can only be kicked during the lobby"))
                .when(gameService).kickPlayer(any(), any(), any());

        mvc.perform(delete("/api/games/game-1/players/target-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requesterId", "host-id"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void kickPlayer_playerNotFound_returns404() throws Exception {
        doThrow(new GameNotFoundException("Game or player not found"))
                .when(gameService).kickPlayer(any(), any(), any());

        mvc.perform(delete("/api/games/game-1/players/no-such-player")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("requesterId", "host-id"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game or player not found"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GameStateDTO lobbyState(String gameId) {
        GameStateDTO dto = new GameStateDTO();
        dto.setGameId(gameId);
        dto.setJoinCode("ABC123");
        dto.setPhase(GamePhase.LOBBY);
        dto.setMaxPlayers(4);
        dto.setPlayers(List.of());
        dto.setRound(0);
        return dto;
    }

    private GameStateDTO inProgressState(String gameId) {
        GameStateDTO dto = new GameStateDTO();
        dto.setGameId(gameId);
        dto.setJoinCode("ABC123");
        dto.setPhase(GamePhase.IN_PROGRESS);
        dto.setMaxPlayers(4);
        dto.setPlayers(List.of());
        dto.setRound(1);
        dto.setTurnPhase(TurnPhase.MR_X_TURN);
        return dto;
    }
}
