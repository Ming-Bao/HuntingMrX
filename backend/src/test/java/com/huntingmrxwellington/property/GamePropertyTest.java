package com.huntingmrxwellington.property;

import com.huntingmrxwellington.game.Game;
import com.huntingmrxwellington.game.MapGraph;
import com.huntingmrxwellington.game.Player;
import com.huntingmrxwellington.game.enums.GamePhase;
import com.huntingmrxwellington.game.enums.TicketType;
import com.huntingmrxwellington.game.exception.ConflictException;
import com.huntingmrxwellington.game.exception.ForbiddenException;
import com.huntingmrxwellington.game.view.MrXMove;
import com.huntingmrxwellington.game.view.ValidMove;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static com.huntingmrxwellington.game.enums.TicketType.BUS;
import static com.huntingmrxwellington.game.enums.TicketType.DOUBLE;
import static com.huntingmrxwellington.game.enums.TicketType.ESCOOTER;
import static com.huntingmrxwellington.game.enums.TicketType.FERRY;
import static com.huntingmrxwellington.game.enums.TicketType.TRAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Property-based and fuzz tests of the rules engine on the real Wellington map. Each property
 *  runs once per seed, so runs are repeatable and a failure names the seed that reproduces it. */
class GamePropertyTest {

    static final int SEEDS = 300;
    static final MapGraph WELLINGTON = load("map.json");
    static final Map<TicketType, Integer> TICKETS = Map.of(ESCOOTER, 12, BUS, 8, TRAIN, 6, FERRY, 2);
    static final String[] TICKET_SAMPLES = {"ESCOOTER", "BUS", "TRAIN", "FERRY", "BLACK", "DOUBLE",
            "DOUBLE_BUS", "DOUBLE_BLACK", "DOUBLE_DOUBLE", "bus", "", null};

    /** One run of a property for one seed. */
    interface SeedCheck {
        void run(long seed) throws Exception;
    }

    /** Random legal games with 2 to 6 players, sometimes using double moves: every rule holds after
     *  every move, whoever's turn it is can always move, and every game ends within 24 rounds. */
    @Test
    void randomLegalGamesKeepEveryRuleAndAlwaysEnd() {
        forEachSeed(SEEDS, seed -> {
            Random rng = new Random(seed);
            int playerCount = 2 + rng.nextInt(5);
            Game game = startedGame(playerCount, rng);
            int moves = 0;
            while (game.phase() == GamePhase.IN_PROGRESS) {
                assertThat(++moves).isLessThanOrEqualTo(Game.LAST_ROUND * (playerCount + 1));
                Player player = game.currentPlayer().orElseThrow();
                List<ValidMove> options = game.validMoves(player);
                assertThat(options).isNotEmpty();
                ValidMove move = options.get(rng.nextInt(options.size()));
                TicketType ticket = move.ticketOptions().get(rng.nextInt(move.ticketOptions().size()));
                boolean asDouble = player.isMrX() && !game.doubleMovePending() && player.has(DOUBLE) && rng.nextInt(8) == 0;
                game.move(player, move.nodeId(), (asDouble ? "DOUBLE_" : "") + ticket);
                checkRules(game);
            }
            assertThat(game.winner()).isNotNull();
            assertThat(game.abortReason()).isNull();
        });
    }

    /** Fuzz: random, mostly invalid moves by random players. The engine only ever answers with the
     *  three expected errors, and a rejected move never breaks a rule. */
    @Test
    void garbageMovesOnlyRaiseTheExpectedErrors() {
        forEachSeed(SEEDS, seed -> {
            Random rng = new Random(seed);
            Game game = startedGame(3, rng);
            for (int i = 0; i < 40 && game.phase() == GamePhase.IN_PROGRESS; i++) {
                Player player = game.players().get(rng.nextInt(game.players().size()));
                String ticket = rng.nextInt(3) == 0 ? randomString(rng, 12)
                        : TICKET_SAMPLES[rng.nextInt(TICKET_SAMPLES.length)];
                try {
                    game.move(player, rng.nextInt(332) - 1, ticket);   // node -1 to 330
                } catch (IllegalArgumentException | ForbiddenException | ConflictException expected) {
                    // rejected cleanly
                }
                checkRules(game);
            }
        });
    }

