package com.huntingmrxwellington.model;

import java.util.Map;

public class LobbyPlayer extends AbstractPlayer {

    public LobbyPlayer(String id, String name) {
        super(id, name);
    }

    @Override public Role getRole() { return null; }
    @Override public Map<TicketType, Integer> getTickets() { return null; }
    @Override public Integer getTicket(TicketType ticket) { return null; }

    @Override
    public void useTicket(TicketType ticket) {
        throw new UnsupportedOperationException("LobbyPlayer has no tickets");
    }
}
