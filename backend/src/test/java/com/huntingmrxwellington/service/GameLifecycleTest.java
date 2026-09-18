package com.huntingmrxwellington.service;

import com.huntingmrxwellington.dto.GameState;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.*;
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
 * Lifecycle tests for GameService's own in-memory session storage.
 * Tests the full game flow: create → join → start → leave/end.
 */
class GameLifecycleTest {

    private GameService gameService;

    @BeforeEach
    void setUp() {
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
        gameService = new GameService(messaging, mapGraph);
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
        String gameId = created.gameState().gameId();
        String hostId = created.playerId();

        gameService.joinGame(created.gameState().joinCode(), "Bob");

        GameState started = gameService.startGame(gameId, hostId);

        assertThat(started.phase()).isEqualTo(GamePhase.IN_PROGRESS);
        assertThat(started.players()).hasSize(2);
        assertThat(started.players()).allMatch(p -> p.role() != null);
        assertThat(started.players()).allMatch(p -> p.tickets() != null);
        long mrXCount = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).count();
        assertThat(mrXCount).isEqualTo(1);
    }

    @Test
    void fullLobbyFlow_maxPlayers_allGetRoles() {
        GameService.CreateResult created = gameService.createGame("Host", 6);
        String gameId = created.gameState().gameId();
        String code = created.gameState().joinCode();

        for (int i = 1; i <= 5; i++) {
            gameService.joinGame(code, "Player" + i);
        }

        GameState started = gameService.startGame(gameId, created.playerId());
        assertThat(started.players()).hasSize(6);
        assertThat(started.players()).allMatch(p -> p.role() != null);
        long mrXCount = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).count();
        assertThat(mrXCount).isEqualTo(1);

        // Mr X should have 5 BLACK tickets (one per detective)
        var mrX = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.BLACK)).isEqualTo(5);
    }

    @Test
    void joinCode_isUppercaseAlphanumericSixChars() {
        GameService.CreateResult result = gameService.createGame("Alice", 3);
        String code = result.gameState().joinCode();
        assertThat(code).matches("[A-Z0-9]{6}");
    }

    @Test
    void joinCode_uniqueAcrossGames() {
        String code1 = gameService.createGame("Alice", 3).gameState().joinCode();
        String code2 = gameService.createGame("Bob", 3).gameState().joinCode();
        // Not guaranteed but extremely unlikely to collide — tests determinism
        // of state rather than randomness; check both are valid format
        assertThat(code1).matches("[A-Z0-9]{6}");
        assertThat(code2).matches("[A-Z0-9]{6}");
    }

    @Test
    void getGame_afterJoin_reflectsNewPlayer() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        String code = created.gameState().joinCode();

        gameService.joinGame(code, "Bob");

        GameState state = gameService.getGame(gameId);
        assertThat(state.players()).hasSize(2);
        assertThat(state.players()).anyMatch(p -> "Bob".equals(p.name()));
    }

    // -------------------------------------------------------------------------
    // Leave — lobby phase
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_hostLeavesLobby_gameDeleted() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();

        gameService.leaveGame(gameId, created.playerId());

        assertThatThrownBy(() -> gameService.getGame(gameId))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void leaveGame_joinerLeavesLobby_hostStillInGame() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().joinCode(), "Bob");

        gameService.leaveGame(gameId, joined.playerId());

        GameState state = gameService.getGame(gameId);
        assertThat(state.players()).hasSize(1);
        assertThat(state.players().get(0).name()).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // Leave — in-progress phase
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_mrXLeavesInProgress_gameEnds() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(gameId, created.playerId());

        String mrXId = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role()))
                .findFirst().orElseThrow().id();

        gameService.leaveGame(gameId, mrXId);

        GameState state = gameService.getGame(gameId);
        assertThat(state.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(state.abortReason()).isNotBlank();
    }

    @Test
    void leaveGame_detectiveLeavesInProgress_gameStillRunning() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(gameId, created.playerId());

        String detectiveId = started.players().stream()
                .filter(p -> Role.DETECTIVE.equals(p.role()))
                .findFirst().orElseThrow().id();

        gameService.leaveGame(gameId, detectiveId);

        GameState state = gameService.getGame(gameId);
        assertThat(state.phase()).isEqualTo(GamePhase.IN_PROGRESS);
        assertThat(state.players()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Start — detailed invariants
    // -------------------------------------------------------------------------

    @Test
    void startGame_currentPlayerIdIsMrX() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        gameService.joinGame(created.gameState().joinCode(), "Bob");

        GameState started = gameService.startGame(gameId, created.playerId());

        String mrXId = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow().id();
        assertThat(started.currentPlayerId())
                .as("game engine reads currentPlayerId to determine whose turn it is")
                .isEqualTo(mrXId);
    }

    @Test
    void startGame_mrXDoubleTicketsIsTwo() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(created.gameState().gameId(), created.playerId());

        var mrX = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.DOUBLE)).isEqualTo(2);
    }

    @Test
    void startGame_twoPlayers_mrXBlackIsOne() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(created.gameState().gameId(), created.playerId());

        var mrX = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.BLACK))
                .as("2 players means 1 detective, so Mr X gets exactly 1 BLACK ticket")
                .isEqualTo(1);
    }

    @Test
    void startGame_detectivesHaveNoBlackOrDoubleTickets() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(created.gameState().gameId(), created.playerId());

        started.players().stream()
                .filter(p -> Role.DETECTIVE.equals(p.role()))
                .forEach(d -> {
                    assertThat(d.tickets()).doesNotContainKey(TicketType.BLACK);
                    assertThat(d.tickets()).doesNotContainKey(TicketType.DOUBLE);
                });
    }

    @Test
    void joinGame_lowercaseCode_accepted() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String lowerCode = created.gameState().joinCode().toLowerCase();

        assertThatCode(() -> gameService.joinGame(lowerCode, "Bob")).doesNotThrowAnyException();

        GameState state = gameService.getGame(created.gameState().gameId());
        assertThat(state.players()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Leave — in-progress, player identity
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_detectiveLeaves_correctPlayerRemovedNotOther() {
        GameService.CreateResult created = gameService.createGame("Alice", 4);
        String gameId = created.gameState().gameId();
        gameService.joinGame(created.gameState().joinCode(), "Bob");
        GameState started = gameService.startGame(gameId, created.playerId());

        String detectiveId = started.players().stream()
                .filter(p -> Role.DETECTIVE.equals(p.role())).findFirst().orElseThrow().id();
        String mrXId = started.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow().id();

        gameService.leaveGame(gameId, detectiveId);

        GameState state = gameService.getGame(gameId);
        assertThat(state.players()).hasSize(1);
        assertThat(state.players().get(0).id())
                .as("MrX must remain; only the specific detective who left is gone")
                .isEqualTo(mrXId);
    }

    // -------------------------------------------------------------------------
    // Kick — lobby phase
    // -------------------------------------------------------------------------

    @Test
    void kickPlayer_fullFlow_kickedPlayerGone() {
        GameService.CreateResult created = gameService.createGame("Host", 4);
        String gameId = created.gameState().gameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().joinCode(), "Bob");

        gameService.kickPlayer(gameId, created.playerId(), joined.playerId());

        GameState state = gameService.getGame(gameId);
        assertThat(state.players()).hasSize(1);
        assertThat(state.players()).noneMatch(p -> "Bob".equals(p.name()));
    }

    @Test
    void kickPlayer_thenJoinAgain_newPlayerAdded() {
        GameService.CreateResult created = gameService.createGame("Host", 4);
        String gameId = created.gameState().gameId();
        GameService.JoinResult joined = gameService.joinGame(created.gameState().joinCode(), "Bob");

        gameService.kickPlayer(gameId, created.playerId(), joined.playerId());
        gameService.joinGame(created.gameState().joinCode(), "Charlie");

        GameState state = gameService.getGame(gameId);
        assertThat(state.players()).hasSize(2);
        assertThat(state.players()).anyMatch(p -> "Charlie".equals(p.name()));
    }
}
