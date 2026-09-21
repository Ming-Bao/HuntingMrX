package com.huntingmrxwellington.game;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** One player. id is public and sent to everyone; token is the secret the player
 *  acts with, and it must never appear in a view or broadcast. */
public final class Player {

    /** Ticket count that never runs out (Mr X's transport tickets). */
    public static final int UNLIMITED = -1;

    private final String id = UUID.randomUUID().toString();
    private final String token = UUID.randomUUID().toString();
    private final String name;
    private final EnumMap<TicketType, Integer> tickets = new EnumMap<>(TicketType.class);
    private Role role;      // null until the game starts
    private Integer node;   // null until the game starts

    Player(String name) {
        this.name = name;
    }

    public String id() { return id; }
    public String token() { return token; }
    public String name() { return name; }
    public Role role() { return role; }
    public Integer node() { return node; }
    public boolean isMrX() { return role == Role.MR_X; }

    /** A copy of the ticket counts; empty until the game starts. */
    public Map<TicketType, Integer> tickets() {
        return new EnumMap<>(tickets);
    }

    public boolean has(TicketType ticket) {
        int count = tickets.getOrDefault(ticket, 0);
        return count == UNLIMITED || count > 0;
    }

    void assign(Role role, int node, Map<TicketType, Integer> startingTickets) {
        this.role = role;
        this.node = node;
        tickets.putAll(startingTickets);
    }

    void moveTo(int node) {
        this.node = node;
    }

    /** Uses up one ticket; unlimited tickets are never used up. */
    void spend(TicketType ticket) {
        if (!has(ticket)) throw new IllegalArgumentException("No " + ticket + " tickets left");
        int count = tickets.get(ticket);
        if (count != UNLIMITED) tickets.put(ticket, count - 1);
    }
}
