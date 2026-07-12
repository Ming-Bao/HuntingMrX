package com.scotlandyard.model;

public class MrXLogEntry {
    private final int round;
    private final int leg;
    private final TicketType ticketUsed;
    private final Integer nodeId;
    private final boolean doubleMove; // true for the first leg of a double move

    public MrXLogEntry(int round, int leg, TicketType ticketUsed, Integer nodeId, boolean doubleMove) {
        this.round = round;
        this.leg = leg;
        this.ticketUsed = ticketUsed;
        this.nodeId = nodeId;
        this.doubleMove = doubleMove;
    }

    public int getRound()             { return round; }
    public int getLeg()               { return leg; }
    public TicketType getTicketUsed() { return ticketUsed; }
    public Integer getNodeId()        { return nodeId; }
    public boolean isDoubleMove()     { return doubleMove; }
}
