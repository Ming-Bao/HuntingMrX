package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.dto.*;
import com.huntingmrxwellington.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping("/create")
    public ResponseEntity<?> createGame(@RequestBody CreateGameRequest req) {
        GameService.CreateResult result = gameService.createGame(req.hostName(), req.maxPlayers());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("playerId", result.playerId(), "gameState", result.gameState()));
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinGame(@RequestBody JoinGameRequest req) {
        GameService.JoinResult result = gameService.joinGame(req.joinCode(), req.playerName());
        return ResponseEntity.ok(Map.of("playerId", result.playerId(), "gameState", result.gameState()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGame(@PathVariable String id,
            @RequestParam(required = false) String playerId) {
        GameState state = gameService.getGame(id, playerId);
        return ResponseEntity.ok(state);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> startGame(@PathVariable String id, @RequestBody StartGameRequest req) {
        GameState state = gameService.startGame(id, req.playerId());
        return ResponseEntity.ok(state);
    }

    @DeleteMapping("/{id}/players/{targetPlayerId}")
    public ResponseEntity<?> removePlayer(
            @PathVariable String id,
            @PathVariable String targetPlayerId,
            @RequestBody(required = false) RemovePlayerRequest req) {
        String requesterId = (req != null && req.requesterId() != null)
                ? req.requesterId()
                : targetPlayerId;
        if (requesterId.equals(targetPlayerId)) {
            gameService.leaveGame(id, targetPlayerId);
        } else {
            gameService.kickPlayer(id, requesterId, targetPlayerId);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/valid-moves")
    public ResponseEntity<?> getValidMoves(@PathVariable String id,
            @RequestParam String playerId) {
        List<ValidMove> moves = gameService.getValidMoves(id, playerId);
        return ResponseEntity.ok(moves);
    }

    @PostMapping("/{id}/moves")
    public ResponseEntity<?> submitMove(@PathVariable String id,
            @RequestBody MoveRequest req) {
        GameState state = gameService.submitMove(id, req.playerId(), req.toNodeId(), req.ticket());
        return ResponseEntity.ok(state);
    }
}
