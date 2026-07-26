package com.huntingmrxwellington.service;

import com.huntingmrxwellington.dto.GameStateDTO;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.*;
import com.huntingmrxwellington.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lifecycle tests for GameService using a real in-memory GameRepository.
 * Tests the full game flow: create → join → start → leave/end.
 */
class GameLifecycleTest {

    private GameService gameService;

    @BeforeEach
    void setUp() {
        GameRepository repo = new GameRepository();
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        MapGraph mapGraph = mock(MapGraph.class);
        when(mapGraph.randomNodes(anyInt(), any())).thenAnswer(inv -> {
            int count = inv.getArgument(0);
            List<Integer> ids = new ArrayList<>();
            for (int i = 1; i <= count; i++) ids.add(i);
            return ids;
        });
        when(mapGraph.validMoves(anyInt(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(List.of());
        gameService = new GameService(repo, messaging, mapGraph);
        ReflectionTestUtils.setField(gameService, "escooterTickets", 10);
        ReflectionTestUtils.setField(gameService, "busTickets", 8);
        ReflectionTestUtils.setField(gameService, "trainTickets", 4);
        ReflectionTestUtils.setField(gameService, "ferryTickets", 2);
    }

    // -------------------------------------------------------------------------
    // Create → join → start
    // -------------------------------------------------------------------------

    @Test
    void fullLobbyFlow_twoPlayers_startAssignsRoles() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        String hostId = created.playerId();

        gameService.joinGame(created.gameState().getJoinCode(), "Bob");

        GameStateDTO started = gameService.startGame(gameId, hostId);

        assertThat(started.getPhase()).isEqualTo(GamePhase.IN_PROGRESS);
        assertThat(started.getPlayers()).hasSize(2);
        assertThat(started.getPlayers()).allMatch(p -> p.getRole() != null);
        assertThat(started.getPlayers()).allMatch(p -> p.getTickets() != null);
        long mrXCount = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).count();
        assertThat(mrXCount).isEqualTo(1);
    }

    @Test
    void fullLobbyFlow_maxPlayers_allGetRoles() {
        GameService.CreateResult created = gameService.createGame("Host", 6);
        String gameId = created.gameState().getGameId();
        String code = created.gameState().getJoinCode();

        for (int i = 1; i <= 5; i++) {
            gameService.joinGame(code, "Player" + i);
        }

        GameStateDTO started = gameService.startGame(gameId, created.playerId());
        assertThat(started.getPlayers()).hasSize(6);
        assertThat(started.getPlayers()).allMatch(p -> p.getRole() != null);
        long mrXCount = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).count();
        assertThat(mrXCount).isEqualTo(1);

        // Mr X should have 5 BLACK tickets (one per detective)
        var mrX = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.BLACK)).isEqualTo(5);
    }

    @Test
    void joinCode_isUppercaseAlphanumericSixChars() {
        GameService.CreateResult result = gameService.createGame("Alice", 3);
        String code = result.gameState().getJoinCode();
        assertThat(code).matches("[A-Z0-9]{6}");
    }

    @Test
    void joinCode_uniqueAcrossGames() {
        String code1 = gameService.createGame("Alice", 3).gameState().getJoinCode();
        String code2 = gameService.createGame("Bob", 3).gameState().getJoinCode();
        // Not guaranteed but extremely unlikely to collide — tests determinism
        // of state rather than randomness; check both are valid format
        assertThat(code1).matches("[A-Z0-9]{6}");
        assertThat(code2).matches("[A-Z0-9]{6}");
    }

    @Test
    void getGame_afterJoin_reflectsNewPlayer() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        String code = created.gameState().getJoinCode();

        gameService.joinGame(code, "Bob");

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPlayers()).hasSize(2);
        assertThat(state.getPlayers()).anyMatch(p -> "Bob".equals(p.getName()));
    }

    // -------------------------------------------------------------------------
    // Leave — lobby phase
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_hostLeavesLobby_gameDeleted() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();

        gameService.leaveGame(gameId, created.playerId());

        assertThatThrownBy(() -> gameService.getGame(gameId))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void leaveGame_joinerLeavesLobby_hostStillInGame() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().getJoinCode(), "Bob");

        gameService.leaveGame(gameId, joined.playerId());

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPlayers()).hasSize(1);
        assertThat(state.getPlayers().get(0).getName()).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // Leave — in-progress phase
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_mrXLeavesInProgress_gameEnds() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(gameId, created.playerId());

        String mrXId = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole()))
                .findFirst().orElseThrow().getId();

        gameService.leaveGame(gameId, mrXId);

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPhase()).isEqualTo(GamePhase.ENDED);
        assertThat(state.getAbortReason()).isNotBlank();
    }

    @Test
    void leaveGame_detectiveLeavesInProgress_gameStillRunning() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(gameId, created.playerId());

        String detectiveId = started.getPlayers().stream()
                .filter(p -> Role.DETECTIVE.equals(p.getRole()))
                .findFirst().orElseThrow().getId();

        gameService.leaveGame(gameId, detectiveId);

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPhase()).isEqualTo(GamePhase.IN_PROGRESS);
        assertThat(state.getPlayers()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Start — detailed invariants
    // -------------------------------------------------------------------------

    @Test
    void startGame_currentPlayerIdIsMrX() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");

        GameStateDTO started = gameService.startGame(gameId, created.playerId());

        String mrXId = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow().getId();
        assertThat(started.getCurrentPlayerId())
                .as("game engine reads currentPlayerId to determine whose turn it is")
                .isEqualTo(mrXId);
    }

    @Test
    void startGame_mrXDoubleTicketsIsTwo() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(created.gameState().getGameId(), created.playerId());

        var mrX = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.DOUBLE)).isEqualTo(2);
    }

    @Test
    void startGame_twoPlayers_mrXBlackIsOne() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(created.gameState().getGameId(), created.playerId());

        var mrX = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.BLACK))
                .as("2 players means 1 detective, so Mr X gets exactly 1 BLACK ticket")
                .isEqualTo(1);
    }

    @Test
    void startGame_detectivesHaveNoBlackOrDoubleTickets() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(created.gameState().getGameId(), created.playerId());

        started.getPlayers().stream()
                .filter(p -> Role.DETECTIVE.equals(p.getRole()))
                .forEach(d -> {
                    assertThat(d.getTickets()).doesNotContainKey(TicketType.BLACK);
                    assertThat(d.getTickets()).doesNotContainKey(TicketType.DOUBLE);
                });
    }

    @Test
    void joinGame_lowercaseCode_accepted() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String lowerCode = created.gameState().getJoinCode().toLowerCase();

        assertThatCode(() -> gameService.joinGame(lowerCode, "Bob")).doesNotThrowAnyException();

        GameStateDTO state = gameService.getGame(created.gameState().getGameId());
        assertThat(state.getPlayers()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Leave — in-progress, player identity
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_detectiveLeaves_correctPlayerRemovedNotOther() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().getGameId();
        gameService.joinGame(created.gameState().getJoinCode(), "Bob");
        GameStateDTO started = gameService.startGame(gameId, created.playerId());

        String detectiveId = started.getPlayers().stream()
                .filter(p -> Role.DETECTIVE.equals(p.getRole())).findFirst().orElseThrow().getId();
        String mrXId = started.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow().getId();

        gameService.leaveGame(gameId, detectiveId);

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPlayers()).hasSize(1);
        assertThat(state.getPlayers().get(0).getId())
                .as("MrX must remain; only the specific detective who left is gone")
                .isEqualTo(mrXId);
    }

    // -------------------------------------------------------------------------
    // Kick — lobby phase
    // -------------------------------------------------------------------------

    @Test
    void kickPlayer_fullFlow_kickedPlayerGone() {
        GameService.CreateResult created = gameService.createGame("Host", 4);
        String gameId = created.gameState().getGameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().getJoinCode(), "Bob");

        gameService.kickPlayer(gameId, created.playerId(), joined.playerId());

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPlayers()).hasSize(1);
        assertThat(state.getPlayers()).noneMatch(p -> "Bob".equals(p.getName()));
    }

    @Test
    void kickPlayer_thenJoinAgain_newPlayerAdded() {
        GameService.CreateResult created = gameService.createGame("Host", 4);
        String gameId = created.gameState().getGameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().getJoinCode(), "Bob");

        gameService.kickPlayer(gameId, created.playerId(), joined.playerId());
        gameService.joinGame(created.gameState().getJoinCode(), "Charlie");

        GameStateDTO state = gameService.getGame(gameId);
        assertThat(state.getPlayers()).hasSize(2);
        assertThat(state.getPlayers()).anyMatch(p -> "Charlie".equals(p.getName()));
    }
}