    /** Fuzz: any name, control and non-ASCII characters included, either joins (stripped,
     *  1 to 20 characters) or is rejected cleanly. */
    @Test
    void anyNameJoinsCleanlyOrIsRejected() {
        forEachSeed(SEEDS, seed -> {
            String name = randomString(new Random(seed), 30);
            Game game = new Game("g", "CODE00", 6, WELLINGTON, TICKETS, InstantSource.system());
            try {
                Player p = game.join(name);
                assertThat(p.name()).isEqualTo(name.strip()).isNotBlank().hasSizeLessThanOrEqualTo(Game.MAX_NAME_LENGTH);
            } catch (IllegalArgumentException expected) {
                // blank or too long
            }
        });
    }

    /** Runs the check once for each seed from 1 to seeds; a failure names the seed so it can be replayed. */
    static void forEachSeed(int seeds, SeedCheck check) {
        for (long seed = 1; seed <= seeds; seed++) {
            long s = seed;
            assertThatCode(() -> check.run(s)).as("seed %d", s).doesNotThrowAnyException();
        }
    }

    /** Up to maxLength characters: control characters, several kinds of whitespace, letters, and
     *  anything else below the surrogate range. */
    static String randomString(Random rng, int maxLength) {
        StringBuilder s = new StringBuilder();
        for (int i = rng.nextInt(maxLength + 1); i > 0; i--) {
            s.append(switch (rng.nextInt(4)) {
                case 0 -> (char) rng.nextInt(0x20);
                case 1 -> " \t\n  ".charAt(rng.nextInt(5));
                case 2 -> (char) ('a' + rng.nextInt(26));
                default -> (char) rng.nextInt(0xD800);
            });
        }
        return s.toString();
    }

    /** The rules every game state must satisfy. */
    static void checkRules(Game game) {
        List<Player> players = game.players();
        Player mrX = players.stream().filter(Player::isMrX).findFirst().orElseThrow();
        Set<Integer> detectiveNodes = players.stream().filter(p -> !p.isMrX()).map(Player::node).collect(Collectors.toSet());
        if (game.phase() == GamePhase.IN_PROGRESS) {
            assertThat(detectiveNodes).doesNotContain(mrX.node());
            assertThat(game.currentPlayer()).isPresent();
            assertThat(game.round()).isBetween(1, Game.LAST_ROUND);
        } else {
            assertThat(game.currentPlayer()).isEmpty();
        }
        for (TicketType t : List.of(ESCOOTER, BUS, TRAIN, FERRY))
            assertThat(mrX.tickets().get(t)).isEqualTo(Player.UNLIMITED);
        for (Player p : players)
            p.tickets().forEach((ticket, count) -> {
                if (count != Player.UNLIMITED) assertThat(count).isNotNegative();
            });
        // Hidden movement: detectives see Mr X only once he's been revealed this round.
        boolean revealed = game.mrXLog().stream().anyMatch(m -> m.round() == game.round() && m.nodeId() != null);
        for (Player p : players) {
            if (p.isMrX()) continue;
            Integer seen = game.viewFor(p).players().stream()
                    .filter(v -> v.id().equals(mrX.id())).findFirst().orElseThrow().nodeId();
            assertThat(seen).isEqualTo(revealed ? mrX.node() : null);
        }
        for (MrXMove m : game.mrXLog())
            if (m.nodeId() != null) assertThat(Game.REVEAL_ROUNDS).contains(m.round());
    }

    static Game startedGame(int playerCount, Random rng) {
        Game game = new Game("g", "CODE00", playerCount, WELLINGTON, TICKETS, InstantSource.system());
        Player host = game.join("P0");
        for (int i = 1; i < playerCount; i++) game.join("P" + i);
        game.start(host, rng);
        return game;
    }

    static MapGraph load(String file) {
        try {
            return MapGraph.parse(new ClassPathResource("static/" + file).getContentAsByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
