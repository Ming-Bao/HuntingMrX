package com.huntingmrxwellington.game;

/** BLACK is shown to players as the Invisible ticket; the wire value stays BLACK.
 *  DOUBLE is only ever sent as a prefix on a move ("DOUBLE_BUS"), never as a leg. */
public enum TicketType {
    ESCOOTER, BUS, TRAIN, FERRY, BLACK, DOUBLE
}
