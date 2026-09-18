package com.huntingmrxwellington.dto;

import com.huntingmrxwellington.model.TicketType;

/** Client-facing snapshot of one MrXLogEntry (round/leg/ticket/reveal). */
public record MrXLogEntryView(int round, int leg, TicketType ticketUsed, Integer nodeId, boolean doubleMove) {}
