package com.huntingmrxwellington.game;

import java.util.List;

/** A node the player can reach in one leg, and the tickets that would pay for it. */
public record ValidMove(int nodeId, List<TicketType> ticketOptions) {}
