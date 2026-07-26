package com.huntingmrxwellington.model;

import java.util.Map;

public interface Player {
    String getId();
    String getName();
    Role getRole();
    Integer getNodeId();
    void setNodeId(Integer nodeId);
    Map<TicketType, Integer> getTickets();
    Integer getTicket(TicketType ticket);
    void useTicket(TicketType ticket);
}
