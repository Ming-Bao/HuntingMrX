package com.huntingmrxwellington.dto;

public class CreateGameRequest {
    private String hostName;
    private int maxPlayers;

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
}
