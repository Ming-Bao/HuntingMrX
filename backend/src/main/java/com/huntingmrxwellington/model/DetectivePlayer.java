package com.huntingmrxwellington.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class DetectivePlayer implements Player {

    private final String id;
    private final String name;
    private Integer nodeId;
    private final Map<TicketType, Integer> tickets;

    public DetectivePlayer(String id, String name, int escooter, int bus, int train, int ferry) {
        this.id = id;
        this.name = name;
        EnumMap<TicketType, Integer> t = new EnumMap<>(TicketType.class);
        t.put(TicketType.ESCOOTER, escooter);
        t.put(TicketType.BUS,      bus);
        t.put(TicketType.TRAIN,    train);
        t.put(TicketType.FERRY,    ferry);
        this.tickets = t;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public Role getRole() { return Role.DETECTIVE; }
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
        if (count == null) throw new IllegalArgumentException("Detective does not hold ticket: " + ticket);
        if (count <= 0) throw new IllegalStateException("No " + ticket + " tickets remaining");
        tickets.put(ticket, count - 1);
    }
}
