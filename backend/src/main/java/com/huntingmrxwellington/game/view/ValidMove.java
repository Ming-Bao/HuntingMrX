package com.huntingmrxwellington.game.view;

import com.huntingmrxwellington.game.enums.TicketType;

import java.util.List;

/**
 * A node the player can reach in one leg, and the tickets that would pay for it.
 *
 * @param nodeId the node reachable in one leg
 * @param ticketOptions the tickets the player could pay with
 */
public record ValidMove(int nodeId, List<TicketType> ticketOptions) {}
