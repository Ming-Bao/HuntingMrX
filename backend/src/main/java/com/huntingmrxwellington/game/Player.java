package com.huntingmrxwellington.game;

import com.huntingmrxwellington.game.enums.Role;
import com.huntingmrxwellington.game.enums.TicketType;

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

    /**
     * A new lobby player with a fresh random id and token. Only Game.join creates players.
     *
     * @param name the display name, already checked by Game.join
     */
    Player(String name) {
        this.name = name;
    }

    /** @return the public id, shown to everyone */
    public String id() { return id; }
    /** @return the secret token this player acts with; never put it in a view */
    public String token() { return token; }
    /** @return the display name */
    public String name() { return name; }
    /** @return the player's side; null until the game starts */
    public Role role() { return role; }
    /** @return the node the player stands on; null until the game starts */
    public Integer node() { return node; }
    /** @return true once the game has started and this player is Mr X */
    public boolean isMrX() { return role == Role.MR_X; }

    /** @return a copy of the ticket counts (UNLIMITED for never runs out); empty until the game starts */
    public Map<TicketType, Integer> tickets() {
        return new EnumMap<>(tickets);
    }

    /**
     * Whether the player can pay with a ticket.
     *
     * @param ticket the ticket type to check
     * @return true if the player holds at least one, or an unlimited supply
     */
    public boolean has(TicketType ticket) {
        int count = tickets.getOrDefault(ticket, 0);
        return count == UNLIMITED || count > 0;
    }

    /**
     * Sets the player up at game start.
     *
     * @param role the player's side
     * @param node the start node
     * @param startingTickets the tickets they start with
     */
    void assign(Role role, int node, Map<TicketType, Integer> startingTickets) {
        this.role = role;
        this.node = node;
        tickets.putAll(startingTickets);
    }

    /**
     * Puts the player on a node. Game has already checked the move is legal.
     *
     * @param node the node to move to
     */
    void moveTo(int node) {
        this.node = node;
    }

    /**
     * Uses up one ticket; unlimited tickets are never used up.
     *
     * @param ticket the ticket to use up; the player must hold one
     */
    void spend(TicketType ticket) {
        if (!has(ticket)) throw new IllegalArgumentException("No " + ticket + " tickets left");
        int count = tickets.get(ticket);
        if (count != UNLIMITED) tickets.put(ticket, count - 1);
    }
}
