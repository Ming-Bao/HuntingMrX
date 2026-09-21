package com.huntingmrxwellington.service;

import com.huntingmrxwellington.config.GameSettings;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.game.Game;
import com.huntingmrxwellington.game.GameState;
import com.huntingmrxwellington.game.MapGraph;
import com.huntingmrxwellington.game.Player;
import com.huntingmrxwellington.game.ValidMove;
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

    private static final String JOIN_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** What create and join return. playerToken is secret: only this response ever carries it. */
    public record JoinResponse(String playerId, String playerToken, GameState gameState) {}

    // In memory only; games don't outlive the process.
    // ponytail: ended games are never evicted; drop them in abortIdleGames if memory ever matters.
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;
    private final MapGraph map;
    private final GameSettings settings;
    private final Random random;
    private final InstantSource clock;

    @Autowired
    public GameService(SimpMessagingTemplate messaging, MapGraph map, GameSettings settings) {
        this(messaging, map, settings, new SecureRandom(), InstantSource.system());
    }

    GameService(SimpMessagingTemplate messaging, MapGraph map, GameSettings settings,
                Random random, InstantSource clock) {
        this.messaging = messaging;
        this.map = map;
        this.settings = settings;
        this.random = random;
        this.clock = clock;
    }

    public JoinResponse createGame(String hostName, int maxPlayers) {
        Game game = new Game(UUID.randomUUID().toString(), newJoinCode(), maxPlayers, map,
                settings.detectiveTickets(), clock);
        Player host = game.join(hostName);
        games.put(game.id(), game);
        return new JoinResponse(host.id(), host.token(), game.viewFor(host));
    }

    public JoinResponse joinGame(String joinCode, String playerName) {
        if (joinCode == null || joinCode.isBlank()) throw new IllegalArgumentException("Join code is required");
        String code = joinCode.trim().toUpperCase(Locale.ROOT);
        Game game = games.values().stream().filter(g -> g.joinCode().equals(code)).findFirst()
                .orElseThrow(() -> new GameNotFoundException("Game not found"));
        synchronized (game) {
            Player player = game.join(playerName);
            publish(game);
            return new JoinResponse(player.id(), player.token(), game.viewFor(player));
        }
    }

    /** A valid token gets that player's view. A missing or unknown one gets the public view rather
     *  than an error, so a kicked player's poll can still see they're gone. */
    public GameState getGame(String gameId, String token) {
        Game game = find(gameId);
        synchronized (game) {
            return game.viewFor(game.playerByToken(token).orElse(null));
        }
    }

    public GameState startGame(String gameId, String token) {
        Game game = find(gameId);
        synchronized (game) {
            Player player = player(game, token);
            game.start(player, random);
            publish(game);
            return game.viewFor(player);
        }
    }

    /** Removing yourself is leaving; the host removing someone else is a kick. */
    public void removePlayer(String gameId, String token, String targetPlayerId) {
        Game game = find(gameId);
        synchronized (game) {
            Player player = player(game, token);
            if (player.id().equals(targetPlayerId)) {
                if (game.leave(player)) {
                    games.remove(gameId);
                    return;
                }
            } else {
                game.kick(player, targetPlayerId);
            }
            publish(game);
        }
    }

    public List<ValidMove> validMoves(String gameId, String token) {
        Game game = find(gameId);
        synchronized (game) {
            return game.validMoves(player(game, token));
        }
    }

    public GameState submitMove(String gameId, String token, int toNodeId, String ticket) {
        Game game = find(gameId);
        synchronized (game) {
            Player player = player(game, token);
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

    /** After every change: the public view to the lobby topic, each player's own view to their
     *  private topic (named by their secret token), and valid moves to whoever's turn it is. */
    private void publish(Game game) {
        String topic = "/topic/games/" + game.id();
        messaging.convertAndSend(topic, game.viewFor(null));
        for (Player p : game.players())
            messaging.convertAndSend(topic + "/players/" + p.token(), game.viewFor(p));
        game.currentPlayer().ifPresent(p ->
                messaging.convertAndSend(topic + "/players/" + p.token() + "/valid-moves", game.validMoves(p)));
    }

    private Game find(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new GameNotFoundException("Game not found");
        return game;
    }

    private static Player player(Game game, String token) {
        return game.playerByToken(token).orElseThrow(() -> new ForbiddenException("Not a player in this game"));
    }

    private String newJoinCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(JOIN_CODE_CHARS.charAt(random.nextInt(JOIN_CODE_CHARS.length())));
        return code.toString();
    }
}
