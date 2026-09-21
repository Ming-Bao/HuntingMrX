package com.huntingmrxwellington.e2e;

import com.huntingmrxwellington.game.MapGraph;
import com.huntingmrxwellington.support.HttpTestClient;
import com.huntingmrxwellington.support.StompTestClient;
import com.huntingmrxwellington.support.StompTestClient.Inbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Complete games through the real server, every player a real HTTP + STOMP client, the way the
 *  frontend plays. Start positions are random, so the checks are rules that must hold in any
 *  game rather than one scripted outcome. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "game.map-file=test-map.json")
class FullGameE2ETest {

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate messaging;

    HttpTestClient http;
    final List<StompTestClient> clients = new ArrayList<>();

    record Player(String id, String token) {}

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(StompTestClient::close);
    }

    @ParameterizedTest(name = "{0} players")
    @ValueSource(ints = {2, 4})
    void aCompleteGameEndsWithAWinnerTheBoardAgreesWith(int playerCount) throws Exception {
        List<Player> players = new ArrayList<>();
        String gameId = lobby(playerCount, players);
        StompTestClient client = connect();
        List<Inbox> inboxes = new ArrayList<>();
        for (Player p : players) inboxes.add(client.subscribe(privateTopic(gameId, p)));

        http.ok("POST", "/api/games/" + gameId + "/start", players.get(0).token(), null);
        JsonNode state = http.ok("GET", "/api/games/" + gameId, null, null);
        Player mrX = byId(players, playerWithRole(state, "MR_X").get("id").asString());

        int moves = 0;
        while ("IN_PROGRESS".equals(state.get("phase").asString())) {
            assertThat(++moves).as("every game ends within 24 rounds").isLessThanOrEqualTo(24 * playerCount);
            Player current = byId(players, state.get("currentPlayerId").asString());
            JsonNode options = http.ok("GET", "/api/games/" + gameId + "/valid-moves", current.token(), null);
            assertThat(options).as("whoever's turn it is can move").isNotEmpty();
            JsonNode pick = options.get(0);
            state = http.ok("POST", "/api/games/" + gameId + "/moves", current.token(), Map.of(
                    "toNodeId", pick.get("nodeId").asInt(), "ticket", pick.get("ticketOptions").get(0).asString()));
            checkRules(gameId, mrX);
        }

        JsonNode end = http.ok("GET", "/api/games/" + gameId, mrX.token(), null);
        assertThat(end.get("phase").asString()).isEqualTo("ENDED");
        assertThat(end.get("abortReason").isNull()).isTrue();
        if ("DETECTIVES".equals(end.get("winner").asString())) assertThat(caughtOrBoxedIn(end)).isTrue();
        else assertThat(end.get("round").asInt()).isEqualTo(24);
        for (Inbox inbox : inboxes) inbox.awaitMatching(s -> "ENDED".equals(s.get("phase").asString()));
    }

    @Test
    void onlyTheCurrentPlayerMayMoveAndARejectedMoveChangesNothing() throws Exception {
        List<Player> players = new ArrayList<>();
        String gameId = lobby(2, players);
        http.ok("POST", "/api/games/" + gameId + "/start", players.get(0).token(), null);
        JsonNode before = http.ok("GET", "/api/games/" + gameId, null, null);
        Player current = byId(players, before.get("currentPlayerId").asString());
        Player other = players.get(0) == current ? players.get(1) : players.get(0);
        JsonNode pick = http.ok("GET", "/api/games/" + gameId + "/valid-moves", current.token(), null).get(0);

        HttpTestClient.Response rejected = http.call("POST", "/api/games/" + gameId + "/moves", other.token(), Map.of(
                "toNodeId", pick.get("nodeId").asInt(), "ticket", pick.get("ticketOptions").get(0).asString()));

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(http.ok("GET", "/api/games/" + gameId, null, null)).isEqualTo(before);
    }

    @Test
    void aPlayerLeavingMidGameSendsEveryoneTheEndedState() throws Exception {
        List<Player> players = new ArrayList<>();
        String gameId = lobby(2, players);
        http.ok("POST", "/api/games/" + gameId + "/start", players.get(0).token(), null);
        Inbox hostHears = connect().subscribe(privateTopic(gameId, players.get(0)));
        Player leaver = players.get(1);

        http.ok("DELETE", "/api/games/" + gameId + "/players/" + leaver.id(), leaver.token(), null);

        JsonNode ended = hostHears.next();
        assertThat(ended.get("phase").asString()).isEqualTo("ENDED");
        assertThat(ended.get("abortReason").asString()).endsWith("has left the game");
    }

    /** Rules that hold after every move, checked from Mr X's view (he sees everything) and the public view. */
    void checkRules(String gameId, Player mrX) throws Exception {
        JsonNode all = http.ok("GET", "/api/games/" + gameId, mrX.token(), null);
        JsonNode pub = http.ok("GET", "/api/games/" + gameId, null, null);
        int round = all.get("round").asInt();
        assertThat(round).isBetween(1, 24);
        int mrXNode = playerWithRole(all, "MR_X").get("nodeId").asInt();
        for (JsonNode p : all.get("players"))
            if ("DETECTIVE".equals(p.get("role").asString()))
                for (JsonNode count : p.get("tickets").values()) assertThat(count.asInt()).isNotNegative();
        if ("IN_PROGRESS".equals(all.get("phase").asString())) {
            assertThat(detectiveNodes(all)).doesNotContain(mrXNode);
            assertThat(all.get("currentPlayerId").isNull()).isFalse();
        }
        boolean revealedThisRound = false;
        for (JsonNode m : all.get("mrXLog"))
            if (m.get("round").asInt() == round && !m.get("nodeId").isNull()) revealedThisRound = true;
        JsonNode seenByPublic = playerWithRole(pub, "MR_X").get("nodeId");
        if (revealedThisRound) assertThat(seenByPublic.asInt()).isEqualTo(mrXNode);
        else assertThat(seenByPublic.isNull()).isTrue();
    }

    /** Detectives win only by landing on Mr X or by leaving him no free neighbour. */
    static boolean caughtOrBoxedIn(JsonNode end) throws Exception {
        MapGraph map = MapGraph.parse(new ClassPathResource("static/test-map.json").getContentAsByteArray());
        int mrXNode = playerWithRole(end, "MR_X").get("nodeId").asInt();
        Set<Integer> detectiveNodes = detectiveNodes(end);
        return detectiveNodes.contains(mrXNode) || map.nodeIds().stream()
                .filter(n -> !map.modesBetween(mrXNode, n).isEmpty())
                .allMatch(detectiveNodes::contains);
    }

    String lobby(int count, List<Player> players) throws Exception {
        JsonNode host = http.ok("POST", "/api/games/create", null, Map.of("hostName", "P0", "maxPlayers", count));
        players.add(new Player(host.get("playerId").asString(), host.get("playerToken").asString()));
        for (int i = 1; i < count; i++) {
            JsonNode joined = http.ok("POST", "/api/games/join", null,
                    Map.of("joinCode", host.get("gameState").get("joinCode").asString(), "playerName", "P" + i));
            players.add(new Player(joined.get("playerId").asString(), joined.get("playerToken").asString()));
        }
        return host.get("gameState").get("gameId").asString();
    }

    StompTestClient connect() throws Exception {
        StompTestClient client = new StompTestClient(port, messaging);
        clients.add(client);
        return client;
    }

    static String privateTopic(String gameId, Player p) {
        return "/topic/games/" + gameId + "/players/" + p.token();
    }

    static Player byId(List<Player> players, String id) {
        return players.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    static JsonNode playerWithRole(JsonNode state, String role) {
        for (JsonNode p : state.get("players")) if (role.equals(p.get("role").asString())) return p;
        throw new AssertionError("no " + role + " in " + state);
    }

    static Set<Integer> detectiveNodes(JsonNode state) {
        Set<Integer> nodes = new HashSet<>();
        for (JsonNode p : state.get("players"))
            if ("DETECTIVE".equals(p.get("role").asString())) nodes.add(p.get("nodeId").asInt());
        return nodes;
    }
}
