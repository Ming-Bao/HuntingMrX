package com.huntingmrxwellington.game.view;

import com.huntingmrxwellington.game.enums.Role;
import com.huntingmrxwellington.game.enums.TicketType;

import java.util.Map;

/**
 * What one viewer sees of one player. It never includes the player's token.
 *
 * @param id the player's public id
 * @param name the display name
 * @param role the player's side; null in the lobby
 * @param nodeId where the player stands; null for Mr X unless the viewer is Mr X or
 *               he was revealed this round
 * @param tickets the ticket counts; null in the lobby
 */
public record PlayerView(String id, String name, Role role, Integer nodeId, Map<TicketType, Integer> tickets) {}
