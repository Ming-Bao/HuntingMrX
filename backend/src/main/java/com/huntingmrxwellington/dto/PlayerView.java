package com.huntingmrxwellington.dto;

import com.huntingmrxwellington.model.Role;
import com.huntingmrxwellington.model.TicketType;
import java.util.Map;

/** Client-facing snapshot of one player. nodeId is null for Mr X when the
 *  viewer isn't Mr X and no reveal has happened yet this round. */
public record PlayerView(String id, String name, Role role, Integer nodeId, Map<TicketType, Integer> tickets) {}
