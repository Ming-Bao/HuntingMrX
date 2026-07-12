package com.scotlandyard.dto;

import com.scotlandyard.model.TicketType;

public class MrXLogEntryDTO {
    private int round;
    private int leg;
    private TicketType ticketUsed;
    private Integer nodeId;
    private boolean doubleMove;

    public int getRound()                      { return round; }
    public void setRound(int round)            { this.round = round; }

    public int getLeg()                        { return leg; }
    public void setLeg(int leg)               { this.leg = leg; }

    public TicketType getTicketUsed()          { return ticketUsed; }
    public void setTicketUsed(TicketType t)   { this.ticketUsed = t; }

    public Integer getNodeId()                 { return nodeId; }
    public void setNodeId(Integer nodeId)     { this.nodeId = nodeId; }

    public boolean isDoubleMove()              { return doubleMove; }
    public void setDoubleMove(boolean d)      { this.doubleMove = d; }
}
