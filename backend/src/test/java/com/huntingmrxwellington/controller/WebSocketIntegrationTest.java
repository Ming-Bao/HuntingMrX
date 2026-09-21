package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.support.HttpTestClient;
import com.huntingmrxwellington.support.StompTestClient;
import com.huntingmrxwellington.support.StompTestClient.Inbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The real-time side, with real STOMP clients against the running server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "game.map-file=test-map.json")
class WebSocketIntegrationTest {

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate messaging;

    HttpTestClient http;
    final List<StompTestClient> clients = new ArrayList<>();

    record Player(String id, String token) {}

    record Lobby(String gameId, Player host, Player guest) {
        String privateTopic(Player p) { return "/topic/games/" + gameId + "/players/" + p.token(); }
    }

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(StompTestClient::close);
    }

    @Test
    void theLobbyTopicBroadcastsEachJoin() throws Exception {
        JsonNode host = http.ok("POST", "/api/games/create", null, Map.of("hostName", "Host", "maxPlayers", 3));
        Inbox lobby = connect().subscribe("/topic/games/" + host.get("gameState").get("gameId").asString());
        http.ok("POST", "/api/games/join", null,
                Map.of("joinCode", host.get("gameState").get("joinCode").asString(), "playerName", "Bob"));
        assertThat(lobby.next().get("players")).hasSize(2);
    }

    @Test
    void eachPlayerHearsTheirOwnViewAndOnlyTheCurrentPlayerGetsMoves() throws Exception {
        Lobby g = twoPlayerLobby();
        StompTestClient client = connect();
        Inbox hostState = client.subscribe(g.privateTopic(g.host()));
        Inbox guestState = client.subscribe(g.privateTopic(g.guest()));
        Inbox hostMoves = client.subscribe(g.privateTopic(g.host()) + "/valid-moves");
        Inbox guestMoves = client.subscribe(g.privateTopic(g.guest()) + "/valid-moves");

        http.ok("POST", "/api/games/" + g.gameId() + "/start", g.host().token(), null);

        JsonNode forHost = hostState.next();
        JsonNode forGuest = guestState.next();
        boolean hostIsMrX = mrX(forHost).get("id").asString().equals(g.host().id());
        assertThat(mrX(hostIsMrX ? forHost : forGuest).get("nodeId").isNull()).isFalse();
        assertThat(mrX(hostIsMrX ? forGuest : forHost).get("nodeId").isNull()).isTrue();
        assertThat((hostIsMrX ? hostMoves : guestMoves).next()).isNotEmpty();
        assertThat((hostIsMrX ? guestMoves : hostMoves).poll(300)).isNull();
    }

    @Test
    void aWildcardSubscriptionReceivesNothing() throws Exception {   // B2
        Lobby g = twoPlayerLobby();
        Inbox real = connect().subscribe(g.privateTopic(g.host()));
        Inbox eavesdropper = connect().subscribe("/topic/games/" + g.gameId() + "/players/**");

        http.ok("POST", "/api/games/" + g.gameId() + "/start", g.host().token(), null);

        real.next();                                     // the broadcast went out...
        assertThat(eavesdropper.poll(300)).isNull();     // ...and the wildcard saw none of it
    }

    @Test
    void clientsCannotPublishToTopics() throws Exception {   // B2
        Lobby g = twoPlayerLobby();
        Inbox lobby = connect().subscribe("/topic/games/" + g.gameId());
        connect().send("/topic/games/" + g.gameId(), "{\"phase\":\"ENDED\",\"abortReason\":\"fake\"}");

        http.ok("POST", "/api/games/" + g.gameId() + "/start", g.host().token(), null);

        assertThat(lobby.next().get("phase").asString()).isEqualTo("IN_PROGRESS");
        assertThat(lobby.poll(300)).isNull();
    }

    StompTestClient connect() throws Exception {
        StompTestClient client = new StompTestClient(port, messaging);
        clients.add(client);
        return client;
    }

    Lobby twoPlayerLobby() throws Exception {
        JsonNode host = http.ok("POST", "/api/games/create", null, Map.of("hostName", "Host", "maxPlayers", 2));
        JsonNode guest = http.ok("POST", "/api/games/join", null,
                Map.of("joinCode", host.get("gameState").get("joinCode").asString(), "playerName", "Guest"));
        return new Lobby(host.get("gameState").get("gameId").asString(),
                new Player(host.get("playerId").asString(), host.get("playerToken").asString()),
                new Player(guest.get("playerId").asString(), guest.get("playerToken").asString()));
    }

    static JsonNode mrX(JsonNode state) {
        for (JsonNode p : state.get("players")) if ("MR_X".equals(p.get("role").asString())) return p;
        throw new AssertionError("no Mr X in " + state);
    }
}
