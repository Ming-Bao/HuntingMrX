package com.huntingmrxwellington.game;

import com.huntingmrxwellington.game.enums.GamePhase;
import com.huntingmrxwellington.game.enums.Role;
import com.huntingmrxwellington.game.enums.TicketType;
import com.huntingmrxwellington.game.enums.TurnPhase;
import com.huntingmrxwellington.game.enums.Winner;
import com.huntingmrxwellington.game.exception.ConflictException;
import com.huntingmrxwellington.game.exception.ForbiddenException;
import com.huntingmrxwellington.game.exception.NotFoundException;
import com.huntingmrxwellington.game.view.GameState;
import com.huntingmrxwellington.game.view.MrXMove;
import com.huntingmrxwellington.game.view.PlayerView;
import com.huntingmrxwellington.game.view.ValidMove;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static com.huntingmrxwellington.game.enums.TicketType.*;

/** One game and every rule of it: lobby, start, moves, turns, reveals, wins, leaving and
 *  the idle abort. Not thread-safe; GameService locks around every call. */
public final class Game {

    /** Longest player name allowed, after trimming. */
    public static final int MAX_NAME_LENGTH = 20;
    /** Mr X wins if he is still free when this round ends. */
    public static final int LAST_ROUND = 24;
    /** Rounds whose final Mr X leg shows his node to everyone. */
    public static final Set<Integer> REVEAL_ROUNDS = Set.of(2, 8, 13, 18, 24);
    /** Prefix on the ticket of the first leg of a double move, e.g. "DOUBLE_BUS". */
    private static final String DOUBLE_PREFIX = "DOUBLE_";

    private final String id;
    private final String joinCode;
    private final int maxPlayers;
    private final MapGraph map;
    private final Map<TicketType, Integer> detectiveTickets;
    private final InstantSource clock;

    // Lobby: join order, so the host is first. In play: Mr X first, then detectives in turn order.
    private final List<Player> players = new ArrayList<>();
    private final List<MrXMove> mrXLog = new ArrayList<>();

    private GamePhase phase = GamePhase.LOBBY;
    private int round;                  // 0 in the lobby
    private TurnPhase turnPhase;        // null outside IN_PROGRESS
    private Player current;             // whose move it is; null outside IN_PROGRESS
    private boolean doubleMovePending;
    private Winner winner;
    private String abortReason;
    /** When the current player got the turn; drives the idle abort. */
    private Instant turnStartedAt;

    /**
     * A new game in the lobby, with nobody in it yet.
     *
     * @param id the game's public id, used in URLs and topic names
     * @param joinCode the short code players type to join
     * @param maxPlayers how many players the lobby takes, 2 to 6
     * @param map the board
     * @param detectiveTickets the tickets each detective starts with
     * @param clock where turn start times come from; tests pass a fake one
     */
    public Game(String id, String joinCode, int maxPlayers, MapGraph map,
                Map<TicketType, Integer> detectiveTickets, InstantSource clock) {
        if (maxPlayers < 2 || maxPlayers > 6)
            throw new IllegalArgumentException("Max players must be between 2 and 6");
        this.id = id;
        this.joinCode = joinCode;
        this.maxPlayers = maxPlayers;
        this.map = map;
        this.detectiveTickets = Map.copyOf(detectiveTickets);
        this.clock = clock;
    }

    // ── Lobby ────────────────────────────────────────────────────────────────

    /**
     * Adds a player to the lobby. The first player to join is the host.
     *
     * @param name the player's display name; trimmed, then 1 to MAX_NAME_LENGTH characters
     * @return the new player
     */
    public Player join(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty()) throw new IllegalArgumentException("Name is required");
        if (stripped.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Name must be " + MAX_NAME_LENGTH + " characters or fewer");
        requirePhase(GamePhase.LOBBY, "Game is not in the lobby phase");
        if (players.size() >= maxPlayers) throw new ConflictException("Game is full");
        Player player = new Player(stripped);
        players.add(player);
        return player;
    }

    /** @return the host: whoever has been in the game longest (the frontend assumes the same), or null if it's empty */
    public Player host() {
        return players.isEmpty() ? null : players.get(0);
    }

    // ── Start ────────────────────────────────────────────────────────────────

