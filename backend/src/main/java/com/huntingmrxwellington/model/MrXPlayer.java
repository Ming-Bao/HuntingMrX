package com.huntingmrxwellington.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class MrXPlayer implements Player {

    private static final int UNLIMITED = -1;

    private final String id;
    private final String name;
    private Integer nodeId;
    private final Map<TicketType, Integer> tickets;

    public MrXPlayer(String id, String name, int detectiveCount) {
        this.id = id;
        this.name = name;
        EnumMap<TicketType, Integer> t = new EnumMap<>(TicketType.class);
        t.put(TicketType.ESCOOTER, UNLIMITED);
        t.put(TicketType.BUS,      UNLIMITED);
        t.put(TicketType.TRAIN,    UNLIMITED);
        t.put(TicketType.FERRY,    UNLIMITED);
        t.put(TicketType.DOUBLE,   2);
        t.put(TicketType.BLACK,    detectiveCount);
        this.tickets = t;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public Role getRole() { return Role.MR_X; }
    @Override public Integer getNodeId() { return nodeId; }
    @Override public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
    @Override public Map<TicketType, Integer> getTickets() { return Collections.unmodifiableMap(tickets); }

    @Override
    public Integer getTicket(TicketType ticket) {
        return tickets.get(ticket);
    }

    @Override
    public void useTicket(TicketType ticket) {
        Integer count = tickets.get(ticket);
        if (count == null) throw new IllegalArgumentException("Mr X does not hold ticket: " + ticket);
        if (count == UNLIMITED) return;
        if (count <= 0) throw new IllegalStateException("No " + ticket + " tickets remaining");
        tickets.put(ticket, count - 1);
    }
}
