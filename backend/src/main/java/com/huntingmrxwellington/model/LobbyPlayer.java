package com.huntingmrxwellington.model;

import java.util.Map;

public class LobbyPlayer implements Player {

    private final String id;
    private final String name;
    private Integer nodeId;

    public LobbyPlayer(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public Role getRole() { return null; }
    @Override public Integer getNodeId() { return nodeId; }
    @Override public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
    @Override public Map<TicketType, Integer> getTickets() { return null; }
    @Override public Integer getTicket(TicketType ticket) { return null; }

    @Override
    public void useTicket(TicketType ticket) {
        throw new UnsupportedOperationException("LobbyPlayer has no tickets");
    }
}
