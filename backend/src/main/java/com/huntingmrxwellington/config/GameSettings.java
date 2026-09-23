package com.huntingmrxwellington.config;

import com.huntingmrxwellington.game.enums.TicketType;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * The game.* properties from application.properties, the only place these values live.
 *
 * @param mapFile the board file under src/main/resources/static/
 * @param turnTimerSeconds how long a player may take over one turn before the game is aborted
 * @param detectiveEscooterTickets e-scooter tickets each detective starts with
 * @param detectiveBusTickets bus tickets each detective starts with
 * @param detectiveTrainTickets train tickets each detective starts with
 * @param detectiveFerryTickets ferry tickets each detective starts with
 */
@ConfigurationProperties(prefix = "game")
public record GameSettings(
        String mapFile,
        int turnTimerSeconds,
        int detectiveEscooterTickets,
        int detectiveBusTickets,
        int detectiveTrainTickets,
        int detectiveFerryTickets) {

    /** @return the four detective ticket counts as one map, the shape Game takes */
    public Map<TicketType, Integer> detectiveTickets() {
        return Map.of(TicketType.ESCOOTER, detectiveEscooterTickets, TicketType.BUS, detectiveBusTickets,
                TicketType.TRAIN, detectiveTrainTickets, TicketType.FERRY, detectiveFerryTickets);
    }
}
