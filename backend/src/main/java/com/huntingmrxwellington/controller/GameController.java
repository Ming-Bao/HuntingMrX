package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.game.GameState;
import com.huntingmrxwellington.game.ValidMove;
import com.huntingmrxwellington.service.GameService;
import com.huntingmrxwellington.service.GameService.JoinResponse;
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

    public static final String TOKEN_HEADER = "X-Player-Token";

    public record CreateGameRequest(String hostName, int maxPlayers) {}
    public record JoinGameRequest(String joinCode, String playerName) {}
    public record MoveRequest(Integer toNodeId, String ticket) {}

    private final GameService games;

    public GameController(GameService games) {
        this.games = games;
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse createGame(@RequestBody CreateGameRequest req) {
        return games.createGame(req.hostName(), req.maxPlayers());
    }

    @PostMapping("/join")
    public JoinResponse joinGame(@RequestBody JoinGameRequest req) {
        return games.joinGame(req.joinCode(), req.playerName());
    }

    @GetMapping("/{id}")
    public GameState getGame(@PathVariable String id,
                             @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return games.getGame(id, token);
    }

    @PostMapping("/{id}/start")
    public GameState startGame(@PathVariable String id,
                               @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return games.startGame(id, token);
    }

    @DeleteMapping("/{id}/players/{targetPlayerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePlayer(@PathVariable String id, @PathVariable String targetPlayerId,
                             @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        games.removePlayer(id, token, targetPlayerId);
    }

    @GetMapping("/{id}/valid-moves")
    public List<ValidMove> getValidMoves(@PathVariable String id,
                                         @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return games.validMoves(id, token);
    }

    @PostMapping("/{id}/moves")
    public GameState submitMove(@PathVariable String id,
                                @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                @RequestBody MoveRequest req) {
        return games.submitMove(id, token, req.toNodeId(), req.ticket());
    }
}
