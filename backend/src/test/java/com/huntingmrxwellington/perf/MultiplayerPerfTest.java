package com.huntingmrxwellington.perf;

import com.huntingmrxwellington.support.HttpTestClient;
import com.huntingmrxwellington.support.StompTestClient;
import com.huntingmrxwellington.support.StompTestClient.Inbox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: mvn test -Dgroups=perf -DexcludedGroups=
 *  Plays GAMES full games at once on the real map, every player a real HTTP + STOMP client, and
 *  reports how long it takes from submitting a move until every other player has the new state. */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "game.map-file=map.json")
class MultiplayerPerfTest {

    static final int GAMES = 10;
    static final int PLAYERS = 6;

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate messaging;

    record Player(String id, String token) {}

    @Test
    void concurrentGamesStayConsistentAndBroadcastQuickly() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(GAMES);
        List<Future<List<Long>>> games = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) games.add(pool.submit(this::playOneGame));
        List<Long> nanos = new ArrayList<>();
        for (Future<List<Long>> game : games) nanos.addAll(game.get(5, TimeUnit.MINUTES));
        pool.shutdown();

        Collections.sort(nanos);
        System.out.printf("%n%d concurrent games x %d players on map.json, %d moves%n", GAMES, PLAYERS, nanos.size());
        System.out.printf("move submitted -> every other player updated: p50 %.1f ms, p95 %.1f ms, max %.1f ms%n%n",
                millis(nanos, 0.50), millis(nanos, 0.95), millis(nanos, 1.0));
        assertThat(nanos).isNotEmpty();
    }

    List<Long> playOneGame() throws Exception {
        HttpTestClient http = new HttpTestClient(port);
        List<Player> players = new ArrayList<>();
        JsonNode host = http.ok("POST", "/api/games/create", null, Map.of("hostName", "P0", "maxPlayers", PLAYERS));
        String gameId = host.get("gameState").get("gameId").asString();
        players.add(new Player(host.get("playerId").asString(), host.get("playerToken").asString()));
        for (int i = 1; i < PLAYERS; i++) {
            JsonNode joined = http.ok("POST", "/api/games/join", null,
                    Map.of("joinCode", host.get("gameState").get("joinCode").asString(), "playerName", "P" + i));
            players.add(new Player(joined.get("playerId").asString(), joined.get("playerToken").asString()));
        }
        Map<String, Inbox> inboxes = new HashMap<>();
        List<StompTestClient> clients = new ArrayList<>();
        try {
            for (Player p : players) {
                StompTestClient client = new StompTestClient(port, messaging);
                clients.add(client);
                inboxes.put(p.id(), client.subscribe("/topic/games/" + gameId + "/players/" + p.token()));
            }
            JsonNode state = http.ok("POST", "/api/games/" + gameId + "/start", players.get(0).token(), null);
            List<Long> nanos = new ArrayList<>();
            while ("IN_PROGRESS".equals(state.get("phase").asString())) {
                String currentId = state.get("currentPlayerId").asString();
                Player current = players.stream().filter(p -> p.id().equals(currentId)).findFirst().orElseThrow();
                JsonNode pick = http.ok("GET", "/api/games/" + gameId + "/valid-moves", current.token(), null).get(0);
                long start = System.nanoTime();
                state = http.ok("POST", "/api/games/" + gameId + "/moves", current.token(), Map.of(
                        "toNodeId", pick.get("nodeId").asInt(), "ticket", pick.get("ticketOptions").get(0).asString()));
                String expected = fingerprint(state);
                for (Player p : players)
                    if (!p.id().equals(currentId)) inboxes.get(p.id()).awaitMatching(s -> fingerprint(s).equals(expected));
                nanos.add(System.nanoTime() - start);
            }
            assertThat(state.get("phase").asString()).isEqualTo("ENDED");
            assertThat(state.get("winner").isNull()).isFalse();
            return nanos;
        } finally {
            clients.forEach(StompTestClient::close);
        }
    }

    /** Identifies one state within a game; it changes with every move. */
    static String fingerprint(JsonNode s) {
        return s.get("phase").asString() + "|" + s.get("round").asInt() + "|"
                + s.get("currentPlayerId").asString() + "|" + s.get("mrXLog").size();
    }

    static double millis(List<Long> sorted, double quantile) {
        int i = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, i)) / 1_000_000.0;
    }
}
