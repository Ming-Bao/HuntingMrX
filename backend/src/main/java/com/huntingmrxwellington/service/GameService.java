package com.huntingmrxwellington.service;

import com.huntingmrxwellington.dto.*;
import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GameService {

    private static final String JOIN_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int JOIN_CODE_LENGTH = 6;
    private static final Set<Integer> REVEAL_ROUNDS = Set.of(2, 8, 13, 18, 24);
    private static final long TURN_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final int MAX_NAME_LENGTH = 20;

    private final SecureRandom random = new SecureRandom();

    @Value("${game.detective-escooter-tickets:10}") private int escooterTickets;
    @Value("${game.detective-bus-tickets:8}")        private int busTickets;
    @Value("${game.detective-train-tickets:4}")      private int trainTickets;
    @Value("${game.detective-ferry-tickets:2}")      private int ferryTickets;

    // In-memory only — games don't outlive the process, so there's no reason
    // for a swappable-persistence Repository layer around this map.
    private final Map<String, GameSession> games = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;
    private final MapGraph mapGraph;

    public GameService(SimpMessagingTemplate messaging, MapGraph mapGraph) {
        this.messaging = messaging;
        this.mapGraph = mapGraph;
    }

    // ── Public records ────────────────────────────────────────────────────────

    public record CreateResult(String playerId, GameState gameState) {}
    public record JoinResult(String playerId, GameState gameState) {}

    // ── Lobby operations ─────────────────────────────────────────────────────

    public CreateResult createGame(String hostName, int maxPlayers) {
        if (hostName == null || hostName.isBlank())
            throw new IllegalArgumentException("Game not created");
        if (hostName.trim().length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Name must be " + MAX_NAME_LENGTH + " characters or fewer");
        if (maxPlayers < 2 || maxPlayers > 6)
            throw new IllegalArgumentException("Game not created");

        String gameId   = UUID.randomUUID().toString();
        String playerId = UUID.randomUUID().toString();

        GameSession session = new GameSession();
        session.setId(gameId);
        session.setJoinCode(generateJoinCode());
        session.setPhase(GamePhase.LOBBY);
        session.setMaxPlayers(maxPlayers);
        session.setHostPlayerId(playerId);
        session.getPlayers().add(new LobbyPlayer(playerId, hostName.trim()));

        games.put(gameId, session);
        return new CreateResult(playerId, toStateUnfiltered(session));
    }

    public JoinResult joinGame(String joinCode, String playerName) {
        if (joinCode == null || joinCode.isBlank())
            throw new IllegalArgumentException("Join code is required");
        if (playerName == null || playerName.isBlank())
            throw new IllegalArgumentException("Player name is required");
        if (playerName.trim().length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Name must be " + MAX_NAME_LENGTH + " characters or fewer");

        GameSession session = findByJoinCode(joinCode.toUpperCase().trim());

        if (session.getPhase() != GamePhase.LOBBY)
            throw new ConflictException("Game is not in the lobby phase");
        if (session.getPlayers().size() >= session.getMaxPlayers())
            throw new ConflictException("Game is full");

        String playerId = UUID.randomUUID().toString();
        session.getPlayers().add(new LobbyPlayer(playerId, playerName.trim()));
        broadcastShared(session);

        return new JoinResult(playerId, toStateUnfiltered(session));
    }

    public GameState getGame(String gameId) { return getGame(gameId, null); }

    public GameState getGame(String gameId, String viewingPlayerId) {
        GameSession session = requireSession(gameId);
        return viewingPlayerId != null
                ? toStateForPlayer(session, viewingPlayerId)
                : toStateUnfiltered(session);
    }

    public GameState startGame(String gameId, String requestingPlayerId) {
        GameSession session = requireSession(gameId);

        if (!requestingPlayerId.equals(session.getHostPlayerId()))
            throw new ForbiddenException("Only the host can start the game");
        if (session.getPhase() != GamePhase.LOBBY)
            throw new ConflictException("Game is not in the lobby phase");
        if (session.getPlayers().size() < 2)
            throw new IllegalArgumentException("Need at least 2 players to start");

        List<Player> lobby = new ArrayList<>(session.getPlayers());
        Collections.shuffle(lobby, random);

        int detectiveCount = lobby.size() - 1;
        List<Player> assigned = new ArrayList<>();
        assigned.add(new MrXPlayer(lobby.get(0).getId(), lobby.get(0).getName(), detectiveCount));
        for (int i = 1; i < lobby.size(); i++) {
            Player p = lobby.get(i);
            assigned.add(new DetectivePlayer(p.getId(), p.getName(),
                    escooterTickets, busTickets, trainTickets, ferryTickets));
        }
        session.setPlayers(assigned);

        // Assign distinct random starting nodes
        List<Integer> startNodes = mapGraph.randomNodes(assigned.size(), random);
        for (int i = 0; i < assigned.size(); i++) {
            assigned.get(i).setNodeId(startNodes.get(i));
        }

        session.setPhase(GamePhase.IN_PROGRESS);
        session.setRound(1);
        session.setTurnPhase(TurnPhase.MR_X_TURN);
        session.setCurrentPlayerId(session.getMrX().getId());
        session.setCurrentDetectiveIndex(0);
        resetTurnTimer(session);

        // Shared broadcast so LobbyView clients detect IN_PROGRESS and navigate
        broadcastShared(session);
        // Per-player filtered broadcasts for GameBoardView
        broadcastToAllPlayers(session);
        // Push valid moves to Mr X immediately
        pushValidMoves(session, session.getMrX().getId());

        return toStateForPlayer(session, requestingPlayerId);
    }

    public void leaveGame(String gameId, String playerId) {
        GameSession session = requireSession(gameId);

        Player leaving = findPlayer(session, playerId);

        if (session.getPhase() == GamePhase.IN_PROGRESS && leaving instanceof MrXPlayer) {
            session.setPhase(GamePhase.ENDED);
            session.setAbortReason("Mr. X has left the game");
            session.getPlayers().remove(leaving);
            broadcastToAllPlayers(session);
            return;
        }

        session.getPlayers().remove(leaving);
        if (session.getPlayers().isEmpty()
                || (session.getPhase() == GamePhase.LOBBY && playerId.equals(session.getHostPlayerId()))) {
            games.remove(gameId);
            return;
        }
        broadcastShared(session);
    }

    public void kickPlayer(String gameId, String hostId, String targetPlayerId) {
        GameSession session = requireSession(gameId);

        if (!hostId.equals(session.getHostPlayerId()))
            throw new ForbiddenException("Only the host can kick players");
        if (session.getPhase() != GamePhase.LOBBY)
            throw new ConflictException("Players can only be kicked during the lobby");
        if (hostId.equals(targetPlayerId))
            throw new IllegalArgumentException("Host cannot kick themselves");

        if (!session.getPlayers().removeIf(p -> p.getId().equals(targetPlayerId)))
            throw new GameNotFoundException("Game or player not found");

        broadcastShared(session);
    }

    // ── In-progress operations ────────────────────────────────────────────────

    public List<ValidMove> getValidMoves(String gameId, String playerId) {
        GameSession session = requireSession(gameId);
        if (session.getPhase() != GamePhase.IN_PROGRESS)
            throw new ConflictException("Game is not in progress");

        Player player = findPlayer(session, playerId);
        return computeValidMoves(session, player);
    }

    public GameState submitMove(String gameId, String playerId, int toNodeId, String ticketStr) {
        GameSession session = requireSession(gameId);

        synchronized (session) {
            if (session.getPhase() != GamePhase.IN_PROGRESS)
                throw new ConflictException("Game is not in progress");
            if (!playerId.equals(session.getCurrentPlayerId()))
                throw new ForbiddenException("Not your turn");

            // Ticket may be "DOUBLE_BUS", "DOUBLE_ESCOOTER", etc. for the first leg
            // of a double move — the prefix carries the DOUBLE card and the suffix
            // carries the transport used on that leg.
            TicketType ticket;
            TicketType doubleTransport = null;
            if (ticketStr.startsWith("DOUBLE_")) {
                ticket = TicketType.DOUBLE;
                String transportStr = ticketStr.substring(7);
                try { doubleTransport = TicketType.valueOf(transportStr); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown ticket: " + ticketStr); }
            } else {
                try { ticket = TicketType.valueOf(ticketStr); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown ticket: " + ticketStr); }
            }

            Player player = findPlayer(session, playerId);

            if (player instanceof MrXPlayer mrX) {
                applyMrXMove(session, mrX, toNodeId, ticket, doubleTransport);
            } else {
                applyDetectiveMove(session, (DetectivePlayer) player, toNodeId, ticket);
            }

            broadcastToAllPlayers(session);

            return toStateForPlayer(session, playerId);
        }
    }

    // ── Move application ─────────────────────────────────────────────────────

    private void applyMrXMove(GameSession session, MrXPlayer mrX, int toNodeId,
                              TicketType ticket, TicketType doubleTransport) {
        Set<Integer> detectiveNodes = detectiveNodeIds(session);

        if (detectiveNodes.contains(toNodeId))
            throw new IllegalArgumentException("Mr X cannot move to a node occupied by a detective");

        boolean doubleFirstLeg = ticket == TicketType.DOUBLE;
        boolean doubleSecondLeg = session.isMrXDoubleMovePending();

        if (doubleFirstLeg) {
            // Validate + deduct the transport ticket for this leg, then the DOUBLE card.
            if (doubleTransport != null) {
                validateAndDeductTicket(mrX, toNodeId, doubleTransport);
            } else {
                if (!mapGraph.isAdjacent(mrX.getNodeId(), toNodeId))
                    throw new IllegalArgumentException("Node is not adjacent");
            }
            mrX.useTicket(TicketType.DOUBLE);
        } else {
            validateAndDeductTicket(mrX, toNodeId, ticket);
        }

        mrX.setNodeId(toNodeId);

        boolean revealRound = REVEAL_ROUNDS.contains(session.getRound());
        int leg = doubleFirstLeg ? 1 : (doubleSecondLeg ? 2 : 1);
        boolean finalLeg = !doubleFirstLeg;
        Integer revealedNode = (revealRound && finalLeg) ? toNodeId : null;
        // Log the actual transport used; mark first leg with doubleMove=true so the
        // frontend can display a ×2 indicator alongside the ticket type.
        TicketType logTicket = (doubleFirstLeg && doubleTransport != null) ? doubleTransport : ticket;
        session.getMrXLog().add(new MrXLogEntry(session.getRound(), leg, logTicket, revealedNode, doubleFirstLeg));

        if (doubleFirstLeg) {
            session.setMrXDoubleMovePending(true);
            resetTurnTimer(session);
            pushValidMoves(session, mrX.getId());
        } else {
            session.setMrXDoubleMovePending(false);
            advanceToDetectiveTurn(session);
        }
    }

    private void applyDetectiveMove(GameSession session, DetectivePlayer detective, int toNodeId, TicketType ticket) {
        validateAndDeductTicket(detective, toNodeId, ticket);
        detective.setNodeId(toNodeId);

        // Check if detective caught Mr X
        MrXPlayer mrX = session.getMrX();
        if (mrX != null && toNodeId == mrX.getNodeId()) {
            session.setPhase(GamePhase.ENDED);
            session.setWinner("DETECTIVES");
            return;
        }

        advanceDetectiveTurn(session);
    }

    // ── Turn advancement ─────────────────────────────────────────────────────

    private void advanceToDetectiveTurn(GameSession session) {
        List<Player> detectives = session.getDetectives();
        session.setTurnPhase(TurnPhase.DETECTIVE_TURN);
        session.setCurrentDetectiveIndex(0);
        resetTurnTimer(session);
        skipToNextDetectiveWithMoves(session, detectives, 0);
    }

    private void advanceDetectiveTurn(GameSession session) {
        List<Player> detectives = session.getDetectives();
        int next = session.getCurrentDetectiveIndex() + 1;
        skipToNextDetectiveWithMoves(session, detectives, next);
    }

    /** Finds the next detective (from {@code startIdx}) with valid moves. If none, advances the round. */
    private void skipToNextDetectiveWithMoves(GameSession session, List<Player> detectives, int startIdx) {
        for (int i = startIdx; i < detectives.size(); i++) {
            Player det = detectives.get(i);
            if (!computeValidMoves(session, det).isEmpty()) {
                session.setCurrentDetectiveIndex(i);
                session.setCurrentPlayerId(det.getId());
                resetTurnTimer(session);
                pushValidMoves(session, det.getId());
                return;
            }
        }
        // All remaining detectives have no valid moves — advance round
        advanceRound(session);
    }

    private void advanceRound(GameSession session) {
        if (session.getRound() >= 24) {
            session.setPhase(GamePhase.ENDED);
            session.setWinner("MR_X");
            return;
        }
        session.setRound(session.getRound() + 1);
        session.setTurnPhase(TurnPhase.MR_X_TURN);
        session.setCurrentDetectiveIndex(0);
        MrXPlayer mrX = session.getMrX();
        session.setCurrentPlayerId(mrX.getId());
        resetTurnTimer(session);
        pushValidMoves(session, mrX.getId());
    }

    // ── Validation helpers ────────────────────────────────────────────────────

    private void validateAndDeductTicket(Player player, int toNodeId, TicketType ticket) {
        if (ticket == TicketType.DOUBLE)
            throw new IllegalArgumentException("DOUBLE can only be used as the first leg of a double move");

        Integer count = player.getTicket(ticket);
        if (count == null)
            throw new IllegalArgumentException("Player does not have ticket: " + ticket);
        if (count == 0)
            throw new IllegalArgumentException("No " + ticket + " tickets remaining");

        if (ticket == TicketType.BLACK) {
            if (!mapGraph.isAdjacent(player.getNodeId(), toNodeId))
                throw new IllegalArgumentException("Node is not adjacent");
        } else {
            Set<TicketType> modes = mapGraph.getEdgeModes(player.getNodeId(), toNodeId);
            if (modes.isEmpty())
                throw new IllegalArgumentException("No connection between those nodes");
            if (!modes.contains(ticket))
                throw new IllegalArgumentException("Ticket type " + ticket + " not valid for this edge");
        }

        player.useTicket(ticket);
    }

    private List<ValidMove> computeValidMoves(GameSession session, Player player) {
        if (player.getNodeId() == null) return List.of();
        boolean isMrX = player instanceof MrXPlayer;
        Set<Integer> blocked = isMrX ? detectiveNodeIds(session) : Set.of();
        return mapGraph.validMoves(player.getNodeId(), player.getTickets(), isMrX,
                session.isMrXDoubleMovePending(), blocked);
    }

    private Set<Integer> detectiveNodeIds(GameSession session) {
        return session.getDetectives().stream()
                .map(Player::getNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    /** Unfiltered — used for lobby broadcasts where there is no sensitive data. */
    private GameState toStateUnfiltered(GameSession session) {
        return buildState(session, null);
    }

    /** Role-filtered view for a specific player. */
    private GameState toStateForPlayer(GameSession session, String viewingPlayerId) {
        return buildState(session, viewingPlayerId);
    }

    private GameState buildState(GameSession session, String viewingPlayerId) {
        boolean viewerIsMrX = viewingPlayerId != null && session.getPlayers().stream()
                .anyMatch(p -> p.getId().equals(viewingPlayerId) && p instanceof MrXPlayer);

        List<PlayerView> players = session.getPlayers().stream()
                .map(p -> toPlayerView(p, viewerIsMrX, session))
                .collect(Collectors.toList());

        List<MrXLogEntryView> mrXLog = session.getMrXLog().stream()
                .map(this::toLogEntryView)
                .collect(Collectors.toList());

        return new GameState(
                session.getId(),
                session.getJoinCode(),
                session.getPhase(),
                session.getMaxPlayers(),
                players,
                session.getRound(),
                session.getTurnPhase(),
                session.getCurrentPlayerId(),
                session.getWinner(),
                session.getAbortReason(),
                mrXLog,
                session.isMrXDoubleMovePending());
    }

    private PlayerView toPlayerView(Player player, boolean viewerIsMrX, GameSession session) {
        Integer nodeId;
        if (player instanceof MrXPlayer && !viewerIsMrX) {
            // Hide Mr X's position unless a reveal entry exists for the current round
            nodeId = session.getMrXLog().stream()
                    .filter(e -> e.getRound() == session.getRound() && e.getNodeId() != null)
                    .map(MrXLogEntry::getNodeId)
                    .findFirst()
                    .orElse(null);
        } else {
            nodeId = player.getNodeId();
        }
        return new PlayerView(player.getId(), player.getName(), player.getRole(), nodeId, player.getTickets());
    }

    private MrXLogEntryView toLogEntryView(MrXLogEntry e) {
        return new MrXLogEntryView(e.getRound(), e.getLeg(), e.getTicketUsed(), e.getNodeId(), e.isDoubleMove());
    }

    // ── Turn timer ────────────────────────────────────────────────────────────

    private void resetTurnTimer(GameSession session) {
        session.setTurnStartedAt(System.currentTimeMillis());
    }

    /** Runs every 30 s and terminates any in-progress game whose current player
     *  has not moved within TURN_TIMEOUT_MS (15 minutes). */
    @Scheduled(fixedDelay = 30_000)
    public void checkTurnTimers() {
        long now = System.currentTimeMillis();
        for (GameSession session : games.values()) {
            if (session.getPhase() != GamePhase.IN_PROGRESS) continue;
            long started = session.getTurnStartedAt();
            if (started == 0 || now - started <= TURN_TIMEOUT_MS) continue;
            synchronized (session) {
                if (session.getPhase() != GamePhase.IN_PROGRESS) continue;
                if (now - session.getTurnStartedAt() <= TURN_TIMEOUT_MS) continue;
                session.setPhase(GamePhase.ENDED);
                session.setAbortReason("A player exceeded the 15-minute turn limit");
                broadcastToAllPlayers(session);
            }
        }
    }

    // ── Broadcasting ──────────────────────────────────────────────────────────

    /** Shared topic — for lobby operations and phase-change detection. */
    private void broadcastShared(GameSession session) {
        messaging.convertAndSend("/topic/games/" + session.getId(), toStateUnfiltered(session));
    }

    /** Per-player filtered topics — for in-game state with Mr X position hidden. */
    private void broadcastToAllPlayers(GameSession session) {
        for (Player p : session.getPlayers()) {
            messaging.convertAndSend(
                "/topic/games/" + session.getId() + "/players/" + p.getId(),
                toStateForPlayer(session, p.getId())
            );
        }
    }

    /** Push valid moves to a specific player's topic. */
    private void pushValidMoves(GameSession session, String playerId) {
        if (session.getPhase() != GamePhase.IN_PROGRESS) return;
        Player player = findPlayerOrNull(session, playerId);
        if (player == null) return;
        messaging.convertAndSend(
            "/topic/games/" + session.getId() + "/players/" + playerId + "/valid-moves",
            computeValidMoves(session, player)
        );
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private GameSession requireSession(String gameId) {
        GameSession session = games.get(gameId);
        if (session == null) throw new GameNotFoundException("Game not found");
        return session;
    }

    private GameSession findByJoinCode(String joinCode) {
        return games.values().stream()
                .filter(s -> joinCode.equals(s.getJoinCode()))
                .findFirst()
                .orElseThrow(() -> new GameNotFoundException("Game not found"));
    }

    private Player findPlayer(GameSession session, String playerId) {
        return session.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new GameNotFoundException("Player not found"));
    }

    private Player findPlayerOrNull(GameSession session, String playerId) {
        return session.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst().orElse(null);
    }

    private String generateJoinCode() {
        StringBuilder sb = new StringBuilder(JOIN_CODE_LENGTH);
        for (int i = 0; i < JOIN_CODE_LENGTH; i++)
            sb.append(JOIN_CODE_CHARS.charAt(random.nextInt(JOIN_CODE_CHARS.length())));
        return sb.toString();
    }
}