    /**
     * Starts the game with a random Mr X, a random detective order and random distinct start nodes.
     *
     * @param requester the player asking to start; must be the host
     * @param rng the random source for roles, turn order and start nodes
     */
    public void start(Player requester, Random rng) {
        List<Player> order = new ArrayList<>(players);
        Collections.shuffle(order, rng);
        List<Integer> nodes = new ArrayList<>(map.nodeIds());
        if (nodes.size() < order.size())
            throw new IllegalArgumentException("The map needs at least " + order.size() + " nodes");
        Collections.shuffle(nodes, rng);
        start(requester, order, nodes.subList(0, order.size()));
    }

    /**
     * Starts the game with a fixed setup. The random start calls this, and so do tests.
     *
     * @param requester the player asking to start; must be the host
     * @param order every player once: the first becomes Mr X, the rest are detectives
     *              in this turn order
     * @param startNodes each player's start node, matching order by index
     */
    void start(Player requester, List<Player> order, List<Integer> startNodes) {
        requirePhase(GamePhase.LOBBY, "Game is not in the lobby phase");
        if (requester != host()) throw new ForbiddenException("Only the host can start the game");
        if (players.size() < 2) throw new IllegalArgumentException("Need at least 2 players to start");
        if (!new HashSet<>(order).equals(new HashSet<>(players)) || startNodes.size() != order.size())
            throw new IllegalArgumentException("order must list every player once, with one start node each");
        Map<TicketType, Integer> mrXTickets = new EnumMap<>(TicketType.class);
        for (TicketType t : List.of(ESCOOTER, BUS, TRAIN, FERRY)) mrXTickets.put(t, Player.UNLIMITED);
        mrXTickets.put(DOUBLE, 2);
        mrXTickets.put(BLACK, order.size() - 1);
        order.get(0).assign(Role.MR_X, startNodes.get(0), mrXTickets);
        for (int i = 1; i < order.size(); i++)
            order.get(i).assign(Role.DETECTIVE, startNodes.get(i), detectiveTickets);
        players.clear();
        players.addAll(order);
        phase = GamePhase.IN_PROGRESS;
        round = 1;
        giveTurnToMrX();
    }

    // ── Moves ────────────────────────────────────────────────────────────────

    /**
     * The player's legal moves from where they stand, whoever's turn it is.
     *
     * @param player the player to list moves for
     * @return one entry per reachable node, sorted by node id
     */
    public List<ValidMove> validMoves(Player player) {
        requirePhase(GamePhase.IN_PROGRESS, "Game is not in progress");
        return movesFrom(player);
    }

    /**
     * Legal moves with no phase check. Mr X can't move onto a detective; detectives may share nodes.
     *
     * @param player the player to list moves for
     * @return one entry per reachable node, sorted by node id
     */
    private List<ValidMove> movesFrom(Player player) {
        return map.validMoves(player, player.isMrX() ? detectiveNodes() : Set.of());
    }

    /**
     * Plays one leg for the current player, then passes the turn on or ends the game.
     *
     * @param player the player moving; must be the current player
     * @param to the node to move to; must be next to the player's node
     * @param ticket ESCOOTER, BUS, TRAIN, FERRY or BLACK, or DOUBLE_&lt;one of those&gt; for
     *               the first leg of Mr X's double move
     */
    public void move(Player player, int to, String ticket) {
        requirePhase(GamePhase.IN_PROGRESS, "Game is not in progress");
        if (player != current) throw new ForbiddenException("Not your turn");
        if (ticket == null) throw new IllegalArgumentException("Ticket is required");
        boolean startsDouble = ticket.startsWith(DOUBLE_PREFIX);
        TicketType leg = parseLeg(startsDouble ? ticket.substring(DOUBLE_PREFIX.length()) : ticket, ticket);
        if (startsDouble) {
            if (doubleMovePending) throw new IllegalArgumentException("A double move is already in progress");
            if (!player.has(DOUBLE)) throw new IllegalArgumentException("No DOUBLE tickets left");
        }
        Set<TicketType> modes = map.modesBetween(player.node(), to);
        if (modes.isEmpty()) throw new IllegalArgumentException("No connection between those nodes");
        if (leg != BLACK && !modes.contains(leg))
            throw new IllegalArgumentException("Ticket type " + leg + " not valid for this edge");
        if (!player.has(leg)) throw new IllegalArgumentException("No " + leg + " tickets left");
        if (player.isMrX() && detectiveNodes().contains(to))
            throw new IllegalArgumentException("Mr X cannot move to a node occupied by a detective");

        if (startsDouble) player.spend(DOUBLE);
        player.spend(leg);
        player.moveTo(to);
        if (player.isMrX()) afterMrXLeg(to, leg, startsDouble);
        else afterDetectiveMove(player);
    }

