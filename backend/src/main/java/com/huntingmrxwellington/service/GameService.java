package com.huntingmrxwellington.service;

import com.huntingmrxwellington.config.GameSettings;
import com.huntingmrxwellington.game.Game;
import com.huntingmrxwellington.game.MapGraph;
import com.huntingmrxwellington.game.Player;
import com.huntingmrxwellington.game.exception.ForbiddenException;
import com.huntingmrxwellington.game.exception.NotFoundException;
import com.huntingmrxwellington.game.view.GameState;
import com.huntingmrxwellington.game.view.ValidMove;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the live games, locks around every call into one, resolves player tokens and
 *  publishes the new state after every change. The rules themselves live in Game. */
@Service
public class GameService {

    /** Characters a join code is made of, 6 of them per code. */
    private static final String JOIN_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * What create and join return.
     *
     * @param playerId the new player's public id
     * @param playerToken the new player's secret token; only this response ever carries it
     * @param gameState the game as the new player sees it
     */
    public record JoinResponse(String playerId, String playerToken, GameState gameState) {}

    // In memory only; games don't outlive the process.
    // ponytail: ended games are never evicted; drop them in abortIdleGames if memory ever matters.
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;
    private final MapGraph map;
    private final GameSettings settings;
    private final Random random;
    private final InstantSource clock;

    /**
     * The constructor Spring uses, with a secure random source and the real clock.
     *
     * @param messaging where state updates are published
     * @param map the board every game is played on
     * @param settings the game.* properties
     */
    @Autowired
    public GameService(SimpMessagingTemplate messaging, MapGraph map, GameSettings settings) {
        this(messaging, map, settings, new SecureRandom(), InstantSource.system());
    }

    /**
     * For tests: a seeded random source and a fake clock make games deterministic.
     *
     * @param messaging where state updates are published
     * @param map the board every game is played on
     * @param settings the game.* properties
     * @param random the source for join codes, roles, turn order and start nodes
     * @param clock where turn start times come from
     */
    GameService(SimpMessagingTemplate messaging, MapGraph map, GameSettings settings,
                Random random, InstantSource clock) {
        this.messaging = messaging;
        this.map = map;
        this.settings = settings;
        this.random = random;
        this.clock = clock;
    }

    /**
     * Creates a game in the lobby with the caller as host.
     *
     * @param hostName the host's display name
     * @param maxPlayers how many players the lobby takes, 2 to 6
     * @return the host's id, secret token and view
     */
    public JoinResponse createGame(String hostName, int maxPlayers) {
        Game game = new Game(UUID.randomUUID().toString(), newJoinCode(), maxPlayers, map,
                settings.detectiveTickets(), clock);
        Player host = game.join(hostName);
        games.put(game.id(), game);
        return new JoinResponse(host.id(), host.token(), game.viewFor(host));
    }

