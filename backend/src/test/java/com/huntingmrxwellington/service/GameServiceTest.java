package com.huntingmrxwellington.service;

import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.game.GamePhase;
import com.huntingmrxwellington.game.GameState;
import com.huntingmrxwellington.game.PlayerView;
import com.huntingmrxwellington.game.Role;
import com.huntingmrxwellington.game.TestMaps;
import com.huntingmrxwellington.game.TicketType;
import com.huntingmrxwellington.game.ValidMove;
import com.huntingmrxwellington.service.GameService.JoinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** GameService with a real game and board and a mocked messaging template: what gets
 *  published where, token handling, and locking. */
@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock SimpMessagingTemplate messaging;
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(messaging, TestMaps.small(), ServiceFixtures.SETTINGS,
                ServiceFixtures.NO_SHUFFLE, () -> now);
    }

    /** A started two-player game: the host is Mr X on node 1, the guest a detective on node 2. */
    record Started(String gameId, JoinResponse mrX, JoinResponse detective) {
        String topic() { return "/topic/games/" + gameId; }
        String privateTopic(JoinResponse p) { return topic() + "/players/" + p.playerToken(); }
    }

    Started startTwoPlayerGame() {
        JoinResponse host = service.createGame("Host", 2);
        JoinResponse guest = service.joinGame(host.gameState().joinCode(), "Guest");
        clearInvocations(messaging);
        service.startGame(host.gameState().gameId(), host.playerToken());
        return new Started(host.gameState().gameId(), host, guest);
    }

    /** Each destination and the last payload sent there since the last clearInvocations. */
    Map<String, Object> published() {
        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging, atLeastOnce()).convertAndSend(destination.capture(), payload.capture());
        Map<String, Object> sent = new LinkedHashMap<>();
        for (int i = 0; i < destination.getAllValues().size(); i++)
            sent.put(destination.getAllValues().get(i), payload.getAllValues().get(i));
        return sent;
    }

    static Integer mrXNode(GameState view) {
        return view.players().stream().filter(p -> p.role() == Role.MR_X).findFirst().orElseThrow().nodeId();
    }

    @Test
    void createReturnsASecretTokenAndBroadcastsNothing() {
        JoinResponse host = service.createGame("Host", 4);
        assertThat(host.playerToken()).isNotBlank().isNotEqualTo(host.playerId());
        assertThat(host.gameState().phase()).isEqualTo(GamePhase.LOBBY);
        assertThat(host.gameState().joinCode()).matches("[A-Z0-9]{6}");
        verifyNoInteractions(messaging);
    }

    @Test
    void joinCodesAreCaseInsensitiveAndTrimmed() {
        Random zeros = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        GameService lettersOnly = new GameService(messaging, TestMaps.small(), ServiceFixtures.SETTINGS, zeros, () -> now);
        JoinResponse host = lettersOnly.createGame("Host", 4);
        assertThat(host.gameState().joinCode()).isEqualTo("AAAAAA");
        assertThat(lettersOnly.joinGame("  aaaaaa ", "Guest").gameState().players()).hasSize(2);
    }

    @Test
    void joiningUsesTheCodeToFindTheRightGame() {
        GameService seeded = new GameService(messaging, TestMaps.small(), ServiceFixtures.SETTINGS, new Random(1), () -> now);
        List<JoinResponse> hosts = List.of(seeded.createGame("A", 4), seeded.createGame("B", 4), seeded.createGame("C", 4));
        for (JoinResponse host : hosts) {
            JoinResponse guest = seeded.joinGame(host.gameState().joinCode(), "Guest");
            assertThat(guest.gameState().gameId()).isEqualTo(host.gameState().gameId());
        }
    }

    @Test
    void joiningBroadcastsTheLobby() {
        JoinResponse host = service.createGame("Host", 4);
        service.joinGame(host.gameState().joinCode(), "Guest");
        GameState lobby = (GameState) published().get("/topic/games/" + host.gameState().gameId());
        assertThat(lobby.players()).extracting(PlayerView::name).containsExactly("Host", "Guest");
    }

    @Test
    void joinErrorsAreReported() {
        assertThatThrownBy(() -> service.joinGame("ZZZZZZ", "Guest")).isInstanceOf(GameNotFoundException.class);
        assertThatThrownBy(() -> service.joinGame(" ", "Guest")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.joinGame(null, "Guest")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownGameIsNotFound() {
        assertThatThrownBy(() -> service.getGame("nope", null)).isInstanceOf(GameNotFoundException.class);
        assertThatThrownBy(() -> service.startGame("nope", "t")).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void actingNeedsAValidTokenAndAPublicIdIsNotOne() {   // B2
        Started g = startTwoPlayerGame();
        for (String bad : new String[] {null, "not-a-token", g.mrX().playerId()}) {
            assertThatThrownBy(() -> service.validMoves(g.gameId(), bad)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.submitMove(g.gameId(), bad, 5, "FERRY")).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.removePlayer(g.gameId(), bad, g.mrX().playerId()))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.startGame(g.gameId(), bad)).isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    void readingWithAMissingOrUnknownTokenGivesThePublicView() {
        Started g = startTwoPlayerGame();
        assertThat(mrXNode(service.getGame(g.gameId(), null))).isNull();
        assertThat(mrXNode(service.getGame(g.gameId(), "stale-token"))).isNull();
        assertThat(mrXNode(service.getGame(g.gameId(), g.mrX().playerToken()))).isEqualTo(1);
    }

    @Test
    void startPublishesEachPlayersOwnViewAndMovesOnlyToMrX() {
        Started g = startTwoPlayerGame();
        Map<String, Object> sent = published();
        assertThat(mrXNode((GameState) sent.get(g.privateTopic(g.mrX())))).isEqualTo(1);
        assertThat(mrXNode((GameState) sent.get(g.privateTopic(g.detective())))).isNull();
        assertThat(mrXNode((GameState) sent.get(g.topic()))).isNull();
        assertThat(sent).containsKey(g.privateTopic(g.mrX()) + "/valid-moves");
        assertThat(sent).doesNotContainKey(g.privateTopic(g.detective()) + "/valid-moves");
    }

    @Test
    void startReturnsTheHostsViewOfTheStartedGame() {
        JoinResponse host = service.createGame("Host", 2);
        service.joinGame(host.gameState().joinCode(), "Guest");
        GameState started = service.startGame(host.gameState().gameId(), host.playerToken());
        assertThat(started.phase()).isEqualTo(GamePhase.IN_PROGRESS);
        assertThat(mrXNode(started)).isEqualTo(1);   // the host is Mr X and sees himself
    }

    @Test
    void validMovesAreTheCallersOwn() {
        Started g = startTwoPlayerGame();
        assertThat(service.validMoves(g.gameId(), g.mrX().playerToken()))
                .containsExactly(new ValidMove(5, List.of(TicketType.FERRY, TicketType.BLACK)));
    }

    @Test
    void afterAMoveTheNextPlayerGetsTheirMoves() {
        Started g = startTwoPlayerGame();
        clearInvocations(messaging);
        service.submitMove(g.gameId(), g.mrX().playerToken(), 5, "FERRY");
        Map<String, Object> sent = published();
        assertThat(sent).containsKey(g.privateTopic(g.detective()) + "/valid-moves");
        assertThat(sent).doesNotContainKey(g.privateTopic(g.mrX()) + "/valid-moves");
    }

    @Test
    void tokensAppearInTopicNamesButNeverInPayloads() {   // B2
        Started g = startTwoPlayerGame();
        ObjectMapper json = new ObjectMapper();
        for (Object payload : published().values()) {
            String body = json.writeValueAsString(payload);
            assertThat(body).doesNotContain(g.mrX().playerToken()).doesNotContain(g.detective().playerToken());
        }
    }

    @Test
    void kickingPublishesTheLobbyWithoutTheKickedPlayer() {
        JoinResponse host = service.createGame("Host", 4);
        JoinResponse guest = service.joinGame(host.gameState().joinCode(), "Guest");
        clearInvocations(messaging);
        service.removePlayer(host.gameState().gameId(), host.playerToken(), guest.playerId());
        GameState lobby = (GameState) published().get("/topic/games/" + host.gameState().gameId());
        assertThat(lobby.players()).extracting(PlayerView::id).containsExactly(host.playerId());
    }

    @Test
    void theIdleSweepEndsStaleGamesAndTellsThePlayers() {
        Started g = startTwoPlayerGame();
        clearInvocations(messaging);
        now = now.plusSeconds(ServiceFixtures.SETTINGS.turnTimerSeconds() + 1);
        service.abortIdleGames();
        GameState view = (GameState) published().get(g.privateTopic(g.detective()));
        assertThat(view.phase()).isEqualTo(GamePhase.ENDED);
        assertThat(view.abortReason()).contains("15-minute");
    }

    @Test
    void theIdleSweepLeavesActiveGamesAlone() {
        startTwoPlayerGame();
        clearInvocations(messaging);
        service.abortIdleGames();
        verifyNoInteractions(messaging);
    }

    @Test
    void concurrentJoinsNeverOverfillAGame() throws Exception {   // B7
        JoinResponse host = service.createGame("Host", 6);
        String code = host.gameState().joinCode();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> joins = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String name = "P" + i;
            joins.add(pool.submit(() -> {
                go.await();
                try {
                    service.joinGame(code, name);
                    return true;
                } catch (ConflictException full) {
                    return false;
                }
            }));
        }
        go.countDown();
        int joined = 0;
        for (Future<Boolean> join : joins) if (join.get(10, TimeUnit.SECONDS)) joined++;
        pool.shutdown();
        assertThat(joined).isEqualTo(5);
        assertThat(service.getGame(host.gameState().gameId(), null).players()).hasSize(6);
    }
}
