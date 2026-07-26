package com.huntingmrxwellington.dto;

public class JoinGameRequest {
    private String joinCode;
    private String playerName;

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
}
