package com.huntingmrxwellington.service;

import com.huntingmrxwellington.dto.GameState;
import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock SimpMessagingTemplate messaging;
    @Mock MapGraph mapGraph;

    @InjectMocks GameService gameService;

    @BeforeEach
    void injectTicketConfig() {
        ReflectionTestUtils.setField(gameService, "escooterTickets", 10);
        ReflectionTestUtils.setField(gameService, "busTickets", 8);
        ReflectionTestUtils.setField(gameService, "trainTickets", 4);
        ReflectionTestUtils.setField(gameService, "ferryTickets", 2);
        // Lenient: only some tests exercise the graph (startGame / moves)
        lenient().when(mapGraph.randomNodes(anyInt(), any())).thenAnswer(inv -> {
            int count = inv.getArgument(0);
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (int i = 1; i <= count; i++) ids.add(i);
            return ids;
        });
        lenient().when(mapGraph.validMoves(anyInt(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(java.util.List.of());
    }

    // -------------------------------------------------------------------------
    // createGame
    // -------------------------------------------------------------------------

    @Test
    void createGame_validInput_returnsHostPlayerIdAndLobbyState() {
        GameService.CreateResult result = gameService.createGame("Alice", 4);
        assertThat(result.playerId()).isNotBlank();
        assertThat(result.gameState().phase()).isEqualTo(GamePhase.LOBBY);
        assertThat(result.gameState().players()).hasSize(1);
        assertThat(result.gameState().players().get(0).name()).isEqualTo("Alice");
    }

    @Test
    void createGame_hostIsLobbyPlayer_noRoleOrTickets() {
        GameService.CreateResult result = gameService.createGame("Alice", 4);
        var player = result.gameState().players().get(0);
        assertThat(player.role()).isNull();
        assertThat(player.tickets()).isNull();
    }

    @Test
    void createGame_blankName_throwsWithGameNotCreatedMessage() {
        assertThatThrownBy(() -> gameService.createGame("  ", 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not created");
    }

    @Test
    void createGame_nullName_throwsWithGameNotCreatedMessage() {
        assertThatThrownBy(() -> gameService.createGame(null, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not created");
    }

    @Test
    void createGame_maxPlayersBelow2_throwsWithGameNotCreatedMessage() {
        assertThatThrownBy(() -> gameService.createGame("Alice", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not created");
    }

    @Test
    void createGame_maxPlayersAbove6_throwsWithGameNotCreatedMessage() {
        assertThatThrownBy(() -> gameService.createGame("Alice", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not created");
    }

    @Test
    void createGame_doesNotBroadcast() {
        gameService.createGame("Alice", 4);
        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void createGame_trimsWhitespaceName() {
        GameService.CreateResult result = gameService.createGame("  Alice  ", 3);
        assertThat(result.gameState().players().get(0).name()).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // joinGame
    // -------------------------------------------------------------------------

    @Test
    void joinGame_validCode_addsPlayerToSession() {
        seedGame(lobbySession(4));

        GameService.JoinResult result = gameService.joinGame("ABC123", "Bob");
        assertThat(result.playerId()).isNotBlank();
        assertThat(result.gameState().players()).hasSize(2);
    }

    @Test
    void joinGame_unknownCode_throwsGameNotFoundException() {
        assertThatThrownBy(() -> gameService.joinGame("XXXXXX", "Bob"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void joinGame_gameNotInLobby_throwsConflictException() {
        GameSession session = lobbySession(4);
        session.setPhase(GamePhase.IN_PROGRESS);
        seedGame(session);
        assertThatThrownBy(() -> gameService.joinGame("ABC123", "Bob"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lobby");
    }

    @Test
    void joinGame_gameFull_throwsConflictException() {
        GameSession session = lobbySession(2);
        session.getPlayers().add(new LobbyPlayer("p2", "Bob"));
        seedGame(session);
        assertThatThrownBy(() -> gameService.joinGame("ABC123", "Charlie"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("full");
    }

    @Test
    void joinGame_blankName_throws() {
        assertThatThrownBy(() -> gameService.joinGame("ABC123", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void joinGame_nullCode_throws() {
        assertThatThrownBy(() -> gameService.joinGame(null, "Bob"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joinGame_lowercaseCodeIsNormalized() {
        seedGame(lobbySession(4));
        assertThatCode(() -> gameService.joinGame("abc123", "Bob")).doesNotThrowAnyException();
    }

    @Test
    void joinGame_broadcastsSentToTopic() {
        GameSession session = lobbySession(4);
        seedGame(session);
        gameService.joinGame("ABC123", "Bob");
        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameState.class));
    }

    // -------------------------------------------------------------------------
    // startGame
    // -------------------------------------------------------------------------

    @Test
    void startGame_validHost_transitionsToInProgress() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.phase()).isEqualTo(GamePhase.IN_PROGRESS);
    }

    @Test
    void startGame_assignsExactlyOneMrX() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        long mrXCount = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).count();
        assertThat(mrXCount).isEqualTo(1);
    }

    @Test
    void startGame_allPlayersGetRoles() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.players()).allMatch(p -> p.role() != null);
    }

    @Test
    void startGame_mrXHasUnlimitedTransportTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var mrX = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.ESCOOTER)).isEqualTo(-1);
        assertThat(mrX.tickets().get(TicketType.BUS)).isEqualTo(-1);
        assertThat(mrX.tickets().get(TicketType.TRAIN)).isEqualTo(-1);
        assertThat(mrX.tickets().get(TicketType.FERRY)).isEqualTo(-1);
    }

    @Test
    void startGame_mrXBlackTicketsEqualDetectiveCount() {
        GameSession session = lobbySessionWithThreePlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var mrX = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.BLACK)).isEqualTo(2);
    }

    @Test
    void startGame_detectivesHaveFiniteTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var detective = result.players().stream()
                .filter(p -> Role.DETECTIVE.equals(p.role())).findFirst().orElseThrow();
        assertThat(detective.tickets().get(TicketType.ESCOOTER)).isEqualTo(10);
        assertThat(detective.tickets().get(TicketType.BUS)).isEqualTo(8);
        assertThat(detective.tickets().get(TicketType.TRAIN)).isEqualTo(4);
        assertThat(detective.tickets().get(TicketType.FERRY)).isEqualTo(2);
    }

    @Test
    void startGame_setsRoundOneAndMrXTurn() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.round()).isEqualTo(1);
        assertThat(result.turnPhase()).isEqualTo(TurnPhase.MR_X_TURN);
    }

    @Test
    void startGame_notHost_throwsForbiddenException() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);
        assertThatThrownBy(() -> gameService.startGame(session.getId(), "wrong-id"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("host");
    }

    @Test
    void startGame_gameNotInLobby_throwsConflictException() {
        GameSession session = lobbySessionWithTwoPlayers();
        session.setPhase(GamePhase.IN_PROGRESS);
        seedGame(session);
        assertThatThrownBy(() -> gameService.startGame(session.getId(), session.getHostPlayerId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lobby");
    }

    @Test
    void startGame_gameNotFound_throwsGameNotFoundException() {
        assertThatThrownBy(() -> gameService.startGame("no-such-game", "player-id"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void startGame_onlyOnePlayer_throws() {
        GameSession session = lobbySession(4);
        seedGame(session);
        assertThatThrownBy(() -> gameService.startGame(session.getId(), session.getHostPlayerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 players");
    }

    @Test
    void startGame_setsCurrentPlayerIdToMrX() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());

        String mrXId = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow().id();
        assertThat(result.currentPlayerId())
                .as("currentPlayerId must be MrX's ID so the game engine knows whose turn it is")
                .isEqualTo(mrXId);
    }

    @Test
    void startGame_broadcastsToTopic() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        gameService.startGame(session.getId(), session.getHostPlayerId());

        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameState.class));
    }

    @Test
    void startGame_mrXDoubleTicketsIsTwo() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());

        var mrX = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.DOUBLE))
                .as("Mr X always starts with exactly 2 DOUBLE tickets")
                .isEqualTo(2);
    }

    @Test
    void startGame_twoPlayers_mrXBlackTicketIsOne() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());

        var mrX = result.players().stream()
                .filter(p -> Role.MR_X.equals(p.role())).findFirst().orElseThrow();
        assertThat(mrX.tickets().get(TicketType.BLACK))
                .as("BLACK tickets must equal detective count (1 detective here)")
                .isEqualTo(1);
    }

    @Test
    void startGame_detectivesHaveNoBlackOrDoubleTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        seedGame(session);

        GameState result = gameService.startGame(session.getId(), session.getHostPlayerId());

        result.players().stream()
                .filter(p -> Role.DETECTIVE.equals(p.role()))
                .forEach(d -> {
                    assertThat(d.tickets()).doesNotContainKey(TicketType.BLACK);
                    assertThat(d.tickets()).doesNotContainKey(TicketType.DOUBLE);
                });
    }

    // -------------------------------------------------------------------------
    // leaveGame
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_hostLeavesLobby_deletesGame() {
        GameSession session = lobbySession(4);
        seedGame(session);
        gameService.leaveGame(session.getId(), session.getHostPlayerId());
        assertThat(games()).doesNotContainKey(session.getId());
    }

    @Test
    void leaveGame_nonHostLeavesLobby_savesUpdatedSession() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        seedGame(session);

        gameService.leaveGame(session.getId(), joinerId);

        assertThat(session.getPlayers()).hasSize(1);
    }

    @Test
    void leaveGame_mrXLeavesInProgress_setsEndedWithAbortReason() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        seedGame(session);

        gameService.leaveGame(session.getId(), mrXId);

        assertThat(session.getPhase()).isEqualTo(GamePhase.ENDED);
        assertThat(session.getAbortReason()).isNotBlank();
    }

    @Test
    void leaveGame_unknownPlayer_throwsGameNotFoundException() {
        GameSession session = lobbySession(4);
        seedGame(session);
        assertThatThrownBy(() -> gameService.leaveGame(session.getId(), "no-such-id"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void leaveGame_gameNotFound_throwsGameNotFoundException() {
        assertThatThrownBy(() -> gameService.leaveGame("no-such-game", "player-id"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void leaveGame_mrXLeaves_mrXRemovedFromPlayerList() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        seedGame(session);

        gameService.leaveGame(session.getId(), mrXId);

        assertThat(session.getPlayers())
                .as("MrX must be removed from the player list when they leave")
                .noneMatch(p -> p.getId().equals(mrXId));
    }

    @Test
    void leaveGame_mrXLeaves_broadcastsEndedState() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        seedGame(session);

        gameService.leaveGame(session.getId(), mrXId);

        // In-progress games broadcast per-player (not the shared lobby topic)
        ArgumentCaptor<GameState> stateCaptor = ArgumentCaptor.forClass(GameState.class);
        verify(messaging, atLeast(1)).convertAndSend(
                contains("/topic/games/" + session.getId() + "/players/"),
                stateCaptor.capture());
        assertThat(stateCaptor.getValue().phase()).isEqualTo(GamePhase.ENDED);
        assertThat(stateCaptor.getValue().abortReason()).isNotBlank();
    }

    @Test
    void leaveGame_detectiveLeaves_inProgress_detectiveRemovedFromList() {
        GameSession session = inProgressSession();
        String detectiveId = session.getPlayers().stream()
                .filter(p -> p instanceof DetectivePlayer).findFirst().orElseThrow().getId();
        seedGame(session);

        gameService.leaveGame(session.getId(), detectiveId);

        assertThat(session.getPlayers())
                .as("detective must be removed but game must not end")
                .noneMatch(p -> p.getId().equals(detectiveId));
        assertThat(session.getPhase()).isEqualTo(GamePhase.IN_PROGRESS);
    }

    @Test
    void leaveGame_detectiveLeaves_inProgress_broadcasts() {
        GameSession session = inProgressSession();
        String detectiveId = session.getPlayers().stream()
                .filter(p -> p instanceof DetectivePlayer).findFirst().orElseThrow().getId();
        seedGame(session);

        gameService.leaveGame(session.getId(), detectiveId);

        verify(messaging).convertAndSend(eq("/topic/games/" + session.getId()), any(GameState.class));
    }

    // -------------------------------------------------------------------------
    // kickPlayer
    // -------------------------------------------------------------------------

    @Test
    void kickPlayer_validKick_removesTargetPlayer() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        seedGame(session);

        gameService.kickPlayer(session.getId(), session.getHostPlayerId(), joinerId);

        assertThat(session.getPlayers()).noneMatch(p -> p.getId().equals(joinerId));
    }

    @Test
    void kickPlayer_notHost_throwsForbiddenException() {
        GameSession session = lobbySession(4);
        session.getPlayers().add(new LobbyPlayer("joiner", "Bob"));
        seedGame(session);
        assertThatThrownBy(() -> gameService.kickPlayer(session.getId(), "wrong-host", "joiner"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("host");
    }

    @Test
    void kickPlayer_selfKick_throwsIllegalArgumentException() {
        GameSession session = lobbySession(4);
        seedGame(session);
        assertThatThrownBy(() -> gameService.kickPlayer(
                session.getId(), session.getHostPlayerId(), session.getHostPlayerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("themselves");
    }

    @Test
    void kickPlayer_duringInProgress_throwsConflictException() {
        GameSession session = inProgressSession();
        seedGame(session);
        String detectiveId = session.getPlayers().stream()
                .filter(p -> p instanceof DetectivePlayer).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> gameService.kickPlayer(
                session.getId(), session.getHostPlayerId(), detectiveId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lobby");
    }

    @Test
    void kickPlayer_unknownTarget_throwsGameNotFoundException() {
        GameSession session = lobbySession(4);
        seedGame(session);
        assertThatThrownBy(() -> gameService.kickPlayer(
                session.getId(), session.getHostPlayerId(), "no-such-player"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void kickPlayer_broadcastsSentAfterKick() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        seedGame(session);

        gameService.kickPlayer(session.getId(), session.getHostPlayerId(), joinerId);

        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameState.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** GameService keeps sessions in a private in-memory map (no repository
     *  interface to mock) — tests seed/inspect fixture sessions directly. */
    @SuppressWarnings("unchecked")
    private Map<String, GameSession> games() {
        return (Map<String, GameSession>) ReflectionTestUtils.getField(gameService, "games");
    }

    private void seedGame(GameSession session) {
        games().put(session.getId(), session);
    }

    private GameSession lobbySession(int maxPlayers) {
        GameSession s = new GameSession();
        s.setId("game-id");
        s.setJoinCode("ABC123");
        s.setPhase(GamePhase.LOBBY);
        s.setMaxPlayers(maxPlayers);
        s.setHostPlayerId("host-id");
        s.getPlayers().add(new LobbyPlayer("host-id", "Host"));
        return s;
    }

    private GameSession lobbySessionWithTwoPlayers() {
        GameSession s = lobbySession(4);
        s.getPlayers().add(new LobbyPlayer("player-2", "Bob"));
        return s;
    }

    private GameSession lobbySessionWithThreePlayers() {
        GameSession s = lobbySessionWithTwoPlayers();
        s.getPlayers().add(new LobbyPlayer("player-3", "Charlie"));
        return s;
    }

    private GameSession inProgressSession() {
        GameSession s = new GameSession();
        s.setId("game-id");
        s.setJoinCode("ABC123");
        s.setPhase(GamePhase.IN_PROGRESS);
        s.setMaxPlayers(4);
        s.setHostPlayerId("mrx-id");
        s.setRound(1);
        s.setTurnPhase(TurnPhase.MR_X_TURN);
        s.getPlayers().add(new MrXPlayer("mrx-id", "Mr X", 1));
        s.getPlayers().add(new DetectivePlayer("det-id", "Alice", 10, 8, 4, 2));
        return s;
    }
}
