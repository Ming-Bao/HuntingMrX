package com.huntingmrxwellington.dto;

public class MoveRequestDTO {
    private String playerId;
    private int toNodeId;
    private String ticket;

    public String getPlayerId()         { return playerId; }
    public void setPlayerId(String id)  { this.playerId = id; }

    public int getToNodeId()            { return toNodeId; }
    public void setToNodeId(int nodeId) { this.toNodeId = nodeId; }

    public String getTicket()           { return ticket; }
    public void setTicket(String ticket){ this.ticket = ticket; }
}
