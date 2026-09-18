package com.huntingmrxwellington.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class DetectivePlayer extends AbstractPlayer {

    private final Map<TicketType, Integer> tickets;

    public DetectivePlayer(String id, String name, int escooter, int bus, int train, int ferry) {
        super(id, name);
        EnumMap<TicketType, Integer> t = new EnumMap<>(TicketType.class);
        t.put(TicketType.ESCOOTER, escooter);
        t.put(TicketType.BUS,      bus);
        t.put(TicketType.TRAIN,    train);
        t.put(TicketType.FERRY,    ferry);
        this.tickets = t;
    }

    @Override public Role getRole() { return Role.DETECTIVE; }
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
