package com.huntingmrxwellington.service;

import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.game.GamePhase;
import com.huntingmrxwellington.game.GameState;
import com.huntingmrxwellington.game.PlayerView;
import com.huntingmrxwellington.game.TestMaps;
import com.huntingmrxwellington.game.Winner;
import com.huntingmrxwellington.service.GameService.JoinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/** Complete games through GameService, one for each way a game can end. With NO_SHUFFLE the host
 *  is Mr X on node 1 and each later joiner a detective on node 2, 3, ... of TestMaps.small(). */
@ExtendWith(MockitoExtension.class)
class GameLifecycleTest {

    @Mock SimpMessagingTemplate messaging;
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    GameService service;
    String gameId;
    List<JoinResponse> players;   // join order; players.get(0) is Mr X

    @BeforeEach
    void setUp() {
        service = new GameService(messaging, TestMaps.small(), ServiceFixtures.SETTINGS,
                ServiceFixtures.NO_SHUFFLE, () -> now);
    }

    void startGame(int playerCount) {
        JoinResponse host = service.createGame("Mr X", playerCount);
        gameId = host.gameState().gameId();
        players = new ArrayList<>(List.of(host));
        for (int i = 1; i < playerCount; i++)
            players.add(service.joinGame(host.gameState().joinCode(), "Det" + i));
        service.startGame(gameId, host.playerToken());
    }

    GameState move(int player, int to, String ticket) {
        return service.submitMove(gameId, players.get(player).playerToken(), to, ticket);
    }

    GameState state() {
        return service.getGame(gameId, null);
    }

    @Test
    void theDetectivesCatchMrX() {
        startGame(2);                          // Mr X on 1, detective on 2
        move(0, 5, "FERRY");
        move(1, 3, "ESCOOTER");
        move(0, 4, "BUS");                     // round 2: Mr X steps next to the detective
        GameState end = move(1, 4, "TRAIN");
        assertThat(end.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(end.winner()).isEqualTo(Winner.DETECTIVES);
    }

    @Test
    void mrXBoxedInLoses() {   // B1
        startGame(3);                          // Mr X on 1, detectives on 2 and 3
        move(0, 5, "FERRY");
        move(1, 1, "BUS");
        GameState end = move(2, 4, "TRAIN");   // both of Mr X's exits (1 and 4) are now held
        assertThat(end.winner()).isEqualTo(Winner.DETECTIVES);
        assertThat(end.round()).isEqualTo(2);
    }

    @Test
    void mrXSurvivesAllTwentyFourRounds() {
        startGame(2);                          // Mr X shuttles 1 <-> 5 by ferry, the detective 2 <-> 6 by bus
        for (int round = 1; round <= 24; round++) {
            move(0, round % 2 == 1 ? 5 : 1, "FERRY");
            move(1, round % 2 == 1 ? 6 : 2, "BUS");
        }
        assertThat(state().winner()).isEqualTo(Winner.MR_X);
        assertThat(state().mrXLog()).hasSize(24);
    }

    @Test
    void aPlayerLeavingEndsTheGameForEveryone() {   // B3
        startGame(3);
        move(0, 5, "FERRY");                   // detective 1's turn
        clearInvocations(messaging);
        service.removePlayer(gameId, players.get(1).playerToken(), players.get(1).playerId());
        GameState forMrX = service.getGame(gameId, players.get(0).playerToken());
        assertThat(forMrX.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(forMrX.abortReason()).isEqualTo("Det1 has left the game");
        verify(messaging).convertAndSend(eq("/topic/games/" + gameId + "/players/" + players.get(2).playerToken()),
                any(Object.class));
    }

    @Test
    void theHostLeavingTheLobbyClosesItForEveryone() {   // B6
        JoinResponse host = service.createGame("Host", 4);
        JoinResponse guest = service.joinGame(host.gameState().joinCode(), "Guest");
        String id = host.gameState().gameId();
        service.removePlayer(id, host.playerToken(), host.playerId());
        GameState forGuest = service.getGame(id, guest.playerToken());
        assertThat(forGuest.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(forGuest.abortReason()).isEqualTo("The host left the game");
    }

    @Test
    void theLastPlayerLeavingDeletesTheGame() {
        JoinResponse host = service.createGame("Host", 4);
        String id = host.gameState().gameId();
        service.removePlayer(id, host.playerToken(), host.playerId());
        assertThatThrownBy(() -> service.getGame(id, null)).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void aKickedPlayersPollShowsThemGone() {
        JoinResponse host = service.createGame("Host", 4);
        JoinResponse guest = service.joinGame(host.gameState().joinCode(), "Guest");
        String id = host.gameState().gameId();
        service.removePlayer(id, host.playerToken(), guest.playerId());
        GameState forGuest = service.getGame(id, guest.playerToken());   // token no longer valid: public view
        assertThat(forGuest.players()).extracting(PlayerView::id).doesNotContain(guest.playerId());
    }

    @Test
    void anIdleGameIsAbortedByTheSweep() {
        startGame(2);
        now = now.plusSeconds(901);
        service.abortIdleGames();
        assertThat(state().phase()).isEqualTo(GamePhase.ENDED);
        assertThat(state().abortReason()).isEqualTo("A player exceeded the 15-minute turn limit");
    }
}
