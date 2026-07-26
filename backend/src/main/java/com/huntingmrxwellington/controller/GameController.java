package com.huntingmrxwellington.controller;

import com.huntingmrxwellington.dto.*;
import com.huntingmrxwellington.exception.ConflictException;
import com.huntingmrxwellington.exception.ForbiddenException;
import com.huntingmrxwellington.exception.GameNotFoundException;
import com.huntingmrxwellington.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
        try {
            GameService.CreateResult result = gameService.createGame(req.getHostName(), req.getMaxPlayers());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("playerId", result.playerId(), "gameState", result.gameState()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinGame(@RequestBody JoinGameRequest req) {
        try {
            GameService.JoinResult result = gameService.joinGame(req.getJoinCode(), req.getPlayerName());
            return ResponseEntity.ok(Map.of("playerId", result.playerId(), "gameState", result.gameState()));
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGame(@PathVariable String id,
            @RequestParam(required = false) String playerId) {
        try {
            GameStateDTO state = gameService.getGame(id, playerId);
            return ResponseEntity.ok(state);
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> startGame(@PathVariable String id, @RequestBody StartGameRequest req) {
        try {
            GameStateDTO state = gameService.startGame(id, req.getPlayerId());
            return ResponseEntity.ok(state);
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/players/{targetPlayerId}")
    public ResponseEntity<?> removePlayer(
            @PathVariable String id,
            @PathVariable String targetPlayerId,
            @RequestBody(required = false) RemovePlayerRequest req) {
        try {
            String requesterId = (req != null && req.getRequesterId() != null)
                    ? req.getRequesterId()
                    : targetPlayerId;
            if (requesterId.equals(targetPlayerId)) {
                gameService.leaveGame(id, targetPlayerId);
            } else {
                gameService.kickPlayer(id, requesterId, targetPlayerId);
            }
            return ResponseEntity.noContent().build();
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/valid-moves")
    public ResponseEntity<?> getValidMoves(@PathVariable String id,
            @RequestParam String playerId) {
        try {
            ValidMovesDTO moves = gameService.getValidMoves(id, playerId);
            return ResponseEntity.ok(moves);
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/moves")
    public ResponseEntity<?> submitMove(@PathVariable String id,
            @RequestBody MoveRequestDTO req) {
        try {
            GameStateDTO state = gameService.submitMove(id, req.getPlayerId(), req.getToNodeId(), req.getTicket());
            return ResponseEntity.ok(state);
        } catch (GameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
