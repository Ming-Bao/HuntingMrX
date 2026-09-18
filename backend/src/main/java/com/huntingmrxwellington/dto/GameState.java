package com.huntingmrxwellington.dto;

import com.huntingmrxwellington.model.GamePhase;
import com.huntingmrxwellington.model.TurnPhase;
import java.util.List;

/** Client-facing snapshot of a game — the live domain object is GameSession;
 *  this is the filtered/public view sent over REST and WebSocket. */
public record GameState(
        String gameId,
        String joinCode,
        GamePhase phase,
        int maxPlayers,
        List<PlayerView> players,
        int round,
        TurnPhase turnPhase,
        String currentPlayerId,
        String winner,
        String abortReason,
        List<MrXLogEntryView> mrXLog,
        boolean mrXDoubleMovePending) {}