    /**
     * Adds a player to the lobby of the game with this join code.
     *
     * @param joinCode the game's join code; case and surrounding spaces are ignored
     * @param playerName the new player's display name
     * @return the new player's id, secret token and view
     */
    public JoinResponse joinGame(String joinCode, String playerName) {
        if (joinCode == null || joinCode.isBlank()) throw new IllegalArgumentException("Join code is required");
        String code = joinCode.trim().toUpperCase(Locale.ROOT);
        Game game = games.values().stream().filter(g -> g.joinCode().equals(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("Game not found"));
        synchronized (game) {
            Player player = game.join(playerName);
            publish(game);
            return new JoinResponse(player.id(), player.token(), game.viewFor(player));
        }
    }

    /**
     * The game as the caller sees it. A missing or unknown token gets the public view rather
     * than an error, so a kicked player's poll can still see they're gone.
     *
     * @param gameId the game's id
     * @param token the caller's secret token, or null
     * @return the caller's view, or the public view
     */
    public GameState getGame(String gameId, String token) {
        Game game = findGame(gameId);
        synchronized (game) {
            return game.viewFor(game.playerByToken(token).orElse(null));
        }
    }

    /**
     * The host starts the game: roles, turn order and start nodes are picked at random.
     *
     * @param gameId the game's id
     * @param token the caller's secret token; must be the host's
     * @return the game as the host now sees it
     */
    public GameState startGame(String gameId, String token) {
        Game game = findGame(gameId);
        synchronized (game) {
            Player player = findPlayer(game, token);
            game.start(player, random);
            publish(game);
            return game.viewFor(player);
        }
    }

    /**
     * Removes a player. Removing yourself is leaving; the host removing someone else is a kick.
     *
     * @param gameId the game's id
     * @param token the caller's secret token
     * @param targetPlayerId the public id of the player to remove
     */
    public void removePlayer(String gameId, String token, String targetPlayerId) {
        Game game = findGame(gameId);
        synchronized (game) {
            Player player = findPlayer(game, token);
            if (player.id().equals(targetPlayerId)) game.leave(player);
            else game.kick(player, targetPlayerId);
            // Nobody left to tell, so forget the game.
            if (game.players().isEmpty()) games.remove(gameId);
            else publish(game);
        }
    }

    /**
     * The caller's legal moves from where they stand.
     *
     * @param gameId the game's id
     * @param token the caller's secret token
     * @return one entry per reachable node, sorted by node id
     */
    public List<ValidMove> validMoves(String gameId, String token) {
        Game game = findGame(gameId);
        synchronized (game) {
            return game.validMoves(findPlayer(game, token));
        }
    }

    /**
     * Plays one leg for the caller.
     *
     * @param gameId the game's id
     * @param token the caller's secret token
     * @param toNodeId the node to move to
     * @param ticket the ticket to pay with; see Game.move for the format
     * @return the game as the caller now sees it
     */
    public GameState submitMove(String gameId, String token, int toNodeId, String ticket) {
        Game game = findGame(gameId);
        synchronized (game) {
            Player player = findPlayer(game, token);
            game.move(player, toNodeId, ticket);
            publish(game);
            return game.viewFor(player);
        }
    }

    /** Every 30 s, ends any game whose current player has been idle past game.turn-timer-seconds. */
    @Scheduled(fixedDelay = 30_000)
    public void abortIdleGames() {
        Duration limit = Duration.ofSeconds(settings.turnTimerSeconds());
        for (Game game : games.values()) {
            synchronized (game) {
                if (game.abortIfIdle(limit)) publish(game);
            }
        }
    }

    /**
     * Sends the new state after every change: the public view to the game topic, each player's
     * own view to their private topic (named by their secret token), and valid moves to whoever's
     * turn it is.
     *
     * @param game the game that changed
     */
    private void publish(Game game) {
        String topic = "/topic/games/" + game.id();
        messaging.convertAndSend(topic, game.viewFor(null));
        for (Player p : game.players())
            messaging.convertAndSend(topic + "/players/" + p.token(), game.viewFor(p));
        game.currentPlayer().ifPresent(p ->
                messaging.convertAndSend(topic + "/players/" + p.token() + "/valid-moves", game.validMoves(p)));
    }

    /**
     * Looks up a live game.
     *
     * @param gameId the game's id
     * @return the game
     * @throws NotFoundException (404) if there is no such game
     */
    private Game findGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new NotFoundException("Game not found");
        return game;
    }

    /**
     * Looks up the player a token belongs to.
     *
     * @param game the game to look in
     * @param token the caller's secret token
     * @return the player
     * @throws ForbiddenException (403) if the token isn't one of this game's players
     */
    private static Player findPlayer(Game game, String token) {
        return game.playerByToken(token).orElseThrow(() -> new ForbiddenException("Not a player in this game"));
    }

    /** @return a random 6-character join code */
    private String newJoinCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(JOIN_CODE_CHARS.charAt(random.nextInt(JOIN_CODE_CHARS.length())));
        return code.toString();
    }
}
