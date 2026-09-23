package com.huntingmrxwellington.game.view;

import com.huntingmrxwellington.game.enums.GamePhase;
import com.huntingmrxwellington.game.enums.TurnPhase;
import com.huntingmrxwellington.game.enums.Winner;

import java.util.List;

/**
 * Client-facing snapshot of a game, built per viewer by Game.viewFor and sent over REST and STOMP.
 *
 * @param gameId the game's public id
 * @param joinCode the short code players type to join
 * @param phase lobby, in progress or ended
 * @param maxPlayers how many players the lobby takes
 * @param players every player as this viewer sees them, in turn order once started
 * @param round the current round; 0 in the lobby
 * @param turnPhase which side is moving; null unless the game is in progress
 * @param currentPlayerId whose move it is; null unless the game is in progress
 * @param winner who won; null until someone wins
 * @param abortReason why the game ended early; null otherwise
 * @param mrXLog Mr X's travel log, one entry per leg
 * @param mrXDoubleMovePending true between the two legs of Mr X's double move
 */
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
