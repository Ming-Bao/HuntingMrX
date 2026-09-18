package com.huntingmrxwellington.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class MrXPlayer extends AbstractPlayer {

    private static final int UNLIMITED = -1;

    private final Map<TicketType, Integer> tickets;

    public MrXPlayer(String id, String name, int detectiveCount) {
        super(id, name);
        EnumMap<TicketType, Integer> t = new EnumMap<>(TicketType.class);
        t.put(TicketType.ESCOOTER, UNLIMITED);
        t.put(TicketType.BUS,      UNLIMITED);
        t.put(TicketType.TRAIN,    UNLIMITED);
        t.put(TicketType.FERRY,    UNLIMITED);
        t.put(TicketType.DOUBLE,   2);
        t.put(TicketType.BLACK,    detectiveCount);
        this.tickets = t;
    }

    @Override public Role getRole() { return Role.MR_X; }
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
