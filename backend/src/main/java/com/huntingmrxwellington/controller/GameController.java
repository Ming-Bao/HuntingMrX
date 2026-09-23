package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.game.view.GameState;
import com.huntingmrxwellington.game.view.ValidMove;
import com.huntingmrxwellington.service.GameService.JoinResponse;
import com.huntingmrxwellington.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST API. Calls that act as a player carry the player's secret token in X-Player-Token. */
@RestController
@RequestMapping("/api/games")
public class GameController {

    /** The request header that carries the caller's secret player token. */
    public static final String TOKEN_HEADER = "X-Player-Token";

    /**
     * Body of POST /create.
     *
     * @param hostName the host's display name
     * @param maxPlayers how many players the lobby takes, 2 to 6
     */
    public record CreateGameRequest(String hostName, int maxPlayers) {}
    /**
     * Body of POST /join.
     *
     * @param joinCode the game's join code
     * @param playerName the new player's display name
     */
    public record JoinGameRequest(String joinCode, String playerName) {}
    /**
     * Body of POST /{id}/moves.
     *
     * @param toNodeId the node to move to; required
     * @param ticket e.g. "BUS", or "DOUBLE_BUS" for the first leg of a double move
     */
    public record MoveRequest(Integer toNodeId, String ticket) {}

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    /**
     * Creates a game with the caller as host.
     *
     * @param req the host's name and the lobby size
     * @return the host's id, secret token and view
     */
    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse createGame(@RequestBody CreateGameRequest req) {
        return gameService.createGame(req.hostName(), req.maxPlayers());
    }

    /**
     * Joins the lobby that has this join code.
     *
     * @param req the join code and the new player's name
     * @return the new player's id, secret token and view
     */
    @PostMapping("/join")
    public JoinResponse joinGame(@RequestBody JoinGameRequest req) {
        return gameService.joinGame(req.joinCode(), req.playerName());
    }

    /**
     * The game as the token's owner sees it, or the public view without a valid token.
     *
     * @param id the game's id
     * @param token the caller's secret token, if any
     * @return the caller's view, or the public view
     */
    @GetMapping("/{id}")
    public GameState getGame(@PathVariable String id,
                             @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return gameService.getGame(id, token);
    }

    /**
     * The host starts the game.
     *
     * @param id the game's id
     * @param token the host's secret token
     * @return the game as the host now sees it
     */
    @PostMapping("/{id}/start")
    public GameState startGame(@PathVariable String id,
                               @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return gameService.startGame(id, token);
    }

    /**
     * Removes a player: your own id means leave, anyone else's is the host kicking them.
     *
     * @param id the game's id
     * @param targetPlayerId the public id of the player to remove
     * @param token the caller's secret token
     */
    @DeleteMapping("/{id}/players/{targetPlayerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePlayer(@PathVariable String id, @PathVariable String targetPlayerId,
                             @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.removePlayer(id, token, targetPlayerId);
    }

    /**
     * The caller's legal moves from where they stand.
     *
     * @param id the game's id
     * @param token the caller's secret token
     * @return one entry per reachable node, sorted by node id
     */
    @GetMapping("/{id}/valid-moves")
    public List<ValidMove> getValidMoves(@PathVariable String id,
                                         @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return gameService.validMoves(id, token);
    }

    /**
     * Plays one leg for the caller.
     *
     * @param id the game's id
     * @param token the caller's secret token
     * @param req the node to move to and the ticket to pay with
     * @return the game as the caller now sees it
     */
    @PostMapping("/{id}/moves")
    public GameState submitMove(@PathVariable String id,
                                @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                @RequestBody MoveRequest req) {
        if (req.toNodeId() == null) throw new IllegalArgumentException("toNodeId is required");
        return gameService.submitMove(id, token, req.toNodeId(), req.ticket());
    }
}
