package com.huntingmrxwellington.dto;

import com.huntingmrxwellington.model.GamePhase;
import com.huntingmrxwellington.model.TurnPhase;
import java.util.List;

public class GameStateDTO {
    private String gameId;
    private String joinCode;
    private GamePhase phase;
    private int maxPlayers;
    private List<PlayerDTO> players;
    private int round;
    private TurnPhase turnPhase;
    private String currentPlayerId;
    private String winner;
    private String abortReason;
    private List<MrXLogEntryDTO> mrXLog;
    private boolean mrXDoubleMovePending;

    public String getGameId()                               { return gameId; }
    public void setGameId(String gameId)                    { this.gameId = gameId; }

    public String getJoinCode()                             { return joinCode; }
    public void setJoinCode(String joinCode)                { this.joinCode = joinCode; }

    public GamePhase getPhase()                             { return phase; }
    public void setPhase(GamePhase phase)                   { this.phase = phase; }

    public int getMaxPlayers()                              { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers)               { this.maxPlayers = maxPlayers; }

    public List<PlayerDTO> getPlayers()                     { return players; }
    public void setPlayers(List<PlayerDTO> players)         { this.players = players; }

    public int getRound()                                   { return round; }
    public void setRound(int round)                         { this.round = round; }

    public TurnPhase getTurnPhase()                         { return turnPhase; }
    public void setTurnPhase(TurnPhase turnPhase)           { this.turnPhase = turnPhase; }

    public String getCurrentPlayerId()                      { return currentPlayerId; }
    public void setCurrentPlayerId(String id)               { this.currentPlayerId = id; }

    public String getWinner()                               { return winner; }
    public void setWinner(String winner)                    { this.winner = winner; }

    public String getAbortReason()                          { return abortReason; }
    public void setAbortReason(String reason)               { this.abortReason = reason; }

    public List<MrXLogEntryDTO> getMrXLog()                 { return mrXLog; }
    public void setMrXLog(List<MrXLogEntryDTO> mrXLog)      { this.mrXLog = mrXLog; }

    public boolean isMrXDoubleMovePending()                 { return mrXDoubleMovePending; }
    public void setMrXDoubleMovePending(boolean b)          { this.mrXDoubleMovePending = b; }
}
