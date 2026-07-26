package com.huntingmrxwellington.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end tests that drive a complete Hunting Mr. X: Wellington Edition game through the REST API
 * using a Firefox browser (Selenium) to make the HTTP requests — simulating real
 * browser-side networking including CORS handling.
 *
 * Prerequisites: Firefox and geckodriver must be installed on the host machine.
 *
 * Run in isolation (headless):
 *   mvn test -Dgroups=e2e
 *
 * Run with a visible browser window (useful for watching what's happening):
 *   mvn test -Dgroups=e2e -De2e.headless=false
 *
 * Skip during CI:
 *   mvn test -DexcludedGroups=e2e
 *
 * The test map (test-map.json) is pinned via the SpringBootTest properties below
 * so these tests continue to use it even after the production map file changes.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)   // one browser window for the whole class
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "game.map-file=test-map.json" }
)
class FullGameE2ETest {

    @LocalServerPort
    int port;

    private WebDriver driver;
    private JavascriptExecutor js;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void setUpDriver() {
        // Use system geckodriver if available; Selenium Manager downloads it otherwise.
        String sysgecko = "/snap/bin/geckodriver";
        if (new java.io.File(sysgecko).exists()) {
            System.setProperty("webdriver.gecko.driver", sysgecko);
        }
        // -De2e.headless=false to pop the browser open for manual inspection.
        boolean headless = !"false".equalsIgnoreCase(System.getProperty("e2e.headless", "true"));

        FirefoxOptions opts = new FirefoxOptions();
        if (headless) opts.addArguments("-headless");

        driver = new FirefoxDriver(opts);
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));
        js = (JavascriptExecutor) driver;
    }

    @AfterAll
    void tearDownDriver() {
        if (driver != null) driver.quit();
    }

    @BeforeEach
    void loadPage() {
        // Navigate to e2e.html — a plain HTML page in the static folder.
        // JSON files open in Firefox's JSON viewer which blocks fetch(), so we need real HTML.
        // Being on the same origin as the backend means fetch() calls need no CORS headers.
        driver.get("http://localhost:" + port + "/e2e.html");
    }

    // ── HTTP helper ──────────────────────────────────────────────────────────

    /**
     * Executes a JSON REST call through the browser's native fetch() API and
     * returns the parsed response body.  Asserts no network error occurred.
     */
    private JsonNode api(String method, String path, Object body) throws Exception {
        String url      = "http://localhost:" + port + path;
        String bodyJson = body != null ? mapper.writeValueAsString(body) : null;

        Object raw = js.executeAsyncScript(
            "const [url, method, body, done] = arguments;" +
            "fetch(url, {" +
            "  method," +
            "  headers: { 'Content-Type': 'application/json' }," +
            "  body: body ?? undefined" +
            "})" +
            ".then(r => r.text())" +
            ".then(done)" +
            ".catch(e => done('FETCH_ERR:' + e));",
            url, method, bodyJson);

        String text = (String) raw;
        assertThat(text)
                .as("Browser fetch failed: %s %s", method, path)
                .doesNotStartWith("FETCH_ERR:");
        return text == null || text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
    }

    // ── Setup helpers ────────────────────────────────────────────────────────

    /**
     * Creates a game, joins (maxPlayers - 1) extra players, then starts it.
     * Returns [gameId, hostPlayerId, player2Id, player3Id, ...].
     */
    private List<String> createAndStartGame(int maxPlayers) throws Exception {
        JsonNode created = api("POST", "/api/games/create",
                Map.of("hostName", "Player1", "maxPlayers", maxPlayers));
        String gameId   = created.get("gameState").get("gameId").asText();
        String hostId   = created.get("playerId").asText();
        String joinCode = created.get("gameState").get("joinCode").asText();

        List<String> ids = new ArrayList<>(List.of(gameId, hostId));
        for (int i = 2; i <= maxPlayers; i++) {
            JsonNode joined = api("POST", "/api/games/join",
                    Map.of("joinCode", joinCode, "playerName", "Player" + i));
            ids.add(joined.get("playerId").asText());
        }

        api("POST", "/api/games/" + gameId + "/start", Map.of("playerId", hostId));
        return ids;
    }

    // ── Game-loop helper ─────────────────────────────────────────────────────

    /**
     * Repeatedly picks the first available non-DOUBLE move for whoever's turn
     * it is, until the game phase leaves IN_PROGRESS or the turn budget runs out.
     * Returns the final game state.
     */
    private JsonNode playToEnd(String gameId) throws Exception {
        final int MAX_TURNS = 300;
        for (int t = 0; t < MAX_TURNS; t++) {
            JsonNode state = api("GET", "/api/games/" + gameId, null);
            if (!"IN_PROGRESS".equals(state.get("phase").asText())) return state;

            String currentId = state.get("currentPlayerId").asText();
            JsonNode moves   = api("GET",
                    "/api/games/" + gameId + "/valid-moves?playerId=" + currentId, null);
            JsonNode list    = moves.path("moves");

            if (list.isArray() && !list.isEmpty()) {
                JsonNode first  = list.get(0);
                int toNodeId    = first.get("nodeId").asInt();
                // Prefer any normal ticket over DOUBLE to keep the loop simple.
                String ticket   = first.get("ticketOptions").get(0).asText();
                for (JsonNode opt : first.get("ticketOptions")) {
                    if (!"DOUBLE".equals(opt.asText())) { ticket = opt.asText(); break; }
                }
                api("POST", "/api/games/" + gameId + "/moves",
                        Map.of("playerId", currentId, "toNodeId", toNodeId, "ticket", ticket));
            }
            // No moves → server auto-skips; next iteration will see updated currentPlayerId.
        }
        return api("GET", "/api/games/" + gameId, null);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Lobby: create → join → state reflects both players before start")
    void lobby_createAndJoin_stateHasBothPlayers() throws Exception {
        JsonNode created = api("POST", "/api/games/create",
                Map.of("hostName", "Alice", "maxPlayers", 2));
        String gameId   = created.get("gameState").get("gameId").asText();
        String joinCode = created.get("gameState").get("joinCode").asText();

        assertThat(created.get("gameState").get("phase").asText()).isEqualTo("LOBBY");
        assertThat(created.get("playerId").asText()).isNotBlank();

        JsonNode joined = api("POST", "/api/games/join",
                Map.of("joinCode", joinCode, "playerName", "Bob"));
        assertThat(joined.get("playerId").asText()).isNotBlank();

        JsonNode state = api("GET", "/api/games/" + gameId, null);
        assertThat(state.get("players")).hasSize(2);
        assertThat(state.get("phase").asText()).isEqualTo("LOBBY");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("2-player game: plays from lobby through all rounds to a decided winner")
    void twoPlayerGame_playsLobbyToWinner() throws Exception {
        List<String> ids = createAndStartGame(2);
        String gameId    = ids.get(0);

        JsonNode started = api("GET", "/api/games/" + gameId, null);
        assertThat(started.get("phase").asText()).isEqualTo("IN_PROGRESS");
        assertThat(started.get("round").asInt()).isEqualTo(1);
        assertThat(started.get("currentPlayerId").asText()).isNotBlank();
        // Roles assigned: exactly one MR_X and one DETECTIVE
        int mrxCount = 0;
        for (JsonNode p : started.get("players")) {
            if ("MR_X".equals(p.get("role").asText())) mrxCount++;
        }
        assertThat(mrxCount).isEqualTo(1);

        JsonNode ended = playToEnd(gameId);

        assertThat(ended.get("phase").asText()).isEqualTo("ENDED");
        assertThat(ended.get("winner").asText()).isIn("MR_X", "DETECTIVES");
        assertThat(ended.get("round").asInt()).isBetween(1, 24);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("4-player game: Mr X vs 3 detectives plays from start to a winner")
    void fourPlayerGame_playsToCompletion() throws Exception {
        List<String> ids = createAndStartGame(4);
        String gameId    = ids.get(0);

        JsonNode started = api("GET", "/api/games/" + gameId, null);
        assertThat(started.get("players")).hasSize(4);
        long mrxCount = 0;
        for (JsonNode p : started.get("players")) {
            if ("MR_X".equals(p.get("role").asText())) mrxCount++;
        }
        assertThat(mrxCount).isEqualTo(1);

        JsonNode ended = playToEnd(gameId);

        assertThat(ended.get("phase").asText()).isEqualTo("ENDED");
        assertThat(ended.get("winner").asText()).isIn("MR_X", "DETECTIVES");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Mr X log: accumulates one entry per Mr X turn with correct fields")
    void mrXLog_accumulatesEntriesWithCorrectFields() throws Exception {
        List<String> ids = createAndStartGame(2);
        String gameId    = ids.get(0);

        // Play 6 half-turns (= 3 full rounds in a 2-player game).
        for (int t = 0; t < 6; t++) {
            JsonNode state = api("GET", "/api/games/" + gameId, null);
            if (!"IN_PROGRESS".equals(state.get("phase").asText())) break;

            String currentId = state.get("currentPlayerId").asText();
            JsonNode moves   = api("GET",
                    "/api/games/" + gameId + "/valid-moves?playerId=" + currentId, null);
            JsonNode list    = moves.path("moves");
            if (list.isArray() && !list.isEmpty()) {
                JsonNode first = list.get(0);
                String ticket  = first.get("ticketOptions").get(0).asText();
                for (JsonNode opt : first.get("ticketOptions")) {
                    if (!"DOUBLE".equals(opt.asText())) { ticket = opt.asText(); break; }
                }
                api("POST", "/api/games/" + gameId + "/moves",
                        Map.of("playerId", currentId,
                               "toNodeId", first.get("nodeId").asInt(),
                               "ticket", ticket));
            }
        }

        JsonNode state = api("GET", "/api/games/" + gameId, null);
        JsonNode log   = state.get("mrXLog");
        assertThat(log).isNotNull();
        // After 3 rounds, Mr X has moved at least once per round → ≥ 3 entries (or fewer if game ended early).
        assertThat(log.size()).isGreaterThanOrEqualTo(1);

        Set<Integer> revealRounds = Set.of(3, 8, 13, 18, 24);
        for (JsonNode entry : log) {
            assertThat(entry.get("round").asInt()).isGreaterThan(0);
            assertThat(entry.get("leg").asInt()).isIn(1, 2);
            assertThat(entry.get("ticketUsed").asText()).isNotBlank();
            // nodeId is revealed only on specific rounds; otherwise it must be JSON null.
            int round    = entry.get("round").asInt();
            boolean isReveal = revealRounds.contains(round);
            if (!isReveal) {
                assertThat(entry.get("nodeId").isNull())
                        .as("nodeId must be null on non-reveal round %d", round)
                        .isTrue();
            } else {
                assertThat(entry.get("nodeId").asInt()).isGreaterThan(0);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Turn enforcement: submitting a move when it is not your turn returns an error")
    void submitMove_rejectedForNonCurrentPlayer() throws Exception {
        List<String> ids = createAndStartGame(2);
        String gameId    = ids.get(0);
        String hostId    = ids.get(1);
        String otherId   = ids.get(2);

        JsonNode state   = api("GET", "/api/games/" + gameId, null);
        String currentId = state.get("currentPlayerId").asText();
        // Pick the player whose turn it is NOT.
        String notCurrentId = currentId.equals(hostId) ? otherId : hostId;

        // Get a node the current player could move to.
        JsonNode moves = api("GET",
                "/api/games/" + gameId + "/valid-moves?playerId=" + currentId, null);
        JsonNode list = moves.path("moves");
        assertThat(list.isArray() && !list.isEmpty())
                .as("Current player must have at least one valid move")
                .isTrue();

        int toNodeId = list.get(0).get("nodeId").asInt();
        String ticket = list.get(0).get("ticketOptions").get(0).asText();
        for (JsonNode opt : list.get(0).get("ticketOptions")) {
            if (!"DOUBLE".equals(opt.asText())) { ticket = opt.asText(); break; }
        }

        // Non-current player attempts the same move — must be rejected.
        JsonNode result = api("POST", "/api/games/" + gameId + "/moves",
                Map.of("playerId", notCurrentId, "toNodeId", toNodeId, "ticket", ticket));

        assertThat(result.has("error"))
                .as("Server must return an error body when submitting out of turn")
                .isTrue();
        assertThat(result.get("error").asText()).isNotBlank();
        // Game must still be in progress (rejected move did not advance state).
        JsonNode after = api("GET", "/api/games/" + gameId, null);
        assertThat(after.get("currentPlayerId").asText()).isEqualTo(currentId);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Abort: Mr X leaving mid-game ends the game with no winner")
    void mrXLeaves_midGame_abortsWithNoWinner() throws Exception {
        List<String> ids = createAndStartGame(2);
        String gameId    = ids.get(0);

        // Find Mr X's player ID from the full unfiltered state.
        JsonNode state = api("GET", "/api/games/" + gameId, null);
        String mrXId   = null;
        for (JsonNode p : state.get("players")) {
            if ("MR_X".equals(p.get("role").asText())) {
                mrXId = p.get("id").asText();
                break;
            }
        }
        assertThat(mrXId).as("Mr X must be assigned after game start").isNotNull();

        // Mr X self-removes (leave).
        api("DELETE", "/api/games/" + gameId + "/players/" + mrXId, null);

        JsonNode ended = api("GET", "/api/games/" + gameId, null);
        assertThat(ended.get("phase").asText()).isEqualTo("ENDED");
        assertThat(ended.get("winner").isNull()).isTrue();
        assertThat(ended.get("abortReason").asText()).isNotBlank();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Full cycle: game ends with correct node positions reported per role")
    void gameEnd_playerPositionsCorrectlyReported() throws Exception {
        List<String> ids = createAndStartGame(2);
        String gameId    = ids.get(0);

        JsonNode ended = playToEnd(gameId);
        assertThat(ended.get("phase").asText()).isEqualTo("ENDED");
        String winner = ended.get("winner").asText();
        assertThat(winner).isIn("MR_X", "DETECTIVES");

        // Find Mr X's player ID from the ended-state players list
        // (id / name / role are always visible; only nodeId is filtered).
        String mrXId = null;
        for (JsonNode p : ended.get("players")) {
            if ("MR_X".equals(p.get("role").asText())) {
                mrXId = p.get("id").asText();
                break;
            }
        }
        assertThat(mrXId).isNotNull();

        // Fetch state as Mr X to get the unfiltered positions.
        JsonNode mrXView = api("GET", "/api/games/" + gameId + "?playerId=" + mrXId, null);
        Integer mrXNode = null;
        Set<Integer> detectiveNodes = new HashSet<>();
        for (JsonNode p : mrXView.get("players")) {
            if ("MR_X".equals(p.get("role").asText())) {
                mrXNode = p.get("nodeId").asInt();
            } else {
                detectiveNodes.add(p.get("nodeId").asInt());
            }
        }
        assertThat(mrXNode).as("Mr X's node must be known from their own perspective").isNotNull();

        // When detectives win they caught Mr X — at least one detective is on the same node.
        if ("DETECTIVES".equals(winner)) {
            assertThat(detectiveNodes).contains(mrXNode);
        }
        // All detectives must be on valid (> 0) nodes.
        assertThat(detectiveNodes).allSatisfy(n -> assertThat(n).isGreaterThan(0));
    }
}
