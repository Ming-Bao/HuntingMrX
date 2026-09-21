package com.huntingmrxwellington.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract, through the real application with nothing mocked. */
@SpringBootTest(properties = "game.map-file=test-map.json")
class ApiIntegrationTest {

    static final String TOKEN = GameController.TOKEN_HEADER;

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    record Player(String id, String token) {}
    record Started(String gameId, Player mrX, Player detective) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void createReturns201WithAnIdATokenAndTheLobby() throws Exception {
        create("{\"hostName\":\"Alice\",\"maxPlayers\":4}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").isNotEmpty())
                .andExpect(jsonPath("$.playerToken").isNotEmpty())
                .andExpect(jsonPath("$.gameState.phase").value("LOBBY"))
                .andExpect(jsonPath("$.gameState.players[0].name").value("Alice"));
    }

    @Test
    void badCreateRequestsAre400WithAnErrorMessage() throws Exception {   // B8
        for (String body : List.of("{\"hostName\":\"\",\"maxPlayers\":4}", "{\"hostName\":\"Alice\",\"maxPlayers\":9}",
                "{}", "not json", "")) {
            create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        }
    }

    @Test
    void joinErrorsMapToStatusCodes() throws Exception {
        JsonNode host = body(create("{\"hostName\":\"Host\",\"maxPlayers\":2}"));
        String code = host.get("gameState").get("joinCode").asString();
        join(code, "Bob").andExpect(status().isOk()).andExpect(jsonPath("$.playerToken").isNotEmpty());
        join(code, "Carol").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Game is full"));
        join("ZZZZZZ", "Dan").andExpect(status().isNotFound());
        join(code, " ").andExpect(status().isBadRequest());
    }

    @Test
    void theStateNeverContainsATokenAndHidesMrXFromThePublic() throws Exception {
        Started g = startTwoPlayerGame();
        String publicView = mvc.perform(get("/api/games/" + g.gameId()))
                .andReturn().getResponse().getContentAsString();
        String mrXView = mvc.perform(get("/api/games/" + g.gameId()).header(TOKEN, g.mrX().token()))
                .andReturn().getResponse().getContentAsString();
        for (String view : List.of(publicView, mrXView))
            assertThat(view).doesNotContain(g.mrX().token()).doesNotContain(g.detective().token());
        assertThat(mrXNode(json.readTree(publicView)).isNull()).isTrue();
        assertThat(mrXNode(json.readTree(mrXView)).isNull()).isFalse();
    }

    @Test
    void mrXsPublicIdGivesNoAccess() throws Exception {   // B2
        Started g = startTwoPlayerGame();
        String mrXId = g.mrX().id();
        JsonNode peek = body(mvc.perform(get("/api/games/" + g.gameId()).param("playerId", mrXId)));
        assertThat(mrXNode(peek).isNull()).isTrue();      // the old ?playerId= trick is ignored
        mvc.perform(get("/api/games/" + g.gameId() + "/valid-moves").header(TOKEN, mrXId))
                .andExpect(status().isForbidden());
    }

    @Test
    void actingWithoutATokenIsForbidden() throws Exception {   // B2
        Started g = startTwoPlayerGame();
        String game = "/api/games/" + g.gameId();
        mvc.perform(get(game + "/valid-moves")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").isNotEmpty());
        mvc.perform(post(game + "/moves").contentType(APPLICATION_JSON).content("{\"toNodeId\":1,\"ticket\":\"BUS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(game + "/start")).andExpect(status().isForbidden());
        // a body-less DELETE used to remove anyone, Mr X included
        mvc.perform(delete(game + "/players/" + g.mrX().id())).andExpect(status().isForbidden());
    }

    @Test
    void aDetectiveCannotRemoveMrX() throws Exception {   // B2
        Started g = startTwoPlayerGame();
        mvc.perform(delete("/api/games/" + g.gameId() + "/players/" + g.mrX().id()).header(TOKEN, g.detective().token()))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/games/" + g.gameId())).andExpect(jsonPath("$.phase").value("IN_PROGRESS"));
    }

    @Test
    void onlyTheHostCanStartAndOnlyOnce() throws Exception {
        JsonNode host = body(create("{\"hostName\":\"Host\",\"maxPlayers\":2}"));
        String gameId = host.get("gameState").get("gameId").asString();
        JsonNode guest = body(join(host.get("gameState").get("joinCode").asString(), "Guest"));
        String start = "/api/games/" + gameId + "/start";
        mvc.perform(post(start).header(TOKEN, guest.get("playerToken").asString())).andExpect(status().isForbidden());
        mvc.perform(post(start).header(TOKEN, host.get("playerToken").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("IN_PROGRESS"));
        mvc.perform(post(start).header(TOKEN, host.get("playerToken").asString())).andExpect(status().isConflict());
    }

    @Test
    void detectivesGetTheTicketsFromApplicationProperties() throws Exception {
        Started g = startTwoPlayerGame();
        JsonNode state = body(mvc.perform(get("/api/games/" + g.gameId())));
        for (JsonNode p : state.get("players")) {
            if ("DETECTIVE".equals(p.get("role").asString())) {
                assertThat(p.get("tickets").get("ESCOOTER").asInt()).isEqualTo(12);
                assertThat(p.get("tickets").get("BUS").asInt()).isEqualTo(8);
                assertThat(p.get("tickets").get("TRAIN").asInt()).isEqualTo(6);
                assertThat(p.get("tickets").get("FERRY").asInt()).isEqualTo(2);
            }
        }
    }

    @Test
    void theCurrentPlayerCanFetchMovesAndPlayOne() throws Exception {
        Started g = startTwoPlayerGame();
        String game = "/api/games/" + g.gameId();
        JsonNode first = body(mvc.perform(get(game + "/valid-moves").header(TOKEN, g.mrX().token()))
                .andExpect(status().isOk())).get(0);
        String move = json.writeValueAsString(Map.of("toNodeId", first.get("nodeId").asInt(),
                "ticket", first.get("ticketOptions").get(0).asString()));
        mvc.perform(post(game + "/moves").header(TOKEN, g.detective().token()).contentType(APPLICATION_JSON).content(move))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Not your turn"));
        mvc.perform(post(game + "/moves").header(TOKEN, g.mrX().token()).contentType(APPLICATION_JSON).content(move))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turnPhase").value("DETECTIVE_TURN"));
    }

    @Test
    void badMoveRequestsAre400NotServerErrors() throws Exception {   // B5, B8
        Started g = startTwoPlayerGame();
        for (String body : List.of("{}", "{\"toNodeId\":1}", "{\"toNodeId\":\"x\",\"ticket\":\"BUS\"}",
                "{\"toNodeId\":1,\"ticket\":\"DOUBLE\"}", "[]", "")) {
            mvc.perform(post("/api/games/" + g.gameId() + "/moves").header(TOKEN, g.mrX().token())
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
    }

    @Test
    void validMovesBeforeTheStartIsAConflict() throws Exception {
        JsonNode host = body(create("{\"hostName\":\"Host\",\"maxPlayers\":2}"));
        mvc.perform(get("/api/games/" + host.get("gameState").get("gameId").asString() + "/valid-moves")
                .header(TOKEN, host.get("playerToken").asString())).andExpect(status().isConflict());
    }

    @Test
    void anUnknownGameIs404() throws Exception {
        mvc.perform(get("/api/games/nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    @Test
    void leavingAndKickingReturn204() throws Exception {
        JsonNode host = body(create("{\"hostName\":\"Host\",\"maxPlayers\":4}"));
        String gameId = host.get("gameState").get("gameId").asString();
        String code = host.get("gameState").get("joinCode").asString();
        JsonNode bob = body(join(code, "Bob"));
        JsonNode carol = body(join(code, "Carol"));
        mvc.perform(delete("/api/games/" + gameId + "/players/" + bob.get("playerId").asString())
                .header(TOKEN, host.get("playerToken").asString())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/games/" + gameId + "/players/" + carol.get("playerId").asString())
                .header(TOKEN, carol.get("playerToken").asString())).andExpect(status().isNoContent());
        mvc.perform(get("/api/games/" + gameId)).andExpect(jsonPath("$.players.length()").value(1));
    }

    @Test
    void theMapEndpointServesTheConfiguredMap() throws Exception {
        mvc.perform(get("/api/map"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.nodes.length()").value(5));
    }

    ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/games/create").contentType(APPLICATION_JSON).content(body));
    }

    ResultActions join(String code, String name) throws Exception {
        return mvc.perform(post("/api/games/join").contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("joinCode", code, "playerName", name))));
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    Started startTwoPlayerGame() throws Exception {
        JsonNode host = body(create("{\"hostName\":\"Host\",\"maxPlayers\":2}"));
        String gameId = host.get("gameState").get("gameId").asString();
        JsonNode guest = body(join(host.get("gameState").get("joinCode").asString(), "Guest"));
        JsonNode started = body(mvc.perform(post("/api/games/" + gameId + "/start")
                .header(TOKEN, host.get("playerToken").asString())));
        Player h = new Player(host.get("playerId").asString(), host.get("playerToken").asString());
        Player gu = new Player(guest.get("playerId").asString(), guest.get("playerToken").asString());
        boolean hostIsMrX = mrXId(started).equals(h.id());
        return new Started(gameId, hostIsMrX ? h : gu, hostIsMrX ? gu : h);
    }

    static String mrXId(JsonNode state) {
        for (JsonNode p : state.get("players")) if ("MR_X".equals(p.get("role").asString())) return p.get("id").asString();
        throw new AssertionError("no Mr X in " + state);
    }

    static JsonNode mrXNode(JsonNode state) {
        for (JsonNode p : state.get("players")) if ("MR_X".equals(p.get("role").asString())) return p.get("nodeId");
        throw new AssertionError("no Mr X in " + state);
    }
}
