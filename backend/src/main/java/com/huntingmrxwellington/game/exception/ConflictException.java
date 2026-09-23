package com.huntingmrxwellington.game.exception;

/** The request doesn't fit the game's current state, e.g. joining a game that has started
 *  or a full lobby. Sent as HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
