package com.huntingmrxwellington.game;

import java.util.List;

/** Client-facing snapshot of a game, built per viewer by Game.viewFor and sent over REST and STOMP. */
public record GameState(
        String gameId,
        String joinCode,
        GamePhase phase,
        int maxPlayers,
        List<PlayerView> players,
        int round,
        TurnPhase turnPhase,
        String currentPlayerId,
        Winner winner,
        String abortReason,
        List<MrXMove> mrXLog,
        boolean mrXDoubleMovePending) {}
