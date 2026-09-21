package com.huntingmrxwellington.game;

/** One leg of Mr X's travel log. nodeId is only set on the final leg of a reveal round,
 *  so the record is safe to send to every player as-is. doubleMove marks the first leg of
 *  a double move. */
public record MrXMove(int round, int leg, TicketType ticketUsed, Integer nodeId, boolean doubleMove) {}
