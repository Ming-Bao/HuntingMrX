package com.huntingmrxwellington.game;

import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.huntingmrxwellington.game.TicketType.*;
// Explicit AssertJ imports: Assertions.* also brings in a DOUBLE constant that clashes with TicketType.DOUBLE.
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every rule of the game, on the hand-drawn board in TestMaps with players on exact nodes. */
class GameTest {

    static final Map<TicketType, Integer> TICKETS = Map.of(ESCOOTER, 30, BUS, 30, TRAIN, 30, FERRY, 30);
    static final String[] NAMES = {"Xavier", "Alice", "Bob", "Carol", "Dan", "Eve"};

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Game game;
    Player mrX, alice, bob;

    Game newGame(int maxPlayers, Map<TicketType, Integer> detectiveTickets) {
        return new Game("g1", "ABC123", maxPlayers, TestMaps.small(), detectiveTickets, () -> now);
    }

    Game newGame(int maxPlayers) {
        return newGame(maxPlayers, TICKETS);
    }

    /** Starts a game with Mr X on nodes[0] and detectives, in turn order, on the rest. */
    void start(int... nodes) {
        startWith(TICKETS, nodes);
    }

    void startWith(Map<TicketType, Integer> detectiveTickets, int... nodes) {
        game = newGame(6, detectiveTickets);
        List<Player> order = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) order.add(game.join(NAMES[i]));
        game.start(order.get(0), order, Arrays.stream(nodes).boxed().toList());
        mrX = order.get(0);
        alice = order.get(1);
        bob = order.size() > 2 ? order.get(2) : null;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    /** The node this view shows for player p (null when hidden). */
    static Integer nodeSeen(GameState view, Player p) {
        return view.players().stream().filter(v -> v.id().equals(p.id())).findFirst().orElseThrow().nodeId();
    }

    @Nested
    class Lobby {

        @Test
        void theFirstPlayerToJoinIsTheHost() {
            Game g = newGame(4);
            Player host = g.join("Alice");
            g.join("Bob");
            assertThat(g.host()).isSameAs(host);
        }

