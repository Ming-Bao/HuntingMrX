package com.huntingmrxwellington.property;

import com.huntingmrxwellington.config.GameSettings;
import com.huntingmrxwellington.controller.ApiExceptionHandler;
import com.huntingmrxwellington.controller.GameController;
import com.huntingmrxwellington.controller.MapController;
import com.huntingmrxwellington.game.MapGraph;
import com.huntingmrxwellington.game.TestMaps;
import com.huntingmrxwellington.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Random;
import java.util.StringJoiner;

import static com.huntingmrxwellington.property.GamePropertyTest.forEachSeed;
import static com.huntingmrxwellington.property.GamePropertyTest.randomString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fuzz: random request bodies, with and without a token, against every endpoint of the real
 *  controller and service in a started game. Nothing may cause a server error. */
class ApiFuzzTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final GameSettings SETTINGS = new GameSettings("test-map.json", 900, 12, 8, 6, 2);
    static final String[] ENDPOINTS = {"create", "join", "get", "start", "validMoves", "move", "leave", "kick", "unknownGame"};
    static final String[] KEYS = {"hostName", "maxPlayers", "joinCode", "playerName", "toNodeId", "ticket", "x"};
    static final String[] VALUES = {"null", "true", "[]", "{}", "1.5", "-1", "0", "7", "99999999999",
            "\"DOUBLE_BUS\"", "\"BLACK\"", "\"\""};

    /** For each seed: a fresh started game, then five random requests against it. */
    @Test
    void noRequestCausesAServerError() {
        forEachSeed(100, seed -> {
            Random rng = new Random(seed);
            App app = new App();
            for (int i = 0; i < 5; i++) {
                String endpoint = ENDPOINTS[rng.nextInt(ENDPOINTS.length)];
                String body = randomBody(rng);
                int status = app.send(endpoint, body, rng.nextBoolean());
                assertThat(status).as("%s with body %s", endpoint, body).isLessThan(500);
            }
        });
    }

    /** A JSON object with random fields, random junk, or one of a few awkward bodies. */
    static String randomBody(Random rng) {
        return switch (rng.nextInt(3)) {
            case 0 -> {
                StringJoiner fields = new StringJoiner(",", "{", "}");
                for (int i = rng.nextInt(5); i > 0; i--) {
                    String value = rng.nextBoolean() ? VALUES[rng.nextInt(VALUES.length)] : "\"" + letters(rng, 25) + "\"";
                    fields.add("\"" + KEYS[rng.nextInt(KEYS.length)] + "\":" + value);
                }
                yield fields.toString();
            }
            case 1 -> randomString(rng, 40);
            default -> new String[] {"", "[]", "null", "{"}[rng.nextInt(4)];
        };
    }

    static String letters(Random rng, int maxLength) {
        StringBuilder s = new StringBuilder();
        for (int i = rng.nextInt(maxLength + 1); i > 0; i--) s.append((char) ('a' + rng.nextInt(26)));
        return s.toString();
    }

    /** The real controller and service on the small test board, with one started three-player game. */
    static final class App {

        final MockMvc mvc;
        final String gameId;
        final String playerId;
        final String token;

        App() throws Exception {
            MapGraph map = TestMaps.small();
            GameService service = new GameService(new SimpMessagingTemplate((message, timeout) -> true), map, SETTINGS);
            mvc = MockMvcBuilders.standaloneSetup(new GameController(service), new MapController(map))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            JsonNode host = read(post("/api/games/create").content("{\"hostName\":\"Host\",\"maxPlayers\":3}"));
            gameId = host.get("gameState").get("gameId").asString();
            playerId = host.get("playerId").asString();
            token = host.get("playerToken").asString();
            read(post("/api/games/join").content("{\"joinCode\":\"" + host.get("gameState").get("joinCode").asString()
                    + "\",\"playerName\":\"Guest\"}"));
            read(post("/api/games/" + gameId + "/start").header(GameController.TOKEN_HEADER, token));
        }

        int send(String endpoint, String body, boolean withToken) throws Exception {
            String game = "/api/games/" + gameId;
            MockHttpServletRequestBuilder request = switch (endpoint) {
                case "create" -> post("/api/games/create");
                case "join" -> post("/api/games/join");
                case "get" -> get(game);
                case "start" -> post(game + "/start");
                case "validMoves" -> get(game + "/valid-moves");
                case "move" -> post(game + "/moves");
                case "leave" -> delete(game + "/players/" + playerId);
                case "kick" -> delete(game + "/players/someone");
                default -> get("/api/games/no-such-game");
            };
            request.contentType(APPLICATION_JSON).content(body);
            if (withToken) request.header(GameController.TOKEN_HEADER, token);
            return mvc.perform(request).andReturn().getResponse().getStatus();
        }

        JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
            return JSON.readTree(mvc.perform(request.contentType(APPLICATION_JSON)).andReturn().getResponse().getContentAsString());
        }
    }
}
