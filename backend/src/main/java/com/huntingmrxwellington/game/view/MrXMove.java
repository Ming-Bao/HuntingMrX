package com.huntingmrxwellington.game.view;

import com.huntingmrxwellington.game.enums.TicketType;

/**
 * One leg of Mr X's travel log. Safe to send to every player as-is.
 *
 * @param round the round the leg was played in
 * @param leg 1, or 2 for the second leg of a double move
 * @param ticketUsed the ticket Mr X paid with
 * @param nodeId where he landed, only on the final leg of a reveal round; otherwise null
 * @param doubleMove true on the first leg of a double move
 */
public record MrXMove(int round, int leg, TicketType ticketUsed, Integer nodeId, boolean doubleMove) {}