    /**
     * The ticket for one leg, from its name.
     *
     * @param name the ticket name with any DOUBLE_ prefix already removed
     * @param asSent the ticket exactly as the client sent it, for the error message
     * @return the matching ticket; never DOUBLE
     */
    private static TicketType parseLeg(String name, String asSent) {
        for (TicketType t : List.of(ESCOOTER, BUS, TRAIN, FERRY, BLACK)) if (t.name().equals(name)) return t;
        throw new IllegalArgumentException("Unknown ticket: " + asSent);
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    /** Mr X to move, unless detectives hold every node next to him: then they win. */
    private void giveTurnToMrX() {
        if (movesFrom(mrX()).isEmpty()) {
            end(Winner.DETECTIVES, null);
            return;
        }
        turnPhase = TurnPhase.MR_X_TURN;
        setCurrent(mrX());
    }

    /**
     * Logs Mr X's leg (with his node on a reveal round's final leg), then either keeps
     * the turn for the second leg of a double or passes it to the first detective.
     *
     * @param to the node Mr X moved to
     * @param leg the ticket he paid with
     * @param startsDouble whether this was the first leg of a double move
     */
    private void afterMrXLeg(int to, TicketType leg, boolean startsDouble) {
        Integer revealed = !startsDouble && REVEAL_ROUNDS.contains(round) ? to : null;
        mrXLog.add(new MrXMove(round, doubleMovePending ? 2 : 1, leg, revealed, startsDouble));
        doubleMovePending = startsDouble;
        if (startsDouble) setCurrent(current);   // a fresh clock for the second leg
        else giveTurnToDetectiveFrom(0);
    }

    /**
     * Detectives win if this detective landed on Mr X; otherwise the next detective moves.
     *
     * @param detective the detective who just moved
     */
    private void afterDetectiveMove(Player detective) {
        if (detective.node().equals(mrX().node())) {
            end(Winner.DETECTIVES, null);
            return;
        }
        giveTurnToDetectiveFrom(detectives().indexOf(detective) + 1);
    }

    /**
     * Gives the turn to the first detective at or after a turn-order position who can move.
     * If none can, the round ends.
     *
     * @param index the turn-order position to start looking from
     */
    private void giveTurnToDetectiveFrom(int index) {
        List<Player> detectives = detectives();
        for (int i = index; i < detectives.size(); i++) {
            if (!movesFrom(detectives.get(i)).isEmpty()) {
                turnPhase = TurnPhase.DETECTIVE_TURN;
                setCurrent(detectives.get(i));
                return;
            }
        }
        endRound();
    }

    /** Mr X wins after the last round; otherwise the next round starts with his turn. */
    private void endRound() {
        if (round >= LAST_ROUND) {
            end(Winner.MR_X, null);
            return;
        }
        round++;
        giveTurnToMrX();
    }

    /**
     * Hands the turn to a player and restarts the idle clock.
     *
     * @param player the player whose turn it is now
     */
    private void setCurrent(Player player) {
        current = player;
        turnStartedAt = clock.instant();
    }

    /**
     * Ends the game.
     *
     * @param winner the side that won, or null if the game was aborted
     * @param abortReason why the game was aborted, or null if someone won
     */
    private void end(Winner winner, String abortReason) {
        phase = GamePhase.ENDED;
        this.winner = winner;
        this.abortReason = abortReason;
        current = null;
        turnPhase = null;
        doubleMovePending = false;
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    /**
     * Removes a player. The host leaving the lobby closes it, and anyone leaving a game in
     * progress ends it.
     *
     * @param player the player leaving
     */
    public void leave(Player player) {
        boolean wasHost = player == host();
        players.remove(player);
        if (phase == GamePhase.LOBBY && wasHost && !players.isEmpty()) {
            end(null, "The host left the game");
        } else if (phase == GamePhase.IN_PROGRESS) {
            end(null, (player.isMrX() ? "Mr. X" : player.name()) + " has left the game");
        }
    }

    /**
     * The host removes another player from the lobby.
     *
     * @param requester the player kicking; must be the host
     * @param targetId the public id of the player to remove
     */
    public void kick(Player requester, String targetId) {
        requirePhase(GamePhase.LOBBY, "Players can only be kicked during the lobby");
        if (requester != host()) throw new ForbiddenException("Only the host can kick players");
        if (requester.id().equals(targetId)) throw new IllegalArgumentException("Host cannot kick themselves");
        if (!players.removeIf(p -> p.id().equals(targetId))) throw new NotFoundException("Player not found");
    }

    /**
     * Ends the game if the current player hasn't moved within the limit.
     *
     * @param limit how long a player may take over one turn
     * @return true if this call ended the game, so the caller knows to publish the new state
     */
    public boolean abortIfIdle(Duration limit) {
        if (phase != GamePhase.IN_PROGRESS || !clock.instant().isAfter(turnStartedAt.plus(limit))) return false;
        end(null, "A player exceeded the " + limit.toMinutes() + "-minute turn limit");
        return true;
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /**
     * The game as one viewer may see it. Mr X's node is hidden from everyone but him, except
     * right after a reveal.
     *
     * @param viewer the player looking, or null for the public (detective-level) view
     * @return a snapshot safe to send to that viewer
     */
    public GameState viewFor(Player viewer) {
        boolean seesMrX = viewer != null && viewer.isMrX();
        Integer revealed = revealedNodeThisRound();
        List<PlayerView> views = players.stream()
                .map(p -> new PlayerView(p.id(), p.name(), p.role(),
                        p.isMrX() && !seesMrX ? revealed : p.node(),
                        p.role() == null ? null : p.tickets()))
                .toList();
        return new GameState(id, joinCode, phase, maxPlayers, views, round, turnPhase,
                current == null ? null : current.id(), winner, abortReason,
                List.copyOf(mrXLog), doubleMovePending);
    }

    /** @return Mr X's node if he was revealed this round, else null */
    private Integer revealedNodeThisRound() {
        Integer revealed = null;
        for (MrXMove m : mrXLog) if (m.round() == round && m.nodeId() != null) revealed = m.nodeId();
        return revealed;
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    /**
     * Looks up a player by their secret token.
     *
     * @param token the secret player token; may be null
     * @return the player holding it, or empty if nobody does
     */
    public Optional<Player> playerByToken(String token) {
        return players.stream().filter(p -> p.token().equals(token)).findFirst();
    }

    /** @return a copy of the players: join order in the lobby, then Mr X first and detectives in turn order */
    public List<Player> players() { return List.copyOf(players); }
    /** @return whose move it is; empty unless the game is in progress */
    public Optional<Player> currentPlayer() { return Optional.ofNullable(current); }
    /** @return the game's public id */
    public String id() { return id; }
    /** @return the short code players type to join */
    public String joinCode() { return joinCode; }
    /** @return where the game is in its life: lobby, in progress or ended */
    public GamePhase phase() { return phase; }
    /** @return the current round, 1 to LAST_ROUND; 0 in the lobby */
    public int round() { return round; }
    /** @return who won; null while playing and when the game was aborted */
    public Winner winner() { return winner; }
    /** @return why the game ended early; null otherwise */
    public String abortReason() { return abortReason; }
    /** @return true between the two legs of Mr X's double move */
    public boolean doubleMovePending() { return doubleMovePending; }
    /** @return a copy of Mr X's travel log, one entry per leg */
    public List<MrXMove> mrXLog() { return List.copyOf(mrXLog); }

    /** @return the player who is Mr X; only call this once the game has started */
    private Player mrX() {
        return players.stream().filter(Player::isMrX).findFirst().orElseThrow();
    }

    /** @return the detectives, in turn order */
    private List<Player> detectives() {
        return players.stream().filter(p -> !p.isMrX()).toList();
    }

    /** @return the nodes the detectives are standing on */
    private Set<Integer> detectiveNodes() {
        return detectives().stream().map(Player::node).collect(Collectors.toSet());
    }

    /**
     * Throws a ConflictException unless the game is in the expected phase.
     *
     * @param expected the phase the game must be in
     * @param message the error message if it isn't
     */
    private void requirePhase(GamePhase expected, String message) {
        if (phase != expected) throw new ConflictException(message);
    }
}