        @Test
        void namesAreTrimmedAndLimitedTo20Characters() {
            Game g = newGame(4);
            assertThat(g.join("  Alice  ").name()).isEqualTo("Alice");
            assertThat(g.join("x".repeat(20)).name()).hasSize(20);
            assertThatThrownBy(() -> g.join("x".repeat(21)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 characters");
        }

        @Test
        void aBlankOrMissingNameIsRejected() {
            Game g = newGame(4);
            assertThatThrownBy(() -> g.join("   ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> g.join(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void maxPlayersMustBeBetween2And6() {
            assertThatThrownBy(() -> newGame(1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> newGame(7)).isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> newGame(2)).doesNotThrowAnyException();
            assertThatCode(() -> newGame(6)).doesNotThrowAnyException();
        }

        @Test
        void aFullGameRejectsJoins() {
            Game g = newGame(2);
            g.join("Alice");
            g.join("Bob");
            assertThatThrownBy(() -> g.join("Carol")).isInstanceOf(ConflictException.class).hasMessageContaining("full");
        }

        @Test
        void aStartedGameRejectsJoins() {
            start(1, 3);
            assertThatThrownBy(() -> game.join("Late")).isInstanceOf(ConflictException.class).hasMessageContaining("lobby");
        }

        @Test
        void theLobbyViewHasNoRolesTicketsOrTurn() {
            Game g = newGame(3);
            g.join("Alice");
            GameState view = g.viewFor(null);
            assertThat(view.phase()).isEqualTo(GamePhase.LOBBY);
            assertThat(view.round()).isZero();
            assertThat(view.currentPlayerId()).isNull();
            assertThat(view.turnPhase()).isNull();
            assertThat(view.players()).singleElement().satisfies(p -> {
                assertThat(p.role()).isNull();
                assertThat(p.tickets()).isNull();
                assertThat(p.nodeId()).isNull();
            });
        }

        @Test
        void playersAreFoundByTokenOnly() {
            Game g = newGame(3);
            Player alice = g.join("Alice");
            assertThat(g.playerByToken(alice.token())).contains(alice);
            assertThat(g.playerByToken(alice.id())).isEmpty();
            assertThat(g.playerByToken(null)).isEmpty();
        }
    }

    @Nested
    class Start {

        @Test
        void onlyTheHostCanStart() {
            Game g = newGame(3);
            g.join("Host");
            Player guest = g.join("Guest");
            assertThatThrownBy(() -> g.start(guest, new Random(1))).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void startingNeedsTwoPlayers() {
            Game g = newGame(3);
            Player host = g.join("Host");
            assertThatThrownBy(() -> g.start(host, new Random(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 players");
        }

        @Test
        void aGameCannotStartTwice() {
            start(1, 3);
            assertThatThrownBy(() -> game.start(mrX, new Random(1))).isInstanceOf(ConflictException.class);
        }

        @Test
        void aRandomStartHasOneMrXAndDistinctNodes() {
            Game g = newGame(6);
            Player host = g.join("P0");
            for (int i = 1; i < 6; i++) g.join("P" + i);
            g.start(host, new Random(42));
            assertThat(g.players()).filteredOn(Player::isMrX).hasSize(1);
            assertThat(g.players()).filteredOn(p -> p.role() == Role.DETECTIVE).hasSize(5);
            assertThat(g.players().stream().map(Player::node).distinct()).hasSize(6);
        }

        @Test
        void mrXGetsUnlimitedTransportTwoDoublesAndOneInvisiblePerDetective() {
            start(1, 3, 6);
            assertThat(mrX.tickets()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    ESCOOTER, Player.UNLIMITED, BUS, Player.UNLIMITED, TRAIN, Player.UNLIMITED,
                    FERRY, Player.UNLIMITED, DOUBLE, 2, BLACK, 2));
        }

        @Test
        void detectivesGetTheConfiguredTicketsOnly() {
            start(1, 3);
            assertThat(alice.tickets()).containsExactlyInAnyOrderEntriesOf(TICKETS);
        }

        @Test
        void theGameStartsInRoundOneWithMrXToMove() {
            start(1, 3);
            assertThat(game.phase()).isEqualTo(GamePhase.IN_PROGRESS);
            assertThat(game.round()).isEqualTo(1);
            assertThat(game.currentPlayer()).contains(mrX);
            assertThat(game.viewFor(null).turnPhase()).isEqualTo(TurnPhase.MR_X_TURN);
        }

        @Test
        void mrXBoxedInAtTheStartLosesStraightAway() {   // B1
            start(7, 6);                                  // node 7's only neighbour is 6
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.winner()).isEqualTo(Winner.DETECTIVES);
            assertThat(game.currentPlayer()).isEmpty();
        }
    }

    @Nested
    class Views {

        @Test
        void detectivesAndThePublicNeverSeeMrXOutsideAReveal() {
            start(1, 3);
            assertThat(nodeSeen(game.viewFor(alice), mrX)).isNull();
            assertThat(nodeSeen(game.viewFor(null), mrX)).isNull();
            assertThat(nodeSeen(game.viewFor(alice), alice)).isEqualTo(3);
        }

        @Test
        void mrXSeesEveryone() {
            start(1, 3);
            GameState view = game.viewFor(mrX);
            assertThat(nodeSeen(view, mrX)).isEqualTo(1);
            assertThat(nodeSeen(view, alice)).isEqualTo(3);
        }

        @Test
        void rolesAndTicketsArePublic() {
            start(1, 3);
            GameState view = game.viewFor(null);
            assertThat(view.players()).extracting(PlayerView::role).containsExactly(Role.MR_X, Role.DETECTIVE);
            assertThat(view.players().get(1).tickets()).containsExactlyInAnyOrderEntriesOf(TICKETS);
        }

        @Test
        void viewsNeverContainAToken() throws Exception {
            start(1, 3);
            String json = new ObjectMapper().writeValueAsString(game.viewFor(mrX));
            assertThat(json).doesNotContain(mrX.token()).doesNotContain(alice.token());
        }
    }
}
