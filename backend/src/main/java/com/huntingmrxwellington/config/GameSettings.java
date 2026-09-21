package com.huntingmrxwellington.config;

import com.huntingmrxwellington.game.TicketType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** The game.* properties from application.properties, the only place these values live. */
@ConfigurationProperties(prefix = "game")
public record GameSettings(
        String mapFile,
        int turnTimerSeconds,
        int detectiveEscooterTickets,
        int detectiveBusTickets,
        int detectiveTrainTickets,
        int detectiveFerryTickets) {

    public Map<TicketType, Integer> detectiveTickets() {
        return Map.of(TicketType.ESCOOTER, detectiveEscooterTickets, TicketType.BUS, detectiveBusTickets,
                TicketType.TRAIN, detectiveTrainTickets, TicketType.FERRY, detectiveFerryTickets);
    }
}
