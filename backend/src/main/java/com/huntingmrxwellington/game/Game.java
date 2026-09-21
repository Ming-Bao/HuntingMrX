package com.huntingmrxwellington.game;

import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;

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

import static com.huntingmrxwellington.game.TicketType.*;

/** One game and every rule of it: lobby, start, moves, turns, reveals, wins, leaving and
 *  the idle abort. Not thread-safe; GameService locks around every call. */
public final class Game {

    public static final int MAX_NAME_LENGTH = 20;
    public static final int LAST_ROUND = 24;
    public static final Set<Integer> REVEAL_ROUNDS = Set.of(2, 8, 13, 18, 24);

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
    private Instant turnStartedAt;

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

    /** Adds a player to the lobby. The first player to join is the host. */
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

    /** Whoever has been in the lobby longest, which is also what the frontend assumes. */
    public Player host() {
        return players.isEmpty() ? null : players.get(0);
    }

    // ── Start ────────────────────────────────────────────────────────────────

    /** The host starts the game: random Mr X, random detective order, random distinct start nodes. */
    public void start(Player requester, Random rng) {
        checkCanStart(requester);
        List<Player> order = new ArrayList<>(players);
        Collections.shuffle(order, rng);
        List<Integer> nodes = new ArrayList<>(map.nodeIds());
        if (nodes.size() < order.size())
            throw new IllegalArgumentException("The map needs at least " + order.size() + " nodes");
        Collections.shuffle(nodes, rng);
        start(requester, order, nodes.subList(0, order.size()));
    }

    /** Deterministic start, which tests call directly: order.get(0) becomes Mr X, the rest are
     *  detectives in that turn order, and order.get(i) starts on startNodes.get(i). */
    void start(Player requester, List<Player> order, List<Integer> startNodes) {
        checkCanStart(requester);
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

    private void checkCanStart(Player requester) {
        requirePhase(GamePhase.LOBBY, "Game is not in the lobby phase");
        if (requester != host()) throw new ForbiddenException("Only the host can start the game");
        if (players.size() < 2) throw new IllegalArgumentException("Need at least 2 players to start");
    }

    // ── Moves ────────────────────────────────────────────────────────────────

    /** The player's legal moves from where they stand, whoever's turn it is. */
    public List<ValidMove> validMoves(Player player) {
        requirePhase(GamePhase.IN_PROGRESS, "Game is not in progress");
        return movesFrom(player);
    }

    private List<ValidMove> movesFrom(Player player) {
        return map.validMoves(player, player.isMrX() ? detectiveNodes() : Set.of());
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

    private void setCurrent(Player player) {
        current = player;
        turnStartedAt = clock.instant();
    }

    private void end(Winner winner, String abortReason) {
        phase = GamePhase.ENDED;
        this.winner = winner;
        this.abortReason = abortReason;
        current = null;
        turnPhase = null;
        doubleMovePending = false;
    }

    // ── Views ────────────────────────────────────────────────────────────────

    /** What the viewer may see; a null viewer gets the public (detective-level) view. */
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

    /** Mr X's node if he was revealed this round, else null. */
    private Integer revealedNodeThisRound() {
        Integer revealed = null;
        for (MrXMove m : mrXLog) if (m.round() == round && m.nodeId() != null) revealed = m.nodeId();
        return revealed;
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    public Optional<Player> playerByToken(String token) {
        return players.stream().filter(p -> p.token().equals(token)).findFirst();
    }

    public List<Player> players() { return List.copyOf(players); }
    public Optional<Player> currentPlayer() { return Optional.ofNullable(current); }
    public String id() { return id; }
    public String joinCode() { return joinCode; }
    public GamePhase phase() { return phase; }
    public int round() { return round; }
    public Winner winner() { return winner; }
    public String abortReason() { return abortReason; }
    public boolean doubleMovePending() { return doubleMovePending; }
    public List<MrXMove> mrXLog() { return List.copyOf(mrXLog); }

    private Player mrX() {
        return players.stream().filter(Player::isMrX).findFirst().orElseThrow();
    }

    private List<Player> detectives() {
        return players.stream().filter(p -> !p.isMrX()).toList();
    }

    private Set<Integer> detectiveNodes() {
        return detectives().stream().map(Player::node).collect(Collectors.toSet());
    }

    private void requirePhase(GamePhase expected, String message) {
        if (phase != expected) throw new ConflictException(message);
    }
}
