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

    @Nested
    class Moves {

        @Test
        void onlyTheCurrentPlayerMayMove() {
            start(1, 3);
            assertThatThrownBy(() -> game.move(alice, 2, "ESCOOTER"))
                    .isInstanceOf(ForbiddenException.class).hasMessage("Not your turn");
        }

        @Test
        void aLegalMoveSpendsTheTicketAndMoves() {
            start(1, 3);
            game.move(mrX, 5, "FERRY");
            game.move(alice, 4, "TRAIN");
            assertThat(alice.node()).isEqualTo(4);
            assertThat(alice.tickets().get(TRAIN)).isEqualTo(29);
            assertThat(mrX.tickets().get(FERRY)).isEqualTo(Player.UNLIMITED);
        }

        @Test
        void aMoveMustFollowAnEdge() {
            start(1, 3);
            assertThatThrownBy(() -> game.move(mrX, 4, "BUS"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No connection");
        }

        @Test
        void theTicketMustMatchTheEdge() {
            start(1, 3);
            assertThatThrownBy(() -> game.move(mrX, 5, "BUS"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid for this edge");
        }

        @Test
        void aDetectiveWithoutThatTicketIsRejected() {
            startWith(Map.of(ESCOOTER, 5, BUS, 5, TRAIN, 0, FERRY, 0), 1, 3);
            game.move(mrX, 5, "FERRY");
            assertThatThrownBy(() -> game.move(alice, 4, "TRAIN"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No TRAIN tickets");
        }

        @Test
        void unknownOrMissingTicketsAreRejected() {
            start(1, 3);
            assertThatThrownBy(() -> game.move(mrX, 2, "TAXI"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown ticket");
            assertThatThrownBy(() -> game.move(mrX, 2, null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void mrXCannotMoveOntoADetective() {
            start(1, 2, 7);
            assertThatThrownBy(() -> game.move(mrX, 2, "BUS"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occupied");
        }

        @Test
        void detectivesMayShareANode() {
            start(1, 3, 6);
            game.move(mrX, 5, "FERRY");
            game.move(alice, 2, "ESCOOTER");
            game.move(bob, 2, "BUS");
            assertThat(bob.node()).isEqualTo(2);
        }

        @Test
        void theInvisibleTicketWorksOnAnyEdgeAndIsLogged() {
            start(1, 3);
            game.move(mrX, 5, "BLACK");                         // along the FERRY edge
            assertThat(mrX.tickets().get(BLACK)).isZero();      // one detective, one Invisible ticket
            assertThat(game.mrXLog()).singleElement().extracting(MrXMove::ticketUsed).isEqualTo(BLACK);
        }

        @Test
        void detectivesHaveNoInvisibleOrDoubleTickets() {
            start(1, 3);
            game.move(mrX, 5, "FERRY");
            assertThatThrownBy(() -> game.move(alice, 4, "BLACK")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> game.move(alice, 4, "DOUBLE_TRAIN")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nobodyMovesInTheLobby() {
            Game g = newGame(3);
            Player host = g.join("Host");
            assertThatThrownBy(() -> g.move(host, 2, "BUS")).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class ValidMoves {

        @Test
        void eachReachableNodeComesWithTheTicketsThatPayForIt() {
            start(1, 3);
            assertThat(game.validMoves(mrX)).containsExactly(
                    new ValidMove(2, List.of(ESCOOTER, BUS, BLACK)),
                    new ValidMove(5, List.of(FERRY, BLACK)));
        }

        @Test
        void detectivesBlockMrXButNotEachOther() {
            start(1, 2, 7);
            assertThat(game.validMoves(mrX)).extracting(ValidMove::nodeId).containsExactly(5);
        }

        @Test
        void usedUpTicketsAreNotOffered() {
            startWith(Map.of(ESCOOTER, 0, BUS, 5, TRAIN, 0, FERRY, 0), 5, 1);
            game.move(mrX, 4, "BUS");
            assertThat(game.validMoves(alice)).containsExactly(new ValidMove(2, List.of(BUS)));
        }
    }

    @Nested
    class Turns {

        @Test
        void detectivesMoveInTurnOrderThenANewRoundStarts() {
            start(1, 3, 6);
            game.move(mrX, 5, "FERRY");
            assertThat(game.currentPlayer()).contains(alice);
            game.move(alice, 4, "TRAIN");
            assertThat(game.currentPlayer()).contains(bob);
            game.move(bob, 7, "ESCOOTER");
            assertThat(game.round()).isEqualTo(2);
            assertThat(game.currentPlayer()).contains(mrX);
            assertThat(game.viewFor(null).turnPhase()).isEqualTo(TurnPhase.MR_X_TURN);
        }

        @Test
        void aDetectiveWithNoLegalMoveIsSkippedWithoutSpendingTickets() {
            startWith(Map.of(ESCOOTER, 0, BUS, 5, TRAIN, 0, FERRY, 0), 1, 3, 6);   // Alice on 3 is stuck
            game.move(mrX, 5, "FERRY");
            assertThat(game.currentPlayer()).contains(bob);
            assertThat(alice.tickets().get(BUS)).isEqualTo(5);
        }

        @Test
        void whenNoDetectiveCanMoveTheRoundEnds() {
            startWith(Map.of(ESCOOTER, 0, BUS, 0, TRAIN, 0, FERRY, 0), 1, 3);
            game.move(mrX, 5, "FERRY");
            assertThat(game.round()).isEqualTo(2);
            assertThat(game.currentPlayer()).contains(mrX);
        }
    }

    @Nested
    class DoubleMoves {

        @Test
        void aDoubleMoveGivesMrXTwoLegsBeforeTheDetectives() {
            start(1, 7);
            game.move(mrX, 2, "DOUBLE_BUS");
            assertThat(game.currentPlayer()).contains(mrX);
            assertThat(game.doubleMovePending()).isTrue();
            game.move(mrX, 3, "ESCOOTER");
            assertThat(game.doubleMovePending()).isFalse();
            assertThat(game.currentPlayer()).contains(alice);
            assertThat(mrX.tickets().get(DOUBLE)).isEqualTo(1);
            assertThat(game.mrXLog()).containsExactly(
                    new MrXMove(1, 1, BUS, null, true),
                    new MrXMove(1, 2, ESCOOTER, null, false));
        }

        @Test
        void aSecondDoubleDuringADoubleIsRejected() {   // B4
            start(1, 7);
            game.move(mrX, 2, "DOUBLE_BUS");
            assertThatThrownBy(() -> game.move(mrX, 3, "DOUBLE_ESCOOTER"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already in progress");
            assertThat(mrX.tickets().get(DOUBLE)).isEqualTo(1);
        }

        @Test
        void aPlainDoubleTicketIsRejected() {   // B5
            start(1, 7);
            assertThatThrownBy(() -> game.move(mrX, 2, "DOUBLE"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown ticket");
            assertThat(mrX.tickets().get(DOUBLE)).isEqualTo(2);
        }

        @Test
        void aDoubleLegStillHasToMatchTheEdgeAndARejectedMoveSpendsNothing() {
            start(1, 7);
            assertThatThrownBy(() -> game.move(mrX, 5, "DOUBLE_BUS")).isInstanceOf(IllegalArgumentException.class);
            assertThat(mrX.tickets().get(DOUBLE)).isEqualTo(2);
        }

        @Test
        void theInvisibleTicketCanPayForADoubleLeg() {
            start(1, 7);
            game.move(mrX, 5, "DOUBLE_BLACK");
            assertThat(mrX.tickets().get(BLACK)).isZero();
            assertThat(game.mrXLog().get(0).ticketUsed()).isEqualTo(BLACK);
        }

        @Test
        void aThirdDoubleIsRejectedOnceBothAreUsed() {
            start(1, 7);
            game.move(mrX, 2, "DOUBLE_BUS");
            game.move(mrX, 1, "BUS");                       // round 1: 1 -> 2 -> 1
            game.move(alice, 6, "ESCOOTER");
            game.move(mrX, 5, "DOUBLE_FERRY");
            game.move(mrX, 4, "BUS");                       // round 2: 1 -> 5 -> 4
            game.move(alice, 7, "ESCOOTER");
            assertThatThrownBy(() -> game.move(mrX, 3, "DOUBLE_TRAIN"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No DOUBLE");
        }
    }

    @Nested
    class Reveals {

        @Test
        void aRevealRoundShowsMrXUntilTheRoundEnds() {
            start(1, 7);
            game.move(mrX, 5, "FERRY");
            game.move(alice, 6, "ESCOOTER");                            // round 1: hidden
            assertThat(game.mrXLog().get(0).nodeId()).isNull();
            game.move(mrX, 4, "BUS");                                   // round 2: revealed
            assertThat(game.mrXLog().get(1).nodeId()).isEqualTo(4);
            assertThat(nodeSeen(game.viewFor(alice), mrX)).isEqualTo(4);
            game.move(alice, 7, "ESCOOTER");                            // round 3 begins
            assertThat(nodeSeen(game.viewFor(alice), mrX)).isNull();
        }

        @Test
        void onADoubleMoveOnlyTheFinalLegIsRevealed() {
            start(1, 7);
            game.move(mrX, 5, "FERRY");
            game.move(alice, 6, "ESCOOTER");
            game.move(mrX, 1, "DOUBLE_FERRY");                          // round 2: 5 -> 1 -> 2
            game.move(mrX, 2, "BUS");
            assertThat(game.mrXLog().subList(1, 3)).extracting(MrXMove::nodeId).containsExactly(null, 2);
        }
    }

    @Nested
    class Endings {

        @Test
        void aDetectiveLandingOnMrXWins() {
            start(1, 3);
            game.move(mrX, 2, "BUS");
            game.move(alice, 2, "ESCOOTER");
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.winner()).isEqualTo(Winner.DETECTIVES);
            assertThat(game.currentPlayer()).isEmpty();
        }

        @Test
        void mrXWithNoLegalMoveAtTheStartOfHisTurnLoses() {   // B1
            start(6, 2);
            game.move(mrX, 7, "ESCOOTER");
            game.move(alice, 6, "BUS");                        // Mr X's only way out is now held
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.winner()).isEqualTo(Winner.DETECTIVES);
            assertThat(game.round()).isEqualTo(2);
        }

        @Test
        void mrXSurvivingRound24WinsAndIsRevealedOnTheRevealRounds() {
            start(4, 7);                                       // Mr X shuttles 4 <-> 5, Alice 7 <-> 6
            for (int round = 1; round <= 24; round++) {
                assertThat(game.winner()).isNull();
                game.move(mrX, round % 2 == 1 ? 5 : 4, "BUS");
                game.move(alice, round % 2 == 1 ? 6 : 7, "ESCOOTER");
            }
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.winner()).isEqualTo(Winner.MR_X);
            assertThat(game.round()).isEqualTo(24);
            assertThat(game.mrXLog()).hasSize(24);
            assertThat(game.mrXLog()).filteredOn(m -> m.nodeId() != null)
                    .extracting(MrXMove::round).containsExactly(2, 8, 13, 18, 24);
        }
    }

    @Nested
    class Leaving {

        @Test
        void aNonHostLeavingTheLobbyIsJustRemoved() {
            Game g = newGame(4);
            Player host = g.join("Host");
            Player guest = g.join("Guest");
            assertThat(g.leave(guest)).isFalse();
            assertThat(g.players()).containsExactly(host);
            assertThat(g.phase()).isEqualTo(GamePhase.LOBBY);
        }

        @Test
        void theHostLeavingTheLobbyEndsItForEveryone() {   // B6
            Game g = newGame(4);
            Player host = g.join("Host");
            g.join("Guest");
            assertThat(g.leave(host)).isFalse();
            assertThat(g.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(g.abortReason()).isEqualTo("The host left the game");
        }

        @Test
        void theLastPlayerLeavingReportsTheGameIsEmpty() {
            Game g = newGame(4);
            Player host = g.join("Host");
            assertThat(g.leave(host)).isTrue();
        }

        @Test
        void aDetectiveLeavingOnTheirTurnEndsTheGame() {   // B3
            start(1, 3, 6);
            game.move(mrX, 5, "FERRY");                     // Alice's turn
            game.leave(alice);
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.abortReason()).isEqualTo("Alice has left the game");
            assertThat(game.currentPlayer()).isEmpty();
            assertThat(game.players()).doesNotContain(alice);
        }

        @Test
        void aDetectiveLeavingOffTheirTurnAlsoEndsTheGame() {
            start(1, 3, 6);
            game.move(mrX, 5, "FERRY");                     // Alice's turn; Bob leaves
            game.leave(bob);
            assertThat(game.abortReason()).isEqualTo("Bob has left the game");
        }

        @Test
        void mrXLeavingMidDoubleEndsTheGameWithNoWinner() {
            start(1, 7);
            game.move(mrX, 2, "DOUBLE_BUS");
            game.leave(mrX);
            assertThat(game.abortReason()).isEqualTo("Mr. X has left the game");
            assertThat(game.winner()).isNull();
            assertThat(game.doubleMovePending()).isFalse();
        }

        @Test
        void leavingAnEndedGameJustRemovesThePlayer() {
            start(1, 3);
            game.leave(mrX);
            assertThat(game.leave(alice)).isTrue();
            assertThat(game.abortReason()).isEqualTo("Mr. X has left the game");
        }
    }

    @Nested
    class Kicking {

        @Test
        void theHostCanKickInTheLobby() {
            Game g = newGame(4);
            Player host = g.join("Host");
            Player guest = g.join("Guest");
            g.kick(host, guest.id());
            assertThat(g.players()).containsExactly(host);
        }

        @Test
        void onlyTheHostCanKick() {
            Game g = newGame(4);
            Player host = g.join("Host");
            Player guest = g.join("Guest");
            assertThatThrownBy(() -> g.kick(guest, host.id())).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void theHostCannotKickThemselves() {
            Game g = newGame(4);
            Player host = g.join("Host");
            assertThatThrownBy(() -> g.kick(host, host.id()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("themselves");
        }

        @Test
        void kickingAnUnknownPlayerIsNotFound() {
            Game g = newGame(4);
            Player host = g.join("Host");
            assertThatThrownBy(() -> g.kick(host, "nobody")).isInstanceOf(GameNotFoundException.class);
        }

        @Test
        void nobodyCanBeKickedOnceTheGameStarts() {   // B2: a detective can't remove Mr X
            start(1, 3);
            assertThatThrownBy(() -> game.kick(alice, mrX.id())).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> game.kick(mrX, alice.id())).isInstanceOf(ConflictException.class);
            assertThat(game.phase()).isEqualTo(GamePhase.IN_PROGRESS);
        }
    }

    @Nested
    class IdleAbort {

        final Duration limit = Duration.ofMinutes(15);

        @Test
        void theGameEndsWhenTheCurrentPlayerIdlesPastTheLimit() {
            start(1, 3);
            advance(limit);
            assertThat(game.abortIfIdle(limit)).isFalse();       // exactly at the limit is still fine
            advance(Duration.ofSeconds(1));
            assertThat(game.abortIfIdle(limit)).isTrue();
            assertThat(game.phase()).isEqualTo(GamePhase.ENDED);
            assertThat(game.abortReason()).isEqualTo("A player exceeded the 15-minute turn limit");
        }

        @Test
        void everyNewTurnRestartsTheClock() {
            start(1, 3);
            advance(Duration.ofMinutes(10));
            game.move(mrX, 5, "FERRY");                          // Alice's turn starts now
            advance(Duration.ofMinutes(10));
            assertThat(game.abortIfIdle(limit)).isFalse();
        }

        @Test
        void theSecondLegOfADoubleGetsAFreshClock() {
            start(1, 7);
            advance(Duration.ofMinutes(10));
            game.move(mrX, 2, "DOUBLE_BUS");
            advance(Duration.ofMinutes(10));
            assertThat(game.abortIfIdle(limit)).isFalse();
        }

        @Test
        void lobbiesAreNeverIdleAborted() {
            Game g = newGame(3);
            g.join("Host");
            advance(Duration.ofDays(1));
            assertThat(g.abortIfIdle(limit)).isFalse();
        }
    }
}
