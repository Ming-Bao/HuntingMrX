package com.huntingmrxwellington.game;

import java.util.Map;

/** What one viewer sees of one player. nodeId is null for Mr X unless the viewer is Mr X
 *  or he was revealed this round; role and tickets are null in the lobby. No token. */
public record PlayerView(String id, String name, Role role, Integer nodeId, Map<TicketType, Integer> tickets) {}
