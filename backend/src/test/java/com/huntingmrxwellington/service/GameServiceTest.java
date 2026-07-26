package com.huntingmrxwellington.service;

import com.huntingmrxwellington.dto.GameStateDTO;
import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.*;
import com.huntingmrxwellington.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock GameRepository gameRepository;
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
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        GameService.CreateResult result = gameService.createGame("Alice", 4);
        assertThat(result.playerId()).isNotBlank();
        assertThat(result.gameState().getPhase()).isEqualTo(GamePhase.LOBBY);
        assertThat(result.gameState().getPlayers()).hasSize(1);
        assertThat(result.gameState().getPlayers().get(0).getName()).isEqualTo("Alice");
    }

    @Test
    void createGame_hostIsLobbyPlayer_noRoleOrTickets() {
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        GameService.CreateResult result = gameService.createGame("Alice", 4);
        var player = result.gameState().getPlayers().get(0);
        assertThat(player.getRole()).isNull();
        assertThat(player.getTickets()).isNull();
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
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        gameService.createGame("Alice", 4);
        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void createGame_trimsWhitespaceName() {
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        GameService.CreateResult result = gameService.createGame("  Alice  ", 3);
        assertThat(result.gameState().getPlayers().get(0).getName()).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // joinGame
    // -------------------------------------------------------------------------

    @Test
    void joinGame_validCode_addsPlayerToSession() {
        GameSession session = lobbySession(4);
        when(gameRepository.findByJoinCode("ABC123")).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameService.JoinResult result = gameService.joinGame("ABC123", "Bob");
        assertThat(result.playerId()).isNotBlank();
        assertThat(result.gameState().getPlayers()).hasSize(2);
    }

    @Test
    void joinGame_unknownCode_throwsGameNotFoundException() {
        when(gameRepository.findByJoinCode(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gameService.joinGame("XXXXXX", "Bob"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void joinGame_gameNotInLobby_throwsConflictException() {
        GameSession session = lobbySession(4);
        session.setPhase(GamePhase.IN_PROGRESS);
        when(gameRepository.findByJoinCode(any())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.joinGame("ABC123", "Bob"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lobby");
    }

    @Test
    void joinGame_gameFull_throwsConflictException() {
        GameSession session = lobbySession(2);
        session.getPlayers().add(new LobbyPlayer("p2", "Bob"));
        when(gameRepository.findByJoinCode(any())).thenReturn(Optional.of(session));
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
        GameSession session = lobbySession(4);
        when(gameRepository.findByJoinCode("ABC123")).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> gameService.joinGame("abc123", "Bob")).doesNotThrowAnyException();
    }

    @Test
    void joinGame_broadcastsSentToTopic() {
        GameSession session = lobbySession(4);
        when(gameRepository.findByJoinCode("ABC123")).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        gameService.joinGame("ABC123", "Bob");
        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameStateDTO.class));
    }

    // -------------------------------------------------------------------------
    // startGame
    // -------------------------------------------------------------------------

    @Test
    void startGame_validHost_transitionsToInProgress() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.getPhase()).isEqualTo(GamePhase.IN_PROGRESS);
    }

    @Test
    void startGame_assignsExactlyOneMrX() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        long mrXCount = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).count();
        assertThat(mrXCount).isEqualTo(1);
    }

    @Test
    void startGame_allPlayersGetRoles() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.getPlayers()).allMatch(p -> p.getRole() != null);
    }

    @Test
    void startGame_mrXHasUnlimitedTransportTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var mrX = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.ESCOOTER)).isEqualTo(-1);
        assertThat(mrX.getTickets().get(TicketType.BUS)).isEqualTo(-1);
        assertThat(mrX.getTickets().get(TicketType.TRAIN)).isEqualTo(-1);
        assertThat(mrX.getTickets().get(TicketType.FERRY)).isEqualTo(-1);
    }

    @Test
    void startGame_mrXBlackTicketsEqualDetectiveCount() {
        GameSession session = lobbySessionWithThreePlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var mrX = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.BLACK)).isEqualTo(2);
    }

    @Test
    void startGame_detectivesHaveFiniteTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        var detective = result.getPlayers().stream()
                .filter(p -> Role.DETECTIVE.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(detective.getTickets().get(TicketType.ESCOOTER)).isEqualTo(10);
        assertThat(detective.getTickets().get(TicketType.BUS)).isEqualTo(8);
        assertThat(detective.getTickets().get(TicketType.TRAIN)).isEqualTo(4);
        assertThat(detective.getTickets().get(TicketType.FERRY)).isEqualTo(2);
    }

    @Test
    void startGame_setsRoundOneAndMrXTurn() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());
        assertThat(result.getRound()).isEqualTo(1);
        assertThat(result.getTurnPhase()).isEqualTo(TurnPhase.MR_X_TURN);
    }

    @Test
    void startGame_notHost_throwsForbiddenException() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.startGame(session.getId(), "wrong-id"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("host");
    }

    @Test
    void startGame_gameNotInLobby_throwsConflictException() {
        GameSession session = lobbySessionWithTwoPlayers();
        session.setPhase(GamePhase.IN_PROGRESS);
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.startGame(session.getId(), session.getHostPlayerId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lobby");
    }

    @Test
    void startGame_gameNotFound_throwsGameNotFoundException() {
        when(gameRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gameService.startGame("no-such-game", "player-id"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void startGame_onlyOnePlayer_throws() {
        GameSession session = lobbySession(4);
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.startGame(session.getId(), session.getHostPlayerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 players");
    }

    @Test
    void startGame_setsCurrentPlayerIdToMrX() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());

        String mrXId = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow().getId();
        assertThat(result.getCurrentPlayerId())
                .as("currentPlayerId must be MrX's ID so the game engine knows whose turn it is")
                .isEqualTo(mrXId);
    }

    @Test
    void startGame_broadcastsToTopic() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.startGame(session.getId(), session.getHostPlayerId());

        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameStateDTO.class));
    }

    @Test
    void startGame_mrXDoubleTicketsIsTwo() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());

        var mrX = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.DOUBLE))
                .as("Mr X always starts with exactly 2 DOUBLE tickets")
                .isEqualTo(2);
    }

    @Test
    void startGame_twoPlayers_mrXBlackTicketIsOne() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());

        var mrX = result.getPlayers().stream()
                .filter(p -> Role.MR_X.equals(p.getRole())).findFirst().orElseThrow();
        assertThat(mrX.getTickets().get(TicketType.BLACK))
                .as("BLACK tickets must equal detective count (1 detective here)")
                .isEqualTo(1);
    }

    @Test
    void startGame_detectivesHaveNoBlackOrDoubleTickets() {
        GameSession session = lobbySessionWithTwoPlayers();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        GameStateDTO result = gameService.startGame(session.getId(), session.getHostPlayerId());

        result.getPlayers().stream()
                .filter(p -> Role.DETECTIVE.equals(p.getRole()))
                .forEach(d -> {
                    assertThat(d.getTickets()).doesNotContainKey(TicketType.BLACK);
                    assertThat(d.getTickets()).doesNotContainKey(TicketType.DOUBLE);
                });
    }

    // -------------------------------------------------------------------------
    // leaveGame
    // -------------------------------------------------------------------------

    @Test
    void leaveGame_hostLeavesLobby_deletesGame() {
        GameSession session = lobbySession(4);
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        gameService.leaveGame(session.getId(), session.getHostPlayerId());
        verify(gameRepository).delete(session.getId());
    }

    @Test
    void leaveGame_nonHostLeavesLobby_savesUpdatedSession() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), joinerId);

        ArgumentCaptor<GameSession> captor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getPlayers()).hasSize(1);
    }

    @Test
    void leaveGame_mrXLeavesInProgress_setsEndedWithAbortReason() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), mrXId);

        ArgumentCaptor<GameSession> captor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getPhase()).isEqualTo(GamePhase.ENDED);
        assertThat(captor.getValue().getAbortReason()).isNotBlank();
    }

    @Test
    void leaveGame_unknownPlayer_throwsGameNotFoundException() {
        GameSession session = lobbySession(4);
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.leaveGame(session.getId(), "no-such-id"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void leaveGame_gameNotFound_throwsGameNotFoundException() {
        when(gameRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gameService.leaveGame("no-such-game", "player-id"))
                .isInstanceOf(GameNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void leaveGame_mrXLeaves_mrXRemovedFromPlayerList() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), mrXId);

        ArgumentCaptor<GameSession> captor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getPlayers())
                .as("MrX must be removed from the player list when they leave")
                .noneMatch(p -> p.getId().equals(mrXId));
    }

    @Test
    void leaveGame_mrXLeaves_broadcastsEndedState() {
        GameSession session = inProgressSession();
        String mrXId = session.getPlayers().stream()
                .filter(p -> p instanceof MrXPlayer).findFirst().orElseThrow().getId();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), mrXId);

        // In-progress games broadcast per-player (not the shared lobby topic)
        ArgumentCaptor<GameStateDTO> stateCaptor = ArgumentCaptor.forClass(GameStateDTO.class);
        verify(messaging, atLeast(1)).convertAndSend(
                contains("/topic/games/" + session.getId() + "/players/"),
                stateCaptor.capture());
        assertThat(stateCaptor.getValue().getPhase()).isEqualTo(GamePhase.ENDED);
        assertThat(stateCaptor.getValue().getAbortReason()).isNotBlank();
    }

    @Test
    void leaveGame_detectiveLeaves_inProgress_detectiveRemovedFromList() {
        GameSession session = inProgressSession();
        String detectiveId = session.getPlayers().stream()
                .filter(p -> p instanceof DetectivePlayer).findFirst().orElseThrow().getId();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), detectiveId);

        ArgumentCaptor<GameSession> captor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getPlayers())
                .as("detective must be removed but game must not end")
                .noneMatch(p -> p.getId().equals(detectiveId));
        assertThat(captor.getValue().getPhase()).isEqualTo(GamePhase.IN_PROGRESS);
    }

    @Test
    void leaveGame_detectiveLeaves_inProgress_broadcasts() {
        GameSession session = inProgressSession();
        String detectiveId = session.getPlayers().stream()
                .filter(p -> p instanceof DetectivePlayer).findFirst().orElseThrow().getId();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.leaveGame(session.getId(), detectiveId);

        verify(messaging).convertAndSend(eq("/topic/games/" + session.getId()), any(GameStateDTO.class));
    }

    // -------------------------------------------------------------------------
    // kickPlayer
    // -------------------------------------------------------------------------

    @Test
    void kickPlayer_validKick_removesTargetPlayer() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.kickPlayer(session.getId(), session.getHostPlayerId(), joinerId);

        ArgumentCaptor<GameSession> captor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getPlayers())
                .noneMatch(p -> p.getId().equals(joinerId));
    }

    @Test
    void kickPlayer_notHost_throwsForbiddenException() {
        GameSession session = lobbySession(4);
        session.getPlayers().add(new LobbyPlayer("joiner", "Bob"));
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.kickPlayer(session.getId(), "wrong-host", "joiner"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("host");
    }

    @Test
    void kickPlayer_selfKick_throwsIllegalArgumentException() {
        GameSession session = lobbySession(4);
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.kickPlayer(
                session.getId(), session.getHostPlayerId(), session.getHostPlayerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("themselves");
    }

    @Test
    void kickPlayer_duringInProgress_throwsConflictException() {
        GameSession session = inProgressSession();
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
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
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> gameService.kickPlayer(
                session.getId(), session.getHostPlayerId(), "no-such-player"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void kickPlayer_broadcastsSentAfterKick() {
        GameSession session = lobbySession(4);
        String joinerId = "joiner-id";
        session.getPlayers().add(new LobbyPlayer(joinerId, "Bob"));
        when(gameRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(gameRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        gameService.kickPlayer(session.getId(), session.getHostPlayerId(), joinerId);

        verify(messaging).convertAndSend(
                eq("/topic/games/" + session.getId()), any(GameStateDTO.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
